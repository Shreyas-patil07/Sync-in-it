// SPDX-License-Identifier: Apache-2.0
#include <jni.h>

#include "syit/audio.h"
#include "syit/estimator.h"
#include "syit/messages.h"
#include "syit/protocol.h"
#include "syit/session.h"
#include "syit/transport.h"
#include "syit/timing.h"

#include <algorithm>
#include <array>
#include <climits>
#include <cmath>
#include <limits>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

struct NativeSession {
    explicit NativeSession(syit::ReceiverSessionConfig config)
        : time_sync_responder(config.session_id, config.receiver_id),
          session(std::move(config)) {}

    syit::TimeSyncResponder time_sync_responder;
    std::mutex mutex;
    syit::ReceiverSession session;
};


struct NativePcmBuffer {
    NativePcmBuffer(std::size_t capacity_frames, std::uint8_t channels, std::uint32_t session_id)
        : session_id(session_id), channels(channels), buffer(capacity_frames, channels) {}

    const std::uint32_t session_id;
    const std::uint8_t channels;
    std::mutex initialization_mutex;
    bool initialized{false};
    bool playback_started{false};
    syit::PcmJitterBuffer buffer;
};

struct NativeDacClock {
    explicit NativeDacClock(std::int64_t sample_rate_hz)
        : mapper(sample_rate_hz) {}

    std::mutex mutex;
    syit::DacClockMapper mapper;
};

struct NativeTimeSyncClient {
    NativeTimeSyncClient(std::uint32_t session_id, std::uint16_t receiver_id)
        : client(session_id, receiver_id) {}

    std::mutex mutex;
    syit::TimeSyncClient client;
    std::vector<syit::SyncSample> samples;
    std::size_t stable_streak{0U};
};

std::mutex g_registry_mutex;
std::unordered_map<jlong, std::shared_ptr<NativeSession>> g_sessions;
std::atomic<jlong> g_next_handle{1};
std::mutex g_clock_clients_mutex;
std::unordered_map<jlong, std::shared_ptr<NativeTimeSyncClient>> g_clock_clients;
std::atomic<jlong> g_next_clock_client_handle{1};
std::mutex g_pcm_buffers_mutex;
std::unordered_map<jlong, std::shared_ptr<NativePcmBuffer>> g_pcm_buffers;
std::atomic<jlong> g_next_pcm_buffer_handle{1};
std::mutex g_dac_clocks_mutex;
std::unordered_map<jlong, std::shared_ptr<NativeDacClock>> g_dac_clocks;
std::atomic<jlong> g_next_dac_clock_handle{1};

std::shared_ptr<NativePcmBuffer> find_pcm_buffer(jlong handle) {
    if (handle <= 0) return {};
    std::lock_guard<std::mutex> lock(g_pcm_buffers_mutex);
    const auto found = g_pcm_buffers.find(handle);
    return found == g_pcm_buffers.end() ? std::shared_ptr<NativePcmBuffer>{} : found->second;
}

std::shared_ptr<NativeDacClock> find_dac_clock(jlong handle) {
    if (handle <= 0) return {};
    std::lock_guard<std::mutex> lock(g_dac_clocks_mutex);
    const auto found = g_dac_clocks.find(handle);
    return found == g_dac_clocks.end() ? std::shared_ptr<NativeDacClock>{} : found->second;
}

void throw_illegal_argument(JNIEnv* env, const char* message) {
    jclass exception_class = env->FindClass("java/lang/IllegalArgumentException");
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message);
        env->DeleteLocalRef(exception_class);
    }
}

std::shared_ptr<NativeSession> find_session(jlong handle) {
    if (handle <= 0) return {};
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    const auto found = g_sessions.find(handle);
    return found == g_sessions.end() ? std::shared_ptr<NativeSession>{} : found->second;
}

std::shared_ptr<NativeTimeSyncClient> find_clock_client(jlong handle) {
    if (handle <= 0) return {};
    std::lock_guard<std::mutex> lock(g_clock_clients_mutex);
    const auto found = g_clock_clients.find(handle);
    return found == g_clock_clients.end() ? std::shared_ptr<NativeTimeSyncClient>{} : found->second;
}

bool copy_token(JNIEnv* env, jbyteArray token_array, std::array<std::uint8_t, 16>& token) {
    if (token_array == nullptr || env->GetArrayLength(token_array) != 16) {
        throw_illegal_argument(env, "session token must contain exactly 16 bytes");
        return false;
    }
    std::array<jbyte, 16> bytes{};
    env->GetByteArrayRegion(token_array, 0, 16, bytes.data());
    if (env->ExceptionCheck()) return false;
    for (std::size_t i = 0; i < token.size(); ++i) {
        token[i] = static_cast<std::uint8_t>(bytes[i]);
    }
    return true;
}

jint result_code(syit::ReceiverSessionResult result) {
    return static_cast<jint>(result);
}

jint state_code(syit::ReceiverSessionState state) {
    return static_cast<jint>(state);
}

bool copy_wire_frame(JNIEnv* env, jbyteArray wire_array, std::vector<std::uint8_t>& wire) {
    if (wire_array == nullptr) {
        throw_illegal_argument(env, "encoded frame must not be null");
        return false;
    }
    const jsize length = env->GetArrayLength(wire_array);
    if (length < 6 || static_cast<std::size_t>(length) > syit::kMaxFrameSize + 4U) {
        throw_illegal_argument(env, "encoded frame length is outside the protocol bounds");
        return false;
    }
    std::vector<jbyte> bytes(static_cast<std::size_t>(length));
    env->GetByteArrayRegion(wire_array, 0, length, bytes.data());
    if (env->ExceptionCheck()) return false;
    wire.resize(bytes.size());
    for (std::size_t i = 0; i < bytes.size(); ++i) {
        wire[i] = static_cast<std::uint8_t>(bytes[i]);
    }
    return true;
}

jbyteArray make_byte_array(JNIEnv* env, const std::vector<std::uint8_t>& bytes) {
    if (bytes.size() > static_cast<std::size_t>(INT32_MAX)) {
        throw_illegal_argument(env, "encoded frame exceeds JNI array limit");
        return nullptr;
    }
    jbyteArray output = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (output == nullptr) return nullptr;
    if (!bytes.empty()) {
        env->SetByteArrayRegion(
            output, 0, static_cast<jsize>(bytes.size()),
            reinterpret_cast<const jbyte*>(bytes.data()));
    }
    return output;
}

bool copy_binary_array(
    JNIEnv* env,
    jbyteArray array,
    std::vector<std::uint8_t>& bytes,
    std::size_t expected_size) {
    if (array == nullptr) return false;
    const jsize length = env->GetArrayLength(array);
    if (length < 0 || static_cast<std::size_t>(length) != expected_size) return false;
    std::vector<jbyte> raw(expected_size);
    if (expected_size != 0U) {
        env->GetByteArrayRegion(array, 0, length, raw.data());
        if (env->ExceptionCheck()) return false;
    }
    bytes.resize(expected_size);
    for (std::size_t i = 0; i < expected_size; ++i) {
        bytes[i] = static_cast<std::uint8_t>(raw[i]);
    }
    return true;
}

jdoubleArray make_double_array(JNIEnv* env, const std::array<jdouble, 8>& values) {
    jdoubleArray output = env->NewDoubleArray(static_cast<jsize>(values.size()));
    if (output == nullptr) return nullptr;
    env->SetDoubleArrayRegion(output, 0, static_cast<jsize>(values.size()), values.data());
    return output;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_syncinit_app_core_NativeCore_nativeCoreVersion(JNIEnv*, jobject) {
    return 1;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_syncinit_app_core_NativeCore_nativeEncodeHelloFrame(
    JNIEnv* env, jobject, jbyteArray token_array, jint platform) {
    if (platform < 0 || platform > 255) {
        throw_illegal_argument(env, "HELLO platform must fit in one byte");
        return nullptr;
    }
    syit::Hello hello;
    hello.proto_version = 1U;
    hello.platform = static_cast<std::uint8_t>(platform);
    hello.app_version = 1U;
    hello.model = "Android";
    if (!copy_token(env, token_array, hello.token)) return nullptr;
    try {
        const auto frame = syit::encode_control_message(syit::ControlMessage{hello});
        return make_byte_array(env, syit::encode_frame(frame));
    } catch (const std::exception& error) {
        throw_illegal_argument(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_syncinit_app_core_NativeCore_nativeDecodeWelcomeFrame(
    JNIEnv* env, jobject, jbyteArray wire_array) {
    std::vector<std::uint8_t> wire;
    if (!copy_wire_frame(env, wire_array, wire)) return nullptr;
    try {
        const auto frame = syit::decode_frame(wire);
        const auto message = syit::decode_control_message(frame);
        const auto* welcome = std::get_if<syit::Welcome>(&message);
        if (welcome == nullptr) {
            throw std::invalid_argument("expected a WELCOME control frame");
        }
        const std::array<jlong, 10> values{{
            static_cast<jlong>(welcome->result),
            static_cast<jlong>(welcome->receiver_id),
            static_cast<jlong>(welcome->session_id),
            static_cast<jlong>(welcome->sample_rate_hz),
            static_cast<jlong>(welcome->channels),
            static_cast<jlong>(welcome->chunk_frames),
            static_cast<jlong>(welcome->codec),
            static_cast<jlong>(welcome->audio_port),
            static_cast<jlong>(welcome->time_port),
            static_cast<jlong>(welcome->startup_lead_ms)
        }};
        jlongArray output = env->NewLongArray(static_cast<jsize>(values.size()));
        if (output == nullptr) return nullptr;
        env->SetLongArrayRegion(output, 0, static_cast<jsize>(values.size()), values.data());
        return output;
    } catch (const std::exception& error) {
        throw_illegal_argument(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_syncinit_app_core_NativeCore_nativeEncodeAudioHelloFrame(
    JNIEnv* env, jobject, jlong session_id, jint receiver_id) {
    if (session_id < 0 || static_cast<std::uint64_t>(session_id) > UINT32_MAX ||
        receiver_id < 0 || receiver_id > 65'535) {
        throw_illegal_argument(env, "AUDIO_HELLO identity contains an out-of-range value");
        return nullptr;
    }
    try {
        const syit::AudioHello hello{
            static_cast<std::uint32_t>(session_id),
            static_cast<std::uint16_t>(receiver_id)};
        const auto frame = syit::encode_control_message(syit::ControlMessage{hello});
        return make_byte_array(env, syit::encode_frame(frame));
    } catch (const std::exception& error) {
        throw_illegal_argument(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_syncinit_app_core_NativeCore_nativeOnHelloFrame(
    JNIEnv* env, jobject, jlong handle, jbyteArray wire_array) {
    auto native_session = find_session(handle);
    if (!native_session) return result_code(syit::ReceiverSessionResult::Closed);
    std::vector<std::uint8_t> wire;
    if (!copy_wire_frame(env, wire_array, wire)) {
        return result_code(syit::ReceiverSessionResult::WrongState);
    }
    try {
        const auto frame = syit::decode_frame(wire);
        if (frame.flags != 0U) throw std::invalid_argument("HELLO frame flags must be zero");
        const auto message = syit::decode_control_message(frame);
        const auto* hello = std::get_if<syit::Hello>(&message);
        if (hello == nullptr) throw std::invalid_argument("expected a HELLO control frame");
        std::lock_guard<std::mutex> lock(native_session->mutex);
        return result_code(native_session->session.on_hello(*hello));
    } catch (const std::exception& error) {
        throw_illegal_argument(env, error.what());
        return result_code(syit::ReceiverSessionResult::WrongState);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_syncinit_app_core_NativeCore_nativeOnAudioHelloFrame(
    JNIEnv* env, jobject, jlong handle, jbyteArray wire_array) {
    auto native_session = find_session(handle);
    if (!native_session) return result_code(syit::ReceiverSessionResult::Closed);
    std::vector<std::uint8_t> wire;
    if (!copy_wire_frame(env, wire_array, wire)) {
        return result_code(syit::ReceiverSessionResult::WrongState);
    }
    try {
        const auto frame = syit::decode_frame(wire);
        if (frame.flags != 0U) throw std::invalid_argument("AUDIO_HELLO frame flags must be zero");
        const auto message = syit::decode_control_message(frame);
        const auto* hello = std::get_if<syit::AudioHello>(&message);
        if (hello == nullptr) throw std::invalid_argument("expected an AUDIO_HELLO control frame");
        std::lock_guard<std::mutex> lock(native_session->mutex);
        return result_code(native_session->session.on_audio_hello(*hello));
    } catch (const std::exception& error) {
        throw_illegal_argument(env, error.what());
        return result_code(syit::ReceiverSessionResult::WrongState);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_syncinit_app_core_NativeCore_nativeCreateSession(
    JNIEnv* env,
    jobject,
    jlong session_id,
    jint receiver_id,
    jbyteArray token_array,
    jlong minimum_buffered_ms,
    jlong maximum_residual_std_us,
    jint audio_port,
    jint time_port,
    jint startup_lead_ms) {
    if (session_id < 0 || static_cast<std::uint64_t>(session_id) > UINT32_MAX ||
        receiver_id < 0 || receiver_id > 65'535 ||
        minimum_buffered_ms < 0 || static_cast<std::uint64_t>(minimum_buffered_ms) > UINT32_MAX ||
        maximum_residual_std_us < 0 || static_cast<std::uint64_t>(maximum_residual_std_us) > UINT32_MAX ||
        audio_port < 1 || audio_port > 65'535 ||
        time_port < 1 || time_port > 65'535 ||
        startup_lead_ms < 0 || startup_lead_ms > 65'535) {
        throw_illegal_argument(env, "receiver session configuration contains an out-of-range value");
        return 0;
    }

    syit::ReceiverSessionConfig config;
    if (!copy_token(env, token_array, config.token)) return 0;

    config.session_id = static_cast<std::uint32_t>(session_id);
    config.receiver_id = static_cast<std::uint16_t>(receiver_id);
    config.minimum_buffered_ms = static_cast<std::uint32_t>(minimum_buffered_ms);
    config.maximum_residual_std_us = static_cast<std::uint32_t>(maximum_residual_std_us);
    config.supported_platforms = {1U, 2U};
    config.welcome.sample_rate_hz = 48'000U;
    config.welcome.channels = 2U;
    config.welcome.chunk_frames = 960U;
    config.welcome.codec = 0U;
    config.welcome.audio_port = static_cast<std::uint16_t>(audio_port);
    config.welcome.time_port = static_cast<std::uint16_t>(time_port);
    config.welcome.startup_lead_ms = static_cast<std::uint16_t>(startup_lead_ms);

    try {
        auto session = std::make_shared<NativeSession>(std::move(config));
        const jlong handle = g_next_handle.fetch_add(1);
        if (handle <= 0) {
            throw std::overflow_error("native receiver session handle space exhausted");
        }
        std::lock_guard<std::mutex> lock(g_registry_mutex);
        g_sessions.emplace(handle, std::move(session));
        return handle;
    } catch (const std::exception& error) {
        throw_illegal_argument(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_syncinit_app_core_NativeCore_nativeOnHello(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint protocol_version,
    jbyteArray token_array,
    jint platform) {
    auto native_session = find_session(handle);
    if (!native_session) return result_code(syit::ReceiverSessionResult::Closed);
    if (protocol_version < 0 || protocol_version > 65'535 || platform < 0 || platform > 255) {
        throw_illegal_argument(env, "HELLO version or platform is outside the wire range");
        return result_code(syit::ReceiverSessionResult::WrongState);
    }

    syit::Hello hello;
    hello.proto_version = static_cast<std::uint16_t>(protocol_version);
    hello.platform = static_cast<std::uint8_t>(platform);
    if (!copy_token(env, token_array, hello.token)) return result_code(syit::ReceiverSessionResult::WrongState);

    std::lock_guard<std::mutex> lock(native_session->mutex);
    return result_code(native_session->session.on_hello(hello));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_syncinit_app_core_NativeCore_nativeWelcomeFrame(JNIEnv* env, jobject, jlong handle) {
    auto native_session = find_session(handle);
    if (!native_session) return nullptr;

    std::vector<std::uint8_t> wire;
    {
        std::lock_guard<std::mutex> lock(native_session->mutex);
        const auto welcome = native_session->session.welcome_message();
        if (!welcome.has_value()) return nullptr;
        wire = syit::encode_frame(
            syit::encode_control_message(syit::ControlMessage{*welcome}));
    }

    if (wire.size() > static_cast<std::size_t>(INT32_MAX)) {
        throw_illegal_argument(env, "WELCOME frame exceeds JNI array limit");
        return nullptr;
    }
    jbyteArray output = env->NewByteArray(static_cast<jsize>(wire.size()));
    if (output == nullptr) return nullptr;
    env->SetByteArrayRegion(
        output, 0, static_cast<jsize>(wire.size()),
        reinterpret_cast<const jbyte*>(wire.data()));
    return output;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_syncinit_app_core_NativeCore_nativeOnAudioHello(
    JNIEnv*, jobject, jlong handle, jlong session_id, jint receiver_id) {
    auto native_session = find_session(handle);
    if (!native_session) return result_code(syit::ReceiverSessionResult::Closed);
    if (session_id < 0 || static_cast<std::uint64_t>(session_id) > UINT32_MAX ||
        receiver_id < 0 || receiver_id > 65'535) {
        return result_code(syit::ReceiverSessionResult::WrongReceiver);
    }
    std::lock_guard<std::mutex> lock(native_session->mutex);
    return result_code(native_session->session.on_audio_hello({
        static_cast<std::uint32_t>(session_id),
        static_cast<std::uint16_t>(receiver_id)}));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_syncinit_app_core_NativeCore_nativeOnReady(
    JNIEnv*, jobject, jlong handle, jlong buffered_ms, jboolean sync_stable, jlong residual_std_us) {
    auto native_session = find_session(handle);
    if (!native_session) return result_code(syit::ReceiverSessionResult::Closed);
    if (buffered_ms < 0 || static_cast<std::uint64_t>(buffered_ms) > UINT32_MAX ||
        residual_std_us < 0 || static_cast<std::uint64_t>(residual_std_us) > UINT32_MAX) {
        return result_code(syit::ReceiverSessionResult::BufferInsufficient);
    }
    std::lock_guard<std::mutex> lock(native_session->mutex);
    return result_code(native_session->session.on_ready({
        static_cast<std::uint32_t>(buffered_ms),
        sync_stable == JNI_TRUE,
        static_cast<std::uint32_t>(residual_std_us)}));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_syncinit_app_core_NativeCore_nativeOnReadyFrame(
    JNIEnv* env, jobject, jlong handle, jbyteArray wire_array) {
    auto native_session = find_session(handle);
    if (!native_session) return result_code(syit::ReceiverSessionResult::Closed);
    std::vector<std::uint8_t> wire;
    if (!copy_wire_frame(env, wire_array, wire)) {
        return result_code(syit::ReceiverSessionResult::WrongState);
    }
    try {
        const auto frame = syit::decode_frame(wire);
        if (frame.flags != 0U) throw std::invalid_argument("READY frame flags must be zero");
        const auto message = syit::decode_control_message(frame);
        const auto* ready = std::get_if<syit::Ready>(&message);
        if (ready == nullptr) throw std::invalid_argument("expected a READY control frame");
        std::lock_guard<std::mutex> lock(native_session->mutex);
        return result_code(native_session->session.on_ready(*ready));
    } catch (const std::exception& error) {
        throw_illegal_argument(env, error.what());
        return result_code(syit::ReceiverSessionResult::WrongState);
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_syncinit_app_core_NativeCore_nativeEncodeReadyFrame(
    JNIEnv* env, jobject, jlong buffered_ms, jboolean sync_stable, jlong residual_std_us) {
    if (buffered_ms < 0 || static_cast<std::uint64_t>(buffered_ms) > UINT32_MAX ||
        residual_std_us < 0 || static_cast<std::uint64_t>(residual_std_us) > UINT32_MAX) {
        throw_illegal_argument(env, "READY metrics are outside the wire range");
        return nullptr;
    }
    try {
        const syit::Ready ready{
            static_cast<std::uint32_t>(buffered_ms),
            sync_stable == JNI_TRUE,
            static_cast<std::uint32_t>(residual_std_us)};
        const auto frame = syit::encode_control_message(syit::ControlMessage{ready});
        return make_byte_array(env, syit::encode_frame(frame));
    } catch (const std::exception& error) {
        throw_illegal_argument(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_syncinit_app_core_NativeCore_nativeTimeSyncResponseFrame(
    JNIEnv* env,
    jobject,
    jlong handle,
    jbyteArray packet_array,
    jlong t2_ns,
    jlong t3_ns) {
    auto native_session = find_session(handle);
    if (!native_session || t3_ns < t2_ns) return nullptr;
    std::vector<std::uint8_t> bytes;
    if (!copy_binary_array(env, packet_array, bytes, syit::kTimeSyncPacketSize)) return nullptr;
    try {
        const auto request = syit::decode_time_sync_packet(bytes);
        syit::TimeSyncPacket response;
        const auto result = native_session->time_sync_responder.make_response(
            request, static_cast<syit::Nanoseconds>(t2_ns),
            static_cast<syit::Nanoseconds>(t3_ns), response);
        if (result != syit::TimeSyncRequestStatus::Prepared) return nullptr;
        return make_byte_array(env, syit::encode_time_sync_response(response));
    } catch (const std::exception&) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_syncinit_app_core_NativeCore_nativeCreateTimeSyncClient(
    JNIEnv* env, jobject, jlong session_id, jint receiver_id) {
    if (session_id < 0 || static_cast<std::uint64_t>(session_id) > UINT32_MAX ||
        receiver_id < 0 || receiver_id > 65'535) {
        throw_illegal_argument(env, "time-sync client identity contains an out-of-range value");
        return 0;
    }
    try {
        auto client = std::make_shared<NativeTimeSyncClient>(
            static_cast<std::uint32_t>(session_id), static_cast<std::uint16_t>(receiver_id));
        const jlong handle = g_next_clock_client_handle.fetch_add(1);
        if (handle <= 0) throw std::overflow_error("time-sync client handle space exhausted");
        std::lock_guard<std::mutex> lock(g_clock_clients_mutex);
        g_clock_clients.emplace(handle, std::move(client));
        return handle;
    } catch (const std::exception& error) {
        throw_illegal_argument(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_syncinit_app_core_NativeCore_nativeMakeTimeSyncRequest(
    JNIEnv* env, jobject, jlong handle, jlong t1_ns) {
    auto native_client = find_clock_client(handle);
    if (!native_client) return nullptr;
    std::lock_guard<std::mutex> lock(native_client->mutex);
    syit::TimeSyncPacket request;
    if (!native_client->client.make_request(static_cast<syit::Nanoseconds>(t1_ns), request)) {
        return nullptr;
    }
    try {
        return make_byte_array(env, syit::encode_time_sync_request(request));
    } catch (const std::exception&) {
        native_client->client.cancel_request(request.sequence);
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_syncinit_app_core_NativeCore_nativeExpireTimeSyncRequests(
    JNIEnv*, jobject, jlong handle, jlong earlier_than_ns) {
    auto native_client = find_clock_client(handle);
    if (!native_client) return;
    std::lock_guard<std::mutex> lock(native_client->mutex);
    native_client->client.expire_before(static_cast<syit::Nanoseconds>(earlier_than_ns));
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_syncinit_app_core_NativeCore_nativeAcceptTimeSyncResponse(
    JNIEnv* env, jobject, jlong handle, jbyteArray packet_array, jlong t4_ns) {
    auto native_client = find_clock_client(handle);
    if (!native_client) return nullptr;
    std::vector<std::uint8_t> bytes;
    if (!copy_binary_array(env, packet_array, bytes, syit::kTimeSyncPacketSize)) return nullptr;

    try {
        const auto response = syit::decode_time_sync_packet(bytes);
        std::lock_guard<std::mutex> lock(native_client->mutex);
        syit::TimeSyncExchange exchange;
        if (native_client->client.accept_response(
                response, static_cast<syit::Nanoseconds>(t4_ns), exchange) !=
            syit::TimeSyncResponseStatus::Accepted) {
            return nullptr;
        }

        native_client->samples.push_back(exchange.to_sync_sample(static_cast<syit::Nanoseconds>(t4_ns)));
        constexpr syit::Nanoseconds kEstimatorWindowNs = 60'000'000'000LL;
        native_client->samples.erase(
            std::remove_if(
                native_client->samples.begin(), native_client->samples.end(),
                [t4_ns](const syit::SyncSample& sample) {
                    return sample.received_at_true_ns > t4_ns ||
                        static_cast<long double>(t4_ns) - sample.received_at_true_ns >
                            static_cast<long double>(kEstimatorWindowNs);
                }),
            native_client->samples.end());

        const auto fit = syit::estimate_clock_offset(
            native_client->samples,
            static_cast<syit::Nanoseconds>(t4_ns),
            static_cast<syit::Nanoseconds>(t4_ns));
        const bool stable_fit =
            fit.receiver_skew_ppm.has_value() &&
            fit.samples_used >= 8U &&
            fit.selected_sample_span_ns >= 10'000'000'000LL &&
            fit.residual_rms_ms < 0.25;
        native_client->stable_streak = stable_fit
            ? std::min<std::size_t>(native_client->stable_streak + 1U, 3U)
            : 0U;

        const auto skew = fit.receiver_skew_ppm.value_or(
            std::numeric_limits<double>::quiet_NaN());
        return make_double_array(env, {{
            fit.offset_ns,
            skew,
            fit.rtt_p50_ms,
            fit.residual_rms_ms,
            static_cast<jdouble>(fit.samples_used),
            static_cast<jdouble>(fit.samples_seen),
            static_cast<jdouble>(fit.selected_sample_span_ns),
            static_cast<jdouble>(native_client->stable_streak)
        }});
    } catch (const std::exception&) {
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_syncinit_app_core_NativeCore_nativeCloseTimeSyncClient(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(g_clock_clients_mutex);
    g_clock_clients.erase(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_syncinit_app_core_NativeCore_nativeArm(JNIEnv*, jobject, jlong handle) {
    auto native_session = find_session(handle);
    if (!native_session) return result_code(syit::ReceiverSessionResult::Closed);
    std::lock_guard<std::mutex> lock(native_session->mutex);
    return result_code(native_session->session.arm());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_syncinit_app_core_NativeCore_nativeOnAnchor(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint kind,
    jlong master_time_ns,
    jlong sample_index,
    jboolean playing,
    jint crossfade_ms) {
    auto native_session = find_session(handle);
    if (!native_session) return result_code(syit::ReceiverSessionResult::Closed);
    if (kind < 0 || kind > 255 || crossfade_ms < 0 || crossfade_ms > 65'535) {
        throw_illegal_argument(env, "ANCHOR kind or crossfade is outside the wire range");
        return result_code(syit::ReceiverSessionResult::InvalidAnchor);
    }
    std::lock_guard<std::mutex> lock(native_session->mutex);
    return result_code(native_session->session.on_anchor({
        static_cast<std::uint8_t>(kind),
        static_cast<std::int64_t>(master_time_ns),
        static_cast<std::int64_t>(sample_index),
        playing == JNI_TRUE,
        static_cast<std::uint16_t>(crossfade_ms)}));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_syncinit_app_core_NativeCore_nativeMarkDegraded(JNIEnv*, jobject, jlong handle) {
    auto native_session = find_session(handle);
    if (!native_session) return result_code(syit::ReceiverSessionResult::Closed);
    std::lock_guard<std::mutex> lock(native_session->mutex);
    return result_code(native_session->session.mark_degraded());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_syncinit_app_core_NativeCore_nativeBeginResynchronization(JNIEnv*, jobject, jlong handle) {
    auto native_session = find_session(handle);
    if (!native_session) return result_code(syit::ReceiverSessionResult::Closed);
    std::lock_guard<std::mutex> lock(native_session->mutex);
    return result_code(native_session->session.begin_resynchronization());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_syncinit_app_core_NativeCore_nativeState(JNIEnv*, jobject, jlong handle) {
    auto native_session = find_session(handle);
    if (!native_session) return state_code(syit::ReceiverSessionState::Closed);
    std::lock_guard<std::mutex> lock(native_session->mutex);
    return state_code(native_session->session.state());
}

extern "C" JNIEXPORT void JNICALL
Java_com_syncinit_app_core_NativeCore_nativeCloseSession(JNIEnv*, jobject, jlong handle) {
    std::shared_ptr<NativeSession> native_session;
    {
        std::lock_guard<std::mutex> lock(g_registry_mutex);
        const auto found = g_sessions.find(handle);
        if (found == g_sessions.end()) return;
        native_session = found->second;
        g_sessions.erase(found);
    }
    std::lock_guard<std::mutex> lock(native_session->mutex);
    native_session->session.close();
}


extern "C" JNIEXPORT jlong JNICALL
Java_com_syncinit_app_core_NativeCore_nativeCreatePcmBuffer(
    JNIEnv* env, jobject, jlong capacity_frames, jint channels, jlong session_id) {
    if (capacity_frames <= 0 || capacity_frames > 960'000 ||
        channels < 1 || channels > 2 ||
        session_id < 0 || static_cast<std::uint64_t>(session_id) > UINT32_MAX) {
        throw_illegal_argument(env, "PCM jitter buffer configuration is outside the supported range");
        return 0;
    }
    try {
        auto buffer = std::make_shared<NativePcmBuffer>(
            static_cast<std::size_t>(capacity_frames),
            static_cast<std::uint8_t>(channels),
            static_cast<std::uint32_t>(session_id));
        const jlong handle = g_next_pcm_buffer_handle.fetch_add(1);
        if (handle <= 0) throw std::overflow_error("PCM buffer handle space exhausted");
        std::lock_guard<std::mutex> lock(g_pcm_buffers_mutex);
        g_pcm_buffers.emplace(handle, std::move(buffer));
        return handle;
    } catch (const std::exception& error) {
        throw_illegal_argument(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_syncinit_app_core_NativeCore_nativeInsertPcmFrame(
    JNIEnv* env, jobject, jlong handle, jbyteArray wire_array) {
    auto native_buffer = find_pcm_buffer(handle);
    if (!native_buffer) return static_cast<jint>(syit::JitterInsertResult::InvalidChunk);

    std::vector<std::uint8_t> wire;
    if (!copy_wire_frame(env, wire_array, wire)) {
        return static_cast<jint>(syit::JitterInsertResult::InvalidChunk);
    }

    try {
        const auto frame = syit::decode_frame(wire);
        const auto chunk = syit::decode_audio_chunk(frame);
        if (chunk.session_id != native_buffer->session_id ||
            chunk.channels != native_buffer->channels) {
            return static_cast<jint>(syit::JitterInsertResult::InvalidChunk);
        }

        std::lock_guard<std::mutex> lock(native_buffer->initialization_mutex);
        if (!native_buffer->initialized) {
            native_buffer->buffer.reset(chunk.sample_index);
            native_buffer->initialized = true;
        }
        // Before the first playback ANCHOR, retain a rolling bounded pre-roll
        // window. Once seek() applies the ANCHOR, inserts become strict again.
        return static_cast<jint>(
            native_buffer->buffer.insert(chunk, !native_buffer->playback_started));
    } catch (const std::exception&) {
        return static_cast<jint>(syit::JitterInsertResult::InvalidChunk);
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_syncinit_app_core_NativeCore_nativePcmBufferStats(
    JNIEnv* env, jobject, jlong handle) {
    auto native_buffer = find_pcm_buffer(handle);
    if (!native_buffer) return nullptr;

    jlong contiguous = 0;
    jlong next_sample_index = 0;
    jlong missing_frames = 0;
    jlong window_advances = 0;
    {
        std::lock_guard<std::mutex> lock(native_buffer->initialization_mutex);
        if (native_buffer->initialized) {
            contiguous = static_cast<jlong>(native_buffer->buffer.contiguous_buffered_frames());
            next_sample_index = static_cast<jlong>(native_buffer->buffer.next_sample_index());
            missing_frames = static_cast<jlong>(native_buffer->buffer.missing_frames_total());
            window_advances = static_cast<jlong>(native_buffer->buffer.window_advance_count());
        }
    }

    const std::array<jlong, 4> values{{contiguous, next_sample_index, missing_frames, window_advances}};
    jlongArray output = env->NewLongArray(static_cast<jsize>(values.size()));
    if (output == nullptr) return nullptr;
    env->SetLongArrayRegion(output, 0, static_cast<jsize>(values.size()), values.data());
    return output;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_syncinit_app_core_NativeCore_nativeReadPcmBuffer(
    JNIEnv* env, jobject, jlong handle, jshortArray output_array) {
    auto native_buffer = find_pcm_buffer(handle);
    if (!native_buffer) {
        throw_illegal_argument(env, "PCM jitter buffer handle is closed");
        return 0;
    }
    if (output_array == nullptr) {
        throw_illegal_argument(env, "PCM output array must not be null");
        return 0;
    }

    const jsize output_samples = env->GetArrayLength(output_array);
    if (output_samples <= 0 || output_samples > 16'384 ||
        output_samples % static_cast<jsize>(native_buffer->channels) != 0) {
        throw_illegal_argument(env, "PCM output array must contain a bounded number of complete frames");
        return 0;
    }

    {
        std::lock_guard<std::mutex> lock(native_buffer->initialization_mutex);
        if (!native_buffer->initialized) {
            throw_illegal_argument(env, "PCM jitter buffer has not received its first audio chunk");
            return 0;
        }
    }

    const auto frames = static_cast<std::size_t>(
        output_samples / static_cast<jsize>(native_buffer->channels));
    std::vector<std::int16_t> samples(static_cast<std::size_t>(output_samples), 0);
    const auto next = native_buffer->buffer.next_sample_index();
    const auto result = native_buffer->buffer.read(
        next, frames, samples.data(), samples.size());
    env->SetShortArrayRegion(
        output_array, 0, output_samples,
        reinterpret_cast<const jshort*>(samples.data()));
    if (env->ExceptionCheck()) return 0;
    return static_cast<jlong>(result.missing_frames);
}

extern "C" JNIEXPORT void JNICALL
Java_com_syncinit_app_core_NativeCore_nativeClosePcmBuffer(
    JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(g_pcm_buffers_mutex);
    g_pcm_buffers.erase(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_syncinit_app_core_NativeCore_nativeCreateDacClock(
    JNIEnv* env, jobject, jint sample_rate_hz) {
    if (sample_rate_hz < 8'000 || sample_rate_hz > 192'000) {
        throw_illegal_argument(env, "DAC sample rate is outside the supported range");
        return 0;
    }
    try {
        auto clock = std::make_shared<NativeDacClock>(sample_rate_hz);
        const jlong handle = g_next_dac_clock_handle.fetch_add(1);
        if (handle <= 0) throw std::overflow_error("DAC clock handle space exhausted");
        std::lock_guard<std::mutex> lock(g_dac_clocks_mutex);
        g_dac_clocks.emplace(handle, std::move(clock));
        return handle;
    } catch (const std::exception& error) {
        throw_illegal_argument(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_syncinit_app_core_NativeCore_nativeAddDacTimestamp(
    JNIEnv*, jobject, jlong handle, jlong frame_position, jlong timestamp_ns) {
    const auto clock = find_dac_clock(handle);
    if (!clock) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(clock->mutex);
    return clock->mapper.add_sample(
        static_cast<std::int64_t>(frame_position),
        static_cast<syit::Nanoseconds>(timestamp_ns)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_syncinit_app_core_NativeCore_nativeDacClockSnapshot(
    JNIEnv* env, jobject, jlong handle) {
    const auto clock = find_dac_clock(handle);
    if (!clock) return nullptr;

    std::array<jdouble, 9> values{};
    {
        std::lock_guard<std::mutex> lock(clock->mutex);
        const auto fit = clock->mapper.estimate();
        if (fit.has_value()) {
            values = {{
                1.0,
                fit->rate_error_ppm,
                fit->residual_rms_ns / 1'000.0,
                static_cast<jdouble>(fit->samples_used),
                static_cast<jdouble>(fit->sample_span_ns),
                fit->nanoseconds_per_frame,
                static_cast<jdouble>(fit->frame_origin),
                static_cast<jdouble>(fit->time_origin_ns),
                static_cast<jdouble>(clock->mapper.rejected_samples())}};
        } else {
            values[3] = static_cast<jdouble>(clock->mapper.samples_seen());
            values[4] = static_cast<jdouble>(clock->mapper.sample_span_ns());
            values[8] = static_cast<jdouble>(clock->mapper.rejected_samples());
        }
    }

    jdoubleArray output = env->NewDoubleArray(static_cast<jsize>(values.size()));
    if (output == nullptr) return nullptr;
    env->SetDoubleArrayRegion(output, 0, static_cast<jsize>(values.size()), values.data());
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_com_syncinit_app_core_NativeCore_nativeResetDacClock(
    JNIEnv*, jobject, jlong handle) {
    const auto clock = find_dac_clock(handle);
    if (!clock) return;
    std::lock_guard<std::mutex> lock(clock->mutex);
    clock->mapper.reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_syncinit_app_core_NativeCore_nativeCloseDacClock(
    JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(g_dac_clocks_mutex);
    g_dac_clocks.erase(handle);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_syncinit_app_core_NativeCore_nativeEncodePcmChunkFrame(
    JNIEnv* env,
    jobject,
    jlong session_id,
    jlong sequence,
    jlong sample_index,
    jint frames,
    jint channels,
    jbyteArray pcm_array) {
    if (session_id < 0 || static_cast<std::uint64_t>(session_id) > UINT32_MAX ||
        sequence < 0 || static_cast<std::uint64_t>(sequence) > UINT32_MAX ||
        sample_index < 0 || frames <= 0 || frames > 65'535 ||
        channels < 1 || channels > 2 || pcm_array == nullptr) {
        throw_illegal_argument(env, "PCM chunk metadata contains an out-of-range value");
        return nullptr;
    }

    const jsize payload_size = env->GetArrayLength(pcm_array);
    if (payload_size < 0 || payload_size > static_cast<jsize>(syit::kMaxFrameSize)) {
        throw_illegal_argument(env, "PCM chunk payload exceeds the frame limit");
        return nullptr;
    }

    std::vector<jbyte> raw(static_cast<std::size_t>(payload_size));
    if (payload_size > 0) {
        env->GetByteArrayRegion(pcm_array, 0, payload_size, raw.data());
        if (env->ExceptionCheck()) return nullptr;
    }

    try {
        syit::AudioChunk chunk;
        chunk.session_id = static_cast<std::uint32_t>(session_id);
        chunk.sequence = static_cast<std::uint32_t>(sequence);
        chunk.sample_index = static_cast<syit::SampleIndex>(sample_index);
        chunk.frames = static_cast<std::uint16_t>(frames);
        chunk.codec = syit::kAudioCodecPcm16;
        chunk.channels = static_cast<std::uint8_t>(channels);
        chunk.payload.resize(static_cast<std::size_t>(payload_size));
        for (jsize index = 0; index < payload_size; ++index) {
            chunk.payload[static_cast<std::size_t>(index)] =
                static_cast<std::uint8_t>(raw[static_cast<std::size_t>(index)]);
        }
        const auto frame = syit::encode_audio_chunk(chunk);
        return make_byte_array(env, syit::encode_frame(frame));
    } catch (const std::exception& error) {
        throw_illegal_argument(env, error.what());
        return nullptr;
    }
}


extern "C" JNIEXPORT void JNICALL
Java_com_syncinit_app_core_NativeCore_nativeSeekPcmBuffer(
    JNIEnv* env, jobject, jlong handle, jlong first_sample_index) {
    auto native_buffer = find_pcm_buffer(handle);
    if (!native_buffer) {
        throw_illegal_argument(env, "PCM jitter buffer handle is closed");
        return;
    }
    if (first_sample_index < 0) {
        throw_illegal_argument(env, "PCM seek sample index must be non-negative");
        return;
    }
    try {
        std::lock_guard<std::mutex> lock(native_buffer->initialization_mutex);
        native_buffer->buffer.seek(static_cast<syit::SampleIndex>(first_sample_index));
        native_buffer->initialized = true;
        native_buffer->playback_started = true;
    } catch (const std::exception& error) {
        throw_illegal_argument(env, error.what());
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_syncinit_app_core_NativeCore_nativeEncodeAnchorFrame(
    JNIEnv* env,
    jobject,
    jint kind,
    jlong master_time_ns,
    jlong sample_index,
    jboolean playing,
    jint crossfade_ms) {
    if (kind < 0 || kind > 4 || master_time_ns < 0 || sample_index < 0 ||
        crossfade_ms < 0 || crossfade_ms > 65'535) {
        throw_illegal_argument(env, "ANCHOR fields are outside the supported wire range");
        return nullptr;
    }
    try {
        const syit::Anchor anchor{
            static_cast<std::uint8_t>(kind),
            static_cast<std::int64_t>(master_time_ns),
            static_cast<std::int64_t>(sample_index),
            playing == JNI_TRUE,
            static_cast<std::uint16_t>(crossfade_ms)};
        return make_byte_array(
            env,
            syit::encode_frame(syit::encode_control_message(syit::ControlMessage{anchor})));
    } catch (const std::exception& error) {
        throw_illegal_argument(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_syncinit_app_core_NativeCore_nativeDecodeAnchorFrame(
    JNIEnv* env, jobject, jbyteArray encoded_frame) {
    std::vector<std::uint8_t> wire;
    if (!copy_wire_frame(env, encoded_frame, wire)) return nullptr;
    try {
        const auto frame = syit::decode_frame(wire);
        if (frame.flags != 0U || frame.message_type != syit::kAnchorMessageType) {
            throw std::invalid_argument("expected an ANCHOR frame with zero flags");
        }
        const auto message = syit::decode_control_message(frame);
        const auto* anchor = std::get_if<syit::Anchor>(&message);
        if (anchor == nullptr) throw std::invalid_argument("decoded control message is not ANCHOR");
        const std::array<jlong, 5> values{{
            static_cast<jlong>(anchor->kind),
            static_cast<jlong>(anchor->master_time_ns),
            static_cast<jlong>(anchor->sample_idx),
            anchor->playing ? 1LL : 0LL,
            static_cast<jlong>(anchor->crossfade_ms)}};
        jlongArray output = env->NewLongArray(static_cast<jsize>(values.size()));
        if (output == nullptr) return nullptr;
        env->SetLongArrayRegion(output, 0, static_cast<jsize>(values.size()), values.data());
        return output;
    } catch (const std::exception& error) {
        throw_illegal_argument(env, error.what());
        return nullptr;
    }
}
