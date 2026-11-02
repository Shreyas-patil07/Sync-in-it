// SPDX-License-Identifier: Apache-2.0
#include "syit/transport.h"

#include <cstdlib>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>

namespace {
int failures = 0;

void expect(bool condition, const std::string& message) {
    if (!condition) {
        ++failures;
        std::cerr << "FAIL: " << message << '\n';
    }
}

syit::Frame make_frame(std::uint8_t type, std::size_t payload_size) {
    return {type, 0U, std::vector<std::uint8_t>(payload_size, type)};
}

void test_bounded_frame_queue_capacity_and_fifo() {
    syit::BoundedFrameQueue queue(2U, 32U);
    const auto first = make_frame(1U, 4U);  // 10 wire bytes
    const auto second = make_frame(2U, 8U); // 14 wire bytes

    expect(queue.push(first) == syit::FrameQueuePushResult::Queued, "queue accepts first frame");
    expect(queue.push(second) == syit::FrameQueuePushResult::Queued, "queue accepts second frame");
    expect(queue.size() == 2U, "queue frame count is bounded");
    expect(queue.queued_bytes() == 24U, "queue accounts for complete wire bytes");
    expect(queue.push(make_frame(3U, 0U)) == syit::FrameQueuePushResult::QueueFull,
           "queue rejects newest frame when frame-count limit is reached");

    syit::Frame output;
    expect(queue.try_pop(output) && output.message_type == 1U, "queue preserves FIFO order");
    expect(queue.queued_bytes() == 14U, "pop releases queued byte budget");
    expect(queue.push(make_frame(3U, 4U)) == syit::FrameQueuePushResult::Queued,
           "queue accepts a frame after capacity is released");
    expect(queue.try_pop(output) && output.message_type == 2U, "second frame remains in FIFO order");
    expect(queue.try_pop(output) && output.message_type == 3U, "new frame follows queued frame");
    expect(!queue.try_pop(output), "empty queue returns no frame");
    expect(queue.queued_bytes() == 0U, "empty queue has zero queued bytes");
}

void test_bounded_frame_queue_byte_and_frame_limits() {
    syit::BoundedFrameQueue byte_limited(8U, 16U);
    expect(byte_limited.push(make_frame(1U, 10U)) == syit::FrameQueuePushResult::Queued,
           "byte-limited queue accepts exact budget");
    expect(byte_limited.push(make_frame(2U, 0U)) == syit::FrameQueuePushResult::QueueFull,
           "byte-limited queue rejects excess bytes");

    syit::BoundedFrameQueue frame_limited(1U, 1024U, 8U);
    expect(frame_limited.push(make_frame(1U, 7U)) == syit::FrameQueuePushResult::FrameTooLarge,
           "queue rejects a frame above its configured frame limit");
    expect(frame_limited.push(make_frame(1U, 6U)) == syit::FrameQueuePushResult::Queued,
           "queue accepts the configured maximum frame");
}

void test_bounded_frame_queue_close_drains_and_rejects() {
    syit::BoundedFrameQueue queue(2U, 128U);
    expect(queue.push(make_frame(1U, 1U)) == syit::FrameQueuePushResult::Queued,
           "queue accepts frame before close");
    queue.close();
    expect(queue.closed(), "queue exposes closed state");
    expect(queue.push(make_frame(2U, 1U)) == syit::FrameQueuePushResult::Closed,
           "closed queue rejects new frames");

    syit::Frame output;
    expect(queue.try_pop(output) && output.message_type == 1U,
           "closed queue allows already queued frames to drain");
    expect(!queue.try_pop(output), "closed drained queue is empty");
}

void test_time_sync_responder_identity_and_packet_creation() {
    syit::TimeSyncResponder responder(0x12345678U, 7U);
    syit::TimeSyncPacket request{1U, 1U, 0x12345678U, 7U, 99U, 1'000'000'000LL,
                                 std::nullopt, std::nullopt};
    syit::TimeSyncPacket response;
    expect(responder.make_response(request, 1'000'000'020LL, 1'000'000'025LL, response) ==
               syit::TimeSyncRequestStatus::Prepared,
           "responder creates response for expected receiver");
    expect(response.packet_type == 2U && response.sequence == request.sequence &&
           response.t1_ns == request.t1_ns && response.t2_ns == 1'000'000'020LL &&
           response.t3_ns == 1'000'000'025LL, "response echoes request and master timestamps");

    request.session_id += 1U;
    expect(responder.make_response(request, 1, 2, response) ==
               syit::TimeSyncRequestStatus::WrongSession,
           "responder rejects wrong session");
    request.session_id -= 1U;
    request.receiver_id += 1U;
    expect(responder.make_response(request, 1, 2, response) ==
               syit::TimeSyncRequestStatus::WrongReceiver,
           "responder rejects wrong receiver identity");
    request.receiver_id -= 1U;
    request.packet_type = 2U;
    expect(responder.make_response(request, 1, 2, response) ==
               syit::TimeSyncRequestStatus::WrongPacketType,
           "responder rejects non-request packet");
}

void test_time_sync_client_correlates_and_consumes_responses() {
    syit::TimeSyncClient client(0x12345678U, 7U, 2U,
                                std::numeric_limits<std::uint32_t>::max());
    syit::TimeSyncPacket request1, request2, request3;
    expect(client.make_request(1'000'000'000LL, request1), "client creates first request");
    expect(client.make_request(2'000'000'000LL, request2), "client creates second request");
    expect(request1.sequence == std::numeric_limits<std::uint32_t>::max() &&
           request2.sequence == 0U, "sequence counter wraps without collision");
    expect(!client.make_request(3'000'000'000LL, request3), "client enforces outstanding request limit");

    syit::TimeSyncPacket response{2U, 1U, request1.session_id, request1.receiver_id,
                                  request1.sequence, request1.t1_ns,
                                  1'000'000'020LL, 1'000'000'025LL};
    syit::TimeSyncExchange exchange;
    expect(client.accept_response(response, 1'000'000'050LL, exchange) ==
               syit::TimeSyncResponseStatus::Accepted,
           "client accepts matching time-sync response");
    expect(exchange.round_trip_ns == 45LL, "client subtracts master processing from RTT");
    expect(client.pending_count() == 1U, "accepted response removes pending request");
    const auto sample = exchange.to_sync_sample(1'000'000'050LL);
    expect(sample.receiver_send_ns == exchange.t1_ns &&
           sample.master_receive_ns == exchange.t2_ns &&
           sample.master_send_ns == exchange.t3_ns &&
           sample.receiver_receive_ns == exchange.t4_ns,
           "exchange converts to estimator SyncSample");
    expect(client.accept_response(response, 1'000'000'050LL, exchange) ==
               syit::TimeSyncResponseStatus::UnknownSequence,
           "duplicate response is rejected as replay/unknown sequence");
}

void test_time_sync_client_rejects_bad_responses_without_consuming_request() {
    syit::TimeSyncClient client(11U, 4U);
    syit::TimeSyncPacket request;
    expect(client.make_request(100LL, request), "client creates request for validation test");

    syit::TimeSyncPacket response{2U, 1U, 11U, 4U, request.sequence, 101LL, 120LL, 125LL};
    syit::TimeSyncExchange exchange;

    response.t1_ns = 99LL;
    expect(client.accept_response(response, 150LL, exchange) ==
               syit::TimeSyncResponseStatus::TimestampMismatch,
           "client rejects response with mismatched echoed t1");
    response.t1_ns = 100LL;

    response.session_id = 12U;
    expect(client.accept_response(response, 150LL, exchange) ==
               syit::TimeSyncResponseStatus::WrongSession,
           "client rejects response from wrong session");
    response.session_id = 11U;

    response.receiver_id = 5U;
    expect(client.accept_response(response, 150LL, exchange) ==
               syit::TimeSyncResponseStatus::WrongReceiver,
           "client rejects response for wrong receiver");
    response.receiver_id = 4U;

    response.version = 2U;
    expect(client.accept_response(response, 150LL, exchange) ==
               syit::TimeSyncResponseStatus::UnsupportedVersion,
           "client rejects unsupported response version");
    response.version = 1U;

    response.t3_ns = 119LL;
    expect(client.accept_response(response, 150LL, exchange) ==
               syit::TimeSyncResponseStatus::InvalidTimestamps,
           "client rejects master send before master receive");
    response.t3_ns = 125LL;

    expect(client.accept_response(response, 124LL, exchange) ==
               syit::TimeSyncResponseStatus::NegativeRoundTrip,
           "client rejects negative network RTT");
    expect(client.pending_count() == 1U, "invalid responses do not consume pending request");

    expect(client.accept_response(response, 150LL, exchange) ==
               syit::TimeSyncResponseStatus::Accepted,
           "valid response still accepted after malformed responses");
}

void test_time_sync_client_expiration_and_cancellation() {
    syit::TimeSyncClient client(1U, 1U, 3U);
    syit::TimeSyncPacket first, second, third;
    expect(client.make_request(10LL, first), "first expiring request created");
    expect(client.make_request(20LL, second), "second expiring request created");
    expect(client.make_request(30LL, third), "third expiring request created");
    expect(client.expire_before(20LL) == 1U, "expiration removes older request");
    expect(client.pending_count() == 2U, "expiration keeps requests at or after threshold");
    expect(client.cancel_request(second.sequence), "cancel removes pending request");
    expect(!client.cancel_request(second.sequence), "cancel of unknown request is false");
}

void test_time_sync_rejects_timestamp_overflow_and_invalid_rtt() {
    syit::TimeSyncClient client(1U, 1U);
    syit::TimeSyncPacket request;
    expect(client.make_request(std::numeric_limits<syit::Nanoseconds>::min(), request),
           "extreme timestamp request can be tracked");

    syit::TimeSyncPacket response{2U, 1U, 1U, 1U, request.sequence, request.t1_ns,
                                  std::numeric_limits<syit::Nanoseconds>::min(),
                                  std::numeric_limits<syit::Nanoseconds>::max()};
    syit::TimeSyncExchange exchange;
    expect(client.accept_response(response, std::numeric_limits<syit::Nanoseconds>::max(), exchange) ==
               syit::TimeSyncResponseStatus::InvalidTimestamps,
           "overflowing receiver interval is rejected");
}

}  // namespace

int main() {
    test_bounded_frame_queue_capacity_and_fifo();
    test_bounded_frame_queue_byte_and_frame_limits();
    test_bounded_frame_queue_close_drains_and_rejects();
    test_time_sync_responder_identity_and_packet_creation();
    test_time_sync_client_correlates_and_consumes_responses();
    test_time_sync_client_rejects_bad_responses_without_consuming_request();
    test_time_sync_client_expiration_and_cancellation();
    test_time_sync_rejects_timestamp_overflow_and_invalid_rtt();
    if (failures != 0) {
        std::cerr << failures << " transport assertion(s) failed\n";
        return EXIT_FAILURE;
    }
    std::cout << "C++ transport primitives: all checks passed\n";
    return EXIT_SUCCESS;
}
