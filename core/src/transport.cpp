// SPDX-License-Identifier: Apache-2.0
#include "syit/transport.h"

#include <limits>
#include <stdexcept>
#include <utility>

namespace syit {
namespace {

bool checked_subtract(Nanoseconds left, Nanoseconds right, Nanoseconds& result) noexcept {
    if ((right < 0 && left > std::numeric_limits<Nanoseconds>::max() + right) ||
        (right > 0 && left < std::numeric_limits<Nanoseconds>::min() + right)) {
        return false;
    }
    result = left - right;
    return true;
}

}  // namespace

BoundedFrameQueue::BoundedFrameQueue(
    std::size_t max_frames,
    std::size_t max_queued_bytes,
    std::uint32_t max_frame_size)
    : max_frames_(max_frames),
      max_queued_bytes_(max_queued_bytes),
      max_frame_size_(max_frame_size) {
    if (max_frames_ == 0U) {
        throw std::invalid_argument("max_frames must be positive");
    }
    if (max_queued_bytes_ < 6U) {
        throw std::invalid_argument("max_queued_bytes must fit an empty framed message");
    }
    if (max_frame_size_ < 2U || max_frame_size_ > kMaxFrameSize) {
        throw std::invalid_argument("max_frame_size must be between 2 and kMaxFrameSize");
    }
}

FrameQueuePushResult BoundedFrameQueue::push(Frame frame) {
    if (frame.payload.size() > static_cast<std::size_t>(max_frame_size_ - 2U)) {
        return FrameQueuePushResult::FrameTooLarge;
    }
    const std::size_t wire_size = 6U + frame.payload.size();

    std::lock_guard<std::mutex> lock(mutex_);
    if (closed_) {
        return FrameQueuePushResult::Closed;
    }
    if (frames_.size() >= max_frames_ ||
        wire_size > max_queued_bytes_ - queued_bytes_) {
        return FrameQueuePushResult::QueueFull;
    }

    queued_bytes_ += wire_size;
    frames_.push_back(std::move(frame));
    return FrameQueuePushResult::Queued;
}

bool BoundedFrameQueue::try_pop(Frame& frame) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (frames_.empty()) {
        return false;
    }

    const std::size_t wire_size = 6U + frames_.front().payload.size();
    frame = std::move(frames_.front());
    frames_.pop_front();
    queued_bytes_ -= wire_size;
    return true;
}

void BoundedFrameQueue::close() {
    std::lock_guard<std::mutex> lock(mutex_);
    closed_ = true;
}

bool BoundedFrameQueue::closed() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return closed_;
}

std::size_t BoundedFrameQueue::size() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return frames_.size();
}

std::size_t BoundedFrameQueue::queued_bytes() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return queued_bytes_;
}

SyncSample TimeSyncExchange::to_sync_sample(Nanoseconds received_at_true_ns) const noexcept {
    return {t1_ns, t2_ns, t3_ns, t4_ns, received_at_true_ns};
}

TimeSyncClient::TimeSyncClient(
    std::uint32_t session_id,
    std::uint16_t receiver_id,
    std::size_t max_pending,
    std::uint32_t initial_sequence)
    : session_id_(session_id),
      receiver_id_(receiver_id),
      max_pending_(max_pending),
      next_sequence_(initial_sequence) {
    if (max_pending_ == 0U || max_pending_ > 1024U) {
        throw std::invalid_argument("max_pending must be between 1 and 1024");
    }
    pending_.reserve(max_pending_);
}

bool TimeSyncClient::make_request(Nanoseconds t1_ns, TimeSyncPacket& request) {
    if (pending_.size() >= max_pending_) {
        return false;
    }

    std::uint32_t sequence = next_sequence_;
    bool available = false;
    for (std::size_t attempt = 0U; attempt <= max_pending_; ++attempt) {
        if (pending_.find(sequence) == pending_.end()) {
            available = true;
            break;
        }
        ++sequence;
    }
    if (!available) {
        return false;
    }

    pending_.emplace(sequence, t1_ns);
    next_sequence_ = sequence + 1U;
    request = TimeSyncPacket{
        1U, 1U, session_id_, receiver_id_, sequence, t1_ns, std::nullopt, std::nullopt};
    return true;
}

TimeSyncResponseStatus TimeSyncClient::accept_response(
    const TimeSyncPacket& response,
    Nanoseconds t4_ns,
    TimeSyncExchange& exchange) {
    if (response.packet_type != 2U) {
        return TimeSyncResponseStatus::WrongPacketType;
    }
    if (response.version != 1U) {
        return TimeSyncResponseStatus::UnsupportedVersion;
    }
    if (response.session_id != session_id_) {
        return TimeSyncResponseStatus::WrongSession;
    }
    if (response.receiver_id != receiver_id_) {
        return TimeSyncResponseStatus::WrongReceiver;
    }

    const auto pending = pending_.find(response.sequence);
    if (pending == pending_.end()) {
        return TimeSyncResponseStatus::UnknownSequence;
    }
    if (response.t1_ns != pending->second) {
        return TimeSyncResponseStatus::TimestampMismatch;
    }
    if (!response.t2_ns.has_value() || !response.t3_ns.has_value() ||
        *response.t3_ns < *response.t2_ns || t4_ns < response.t1_ns) {
        return TimeSyncResponseStatus::InvalidTimestamps;
    }

    Nanoseconds receiver_elapsed = 0;
    Nanoseconds master_elapsed = 0;
    if (!checked_subtract(t4_ns, response.t1_ns, receiver_elapsed) ||
        !checked_subtract(*response.t3_ns, *response.t2_ns, master_elapsed)) {
        return TimeSyncResponseStatus::InvalidTimestamps;
    }
    if (master_elapsed > receiver_elapsed) {
        return TimeSyncResponseStatus::NegativeRoundTrip;
    }

    exchange = TimeSyncExchange{
        response.sequence,
        response.t1_ns,
        *response.t2_ns,
        *response.t3_ns,
        t4_ns,
        receiver_elapsed - master_elapsed};
    pending_.erase(pending);
    return TimeSyncResponseStatus::Accepted;
}

bool TimeSyncClient::cancel_request(std::uint32_t sequence) {
    return pending_.erase(sequence) != 0U;
}

std::size_t TimeSyncClient::expire_before(Nanoseconds earliest_send_time_ns) {
    std::size_t expired = 0U;
    for (auto it = pending_.begin(); it != pending_.end();) {
        if (it->second < earliest_send_time_ns) {
            it = pending_.erase(it);
            ++expired;
        } else {
            ++it;
        }
    }
    return expired;
}

std::size_t TimeSyncClient::pending_count() const noexcept {
    return pending_.size();
}

TimeSyncResponder::TimeSyncResponder(std::uint32_t session_id, std::uint16_t receiver_id)
    : session_id_(session_id), receiver_id_(receiver_id) {}

TimeSyncRequestStatus TimeSyncResponder::make_response(
    const TimeSyncPacket& request,
    Nanoseconds t2_ns,
    Nanoseconds t3_ns,
    TimeSyncPacket& response) const {
    if (request.packet_type != 1U) {
        return TimeSyncRequestStatus::WrongPacketType;
    }
    if (request.version != 1U) {
        return TimeSyncRequestStatus::UnsupportedVersion;
    }
    if (request.session_id != session_id_) {
        return TimeSyncRequestStatus::WrongSession;
    }
    if (request.receiver_id != receiver_id_) {
        return TimeSyncRequestStatus::WrongReceiver;
    }
    Nanoseconds master_processing_ns = 0;
    if (t3_ns < t2_ns || !checked_subtract(t3_ns, t2_ns, master_processing_ns)) {
        return TimeSyncRequestStatus::InvalidTimestamps;
    }

    response = TimeSyncPacket{
        2U, 1U, session_id_, receiver_id_, request.sequence,
        request.t1_ns, t2_ns, t3_ns};
    return TimeSyncRequestStatus::Prepared;
}

}  // namespace syit
