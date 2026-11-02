// SPDX-License-Identifier: Apache-2.0
#pragma once

#include "syit/protocol.h"
#include "syit/types.h"

#include <cstddef>
#include <cstdint>
#include <deque>
#include <mutex>
#include <unordered_map>

namespace syit {

enum class FrameQueuePushResult {
    Queued,
    Closed,
    FrameTooLarge,
    QueueFull,
};

// A non-blocking, thread-safe FIFO for network-thread handoff.
// Use separate instances for control and audio traffic so audio cannot consume
// the control queue's budget. A full queue rejects the newest frame.
class BoundedFrameQueue {
public:
    BoundedFrameQueue(
        std::size_t max_frames,
        std::size_t max_queued_bytes,
        std::uint32_t max_frame_size = kMaxFrameSize);

    FrameQueuePushResult push(Frame frame);
    bool try_pop(Frame& frame);
    void close();

    bool closed() const;
    std::size_t size() const;
    std::size_t queued_bytes() const;

private:
    const std::size_t max_frames_;
    const std::size_t max_queued_bytes_;
    const std::uint32_t max_frame_size_;

    mutable std::mutex mutex_;
    std::deque<Frame> frames_;
    std::size_t queued_bytes_{0};
    bool closed_{false};
};

struct TimeSyncExchange {
    std::uint32_t sequence{};
    Nanoseconds t1_ns{};
    Nanoseconds t2_ns{};
    Nanoseconds t3_ns{};
    Nanoseconds t4_ns{};
    Nanoseconds round_trip_ns{};

    SyncSample to_sync_sample(Nanoseconds received_at_true_ns) const noexcept;
};

enum class TimeSyncResponseStatus {
    Accepted,
    WrongPacketType,
    UnsupportedVersion,
    WrongSession,
    WrongReceiver,
    UnknownSequence,
    TimestampMismatch,
    InvalidTimestamps,
    NegativeRoundTrip,
};

// Correlates UDP time-sync responses with outstanding requests. The caller
// supplies timestamps taken immediately before send and on receive.
class TimeSyncClient {
public:
    TimeSyncClient(
        std::uint32_t session_id,
        std::uint16_t receiver_id,
        std::size_t max_pending = 32,
        std::uint32_t initial_sequence = 0);

    bool make_request(Nanoseconds t1_ns, TimeSyncPacket& request);
    TimeSyncResponseStatus accept_response(
        const TimeSyncPacket& response,
        Nanoseconds t4_ns,
        TimeSyncExchange& exchange);

    bool cancel_request(std::uint32_t sequence);
    std::size_t expire_before(Nanoseconds earliest_send_time_ns);
    std::size_t pending_count() const noexcept;

private:
    const std::uint32_t session_id_;
    const std::uint16_t receiver_id_;
    const std::size_t max_pending_;

    std::uint32_t next_sequence_;
    std::unordered_map<std::uint32_t, Nanoseconds> pending_;
};

// Builds a response only for a request bound to the authenticated receiver
// connection's session and receiver identity.
enum class TimeSyncRequestStatus {
    Prepared,
    WrongPacketType,
    UnsupportedVersion,
    WrongSession,
    WrongReceiver,
    InvalidTimestamps,
};

class TimeSyncResponder {
public:
    TimeSyncResponder(std::uint32_t session_id, std::uint16_t receiver_id);

    TimeSyncRequestStatus make_response(
        const TimeSyncPacket& request,
        Nanoseconds t2_ns,
        Nanoseconds t3_ns,
        TimeSyncPacket& response) const;

private:
    const std::uint32_t session_id_;
    const std::uint16_t receiver_id_;
};

}  // namespace syit
