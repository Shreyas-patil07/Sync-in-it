// SPDX-License-Identifier: Apache-2.0
#include <jni.h>

#include "syit/messages.h"
#include "syit/protocol.h"
#include "syit/session.h"

#include <array>
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
        : session(std::move(config)) {}

    std::mutex mutex;
    syit::ReceiverSession session;
};

std::mutex g_registry_mutex;
std::unordered_map<jlong, std::shared_ptr<NativeSession>> g_sessions;
std::atomic<jlong> g_next_handle{1};

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
