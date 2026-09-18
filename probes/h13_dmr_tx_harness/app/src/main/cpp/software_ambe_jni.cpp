#include <jni.h>
#include <cstdint>
#include <mutex>
#include <vector>

#include "vendor/ambe/ambe_wrapper.h"

namespace {
std::mutex g_ambe_mutex;

void throw_java(JNIEnv* env, const char* class_name, const char* message) {
    jclass cls = env->FindClass(class_name);
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
    }
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_net_elfradio_h13dmrtx_SoftwareAmbeEncoder_nativeCreate(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_ambe_mutex);
    AmbeEncoder encoder = ambe_encoder_create();
    if (encoder == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "AMBE encoder create failed");
        return 0;
    }
    return reinterpret_cast<jlong>(encoder);
}

extern "C" JNIEXPORT void JNICALL
Java_net_elfradio_h13dmrtx_SoftwareAmbeEncoder_nativeDestroy(
        JNIEnv*, jclass, jlong handle) {
    if (handle == 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(g_ambe_mutex);
    ambe_encoder_destroy(reinterpret_cast<AmbeEncoder>(handle));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_net_elfradio_h13dmrtx_SoftwareAmbeEncoder_nativeEncode49BitPacked9Batch(
        JNIEnv* env, jclass, jshortArray pcm_array) {
    if (pcm_array == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "PCM is null");
        return nullptr;
    }
    const jsize sample_count = env->GetArrayLength(pcm_array);
    if (sample_count <= 0 || sample_count % 160 != 0) {
        throw_java(env, "java/lang/IllegalArgumentException",
                "PCM sample count must be a non-zero multiple of 160");
        return nullptr;
    }
    std::vector<jshort> samples(static_cast<size_t>(sample_count));
    env->GetShortArrayRegion(pcm_array, 0, sample_count, samples.data());
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    const size_t frame_count = static_cast<size_t>(sample_count / 160);
    std::vector<jbyte> encoded(frame_count * 9U);
    std::lock_guard<std::mutex> lock(g_ambe_mutex);
    AmbeEncoder encoder = ambe_encoder_create();
    if (encoder == nullptr) {
        throw_java(env, "java/lang/IllegalStateException",
                "AMBE 49-bit encoder create failed");
        return nullptr;
    }
    for (size_t frame = 0; frame < frame_count; ++frame) {
        uint8_t packed[9] = {};
        const int result = ambe_encode_49bit_packed9(encoder,
                reinterpret_cast<const int16_t*>(samples.data() + frame * 160U),
                packed);
        if (result != 0) {
            ambe_encoder_destroy(encoder);
            throw_java(env, "java/lang/IllegalStateException",
                    "AMBE 49-bit encode failed");
            return nullptr;
        }
        for (size_t index = 0; index < 9U; ++index) {
            encoded[frame * 9U + index] = static_cast<jbyte>(packed[index]);
        }
    }
    ambe_encoder_destroy(encoder);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(encoded.size()));
    if (out == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(encoded.size()),
            encoded.data());
    return out;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_net_elfradio_h13dmrtx_SoftwareAmbeEncoder_nativeChannelEncode49BitPacked9Batch(
        JNIEnv* env, jclass, jbyteArray packed_array) {
    if (packed_array == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "packed49 is null");
        return nullptr;
    }
    const jsize byte_count = env->GetArrayLength(packed_array);
    if (byte_count <= 0 || byte_count % 9 != 0) {
        throw_java(env, "java/lang/IllegalArgumentException",
                "packed49 length must be a non-zero multiple of 9");
        return nullptr;
    }
    std::vector<jbyte> packed(static_cast<size_t>(byte_count));
    std::vector<jbyte> encoded(static_cast<size_t>(byte_count));
    env->GetByteArrayRegion(packed_array, 0, byte_count, packed.data());
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(g_ambe_mutex);
    for (jsize offset = 0; offset < byte_count; offset += 9) {
        if (ambe_channel_encode_49bit_packed9(
                reinterpret_cast<const uint8_t*>(packed.data() + offset),
                reinterpret_cast<uint8_t*>(encoded.data() + offset)) != 0) {
            throw_java(env, "java/lang/IllegalStateException",
                    "DMR channel encode failed");
            return nullptr;
        }
    }
    jbyteArray out = env->NewByteArray(byte_count);
    if (out != nullptr) {
        env->SetByteArrayRegion(out, 0, byte_count, encoded.data());
    }
    return out;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_net_elfradio_h13dmrtx_SoftwareAmbeEncoder_nativeEncodeBatch(
        JNIEnv* env, jclass, jlong handle, jshortArray pcm_array) {
    if (handle == 0) {
        throw_java(env, "java/lang/IllegalStateException", "AMBE encoder is closed");
        return nullptr;
    }
    if (pcm_array == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "PCM is null");
        return nullptr;
    }
    const jsize sample_count = env->GetArrayLength(pcm_array);
    if (sample_count <= 0 || sample_count % 160 != 0) {
        throw_java(env, "java/lang/IllegalArgumentException",
                "PCM sample count must be a non-zero multiple of 160");
        return nullptr;
    }

    std::vector<jshort> samples(static_cast<size_t>(sample_count));
    env->GetShortArrayRegion(pcm_array, 0, sample_count, samples.data());
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    const size_t frame_count = static_cast<size_t>(sample_count / 160);
    std::vector<jbyte> encoded(frame_count * 9U);

    std::lock_guard<std::mutex> lock(g_ambe_mutex);
    AmbeEncoder encoder = reinterpret_cast<AmbeEncoder>(handle);
    for (size_t frame = 0; frame < frame_count; ++frame) {
        uint8_t work_buffer[72] = {};
        const int result = ambe_encode_dmr(encoder,
                reinterpret_cast<const int16_t*>(samples.data() + frame * 160U),
                work_buffer);
        if (result != 0) {
            throw_java(env, "java/lang/IllegalStateException", "AMBE encode failed");
            return nullptr;
        }
        for (size_t index = 0; index < 9U; ++index) {
            encoded[frame * 9U + index] = static_cast<jbyte>(work_buffer[index]);
        }
    }

    jbyteArray output = env->NewByteArray(static_cast<jsize>(encoded.size()));
    if (output != nullptr) {
        env->SetByteArrayRegion(output, 0, static_cast<jsize>(encoded.size()),
                encoded.data());
    }
    return output;
}

extern "C" JNIEXPORT jlong JNICALL
Java_net_elfradio_h13dmrtx_SoftwareAmbeDecoder_nativeCreate(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_ambe_mutex);
    AmbeDecoder decoder = ambe_decoder_create();
    if (decoder == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "AMBE decoder create failed");
        return 0;
    }
    return reinterpret_cast<jlong>(decoder);
}

extern "C" JNIEXPORT void JNICALL
Java_net_elfradio_h13dmrtx_SoftwareAmbeDecoder_nativeDestroy(
        JNIEnv*, jclass, jlong handle) {
    if (handle == 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(g_ambe_mutex);
    ambe_decoder_destroy(reinterpret_cast<AmbeDecoder>(handle));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_net_elfradio_h13dmrtx_SoftwareAmbeDecoder_nativeChannelDecodeTo49BitPacked9Batch(
        JNIEnv* env, jclass, jbyteArray dmr_array) {
    if (dmr_array == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "DMR input is null");
        return nullptr;
    }
    const jsize byte_count = env->GetArrayLength(dmr_array);
    if (byte_count <= 0 || byte_count % 9 != 0) {
        throw_java(env, "java/lang/IllegalArgumentException",
                "DMR input length must be a non-zero multiple of 9");
        return nullptr;
    }
    std::vector<jbyte> dmr(static_cast<size_t>(byte_count));
    std::vector<jbyte> packed(static_cast<size_t>(byte_count));
    env->GetByteArrayRegion(dmr_array, 0, byte_count, dmr.data());
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(g_ambe_mutex);
    for (jsize offset = 0; offset < byte_count; offset += 9) {
        if (ambe_channel_decode_to_49bit_packed9(
                reinterpret_cast<const uint8_t*>(dmr.data() + offset),
                reinterpret_cast<uint8_t*>(packed.data() + offset)) != 0) {
            throw_java(env, "java/lang/IllegalStateException",
                    "DMR channel decode failed");
            return nullptr;
        }
    }
    jbyteArray out = env->NewByteArray(byte_count);
    if (out != nullptr) {
        env->SetByteArrayRegion(out, 0, byte_count, packed.data());
    }
    return out;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_net_elfradio_h13dmrtx_SoftwareAmbeDecoder_nativeDecodeDmrReferenceBatch(
        JNIEnv* env, jclass, jlong handle, jbyteArray dmr_array,
        jintArray error_array) {
    if (handle == 0 || dmr_array == nullptr || error_array == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException",
                "decoder, DMR input, or error output is invalid");
        return nullptr;
    }
    const jsize byte_count = env->GetArrayLength(dmr_array);
    if (byte_count <= 0 || byte_count % 9 != 0) {
        throw_java(env, "java/lang/IllegalArgumentException",
                "DMR input length must be a non-zero multiple of 9");
        return nullptr;
    }
    const size_t frame_count = static_cast<size_t>(byte_count / 9);
    if (env->GetArrayLength(error_array) != static_cast<jsize>(frame_count)) {
        throw_java(env, "java/lang/IllegalArgumentException",
                "error output length does not match frame count");
        return nullptr;
    }
    std::vector<jbyte> dmr(static_cast<size_t>(byte_count));
    std::vector<jshort> pcm(frame_count * 160U);
    std::vector<jint> frame_errors(frame_count);
    env->GetByteArrayRegion(dmr_array, 0, byte_count, dmr.data());
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(g_ambe_mutex);
    AmbeDecoder decoder = reinterpret_cast<AmbeDecoder>(handle);
    for (size_t frame = 0; frame < frame_count; ++frame) {
        const int errors = ambe_decode_dmr_reference(decoder,
                reinterpret_cast<const uint8_t*>(dmr.data() + frame * 9U),
                reinterpret_cast<int16_t*>(pcm.data() + frame * 160U));
        if (errors < 0) {
            throw_java(env, "java/lang/IllegalStateException",
                    "reference DMR decode failed");
            return nullptr;
        }
        frame_errors[frame] = static_cast<jint>(errors);
    }
    env->SetIntArrayRegion(error_array, 0, static_cast<jsize>(frame_count),
            frame_errors.data());
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    jshortArray out = env->NewShortArray(static_cast<jsize>(pcm.size()));
    if (out != nullptr) {
        env->SetShortArrayRegion(out, 0, static_cast<jsize>(pcm.size()),
                pcm.data());
    }
    return out;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_net_elfradio_h13dmrtx_SoftwareAmbeDecoder_nativeDecode49BitPacked9Batch(
        JNIEnv* env, jclass, jlong handle, jbyteArray packed_array,
        jintArray error_array) {
    if (handle == 0 || packed_array == nullptr || error_array == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException",
                "decoder, packed49 input, or error output is invalid");
        return nullptr;
    }
    const jsize byte_count = env->GetArrayLength(packed_array);
    if (byte_count <= 0 || byte_count % 9 != 0) {
        throw_java(env, "java/lang/IllegalArgumentException",
                "packed49 length must be a non-zero multiple of 9");
        return nullptr;
    }
    std::vector<jbyte> packed(static_cast<size_t>(byte_count));
    env->GetByteArrayRegion(packed_array, 0, byte_count, packed.data());
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    const size_t frame_count = static_cast<size_t>(byte_count / 9);
    if (env->GetArrayLength(error_array) != static_cast<jsize>(frame_count)) {
        throw_java(env, "java/lang/IllegalArgumentException",
                "error output length does not match frame count");
        return nullptr;
    }
    std::vector<jshort> pcm(frame_count * 160U);
    std::vector<jint> frame_errors(frame_count);
    std::lock_guard<std::mutex> lock(g_ambe_mutex);
    AmbeDecoder decoder = reinterpret_cast<AmbeDecoder>(handle);
    for (size_t frame = 0; frame < frame_count; ++frame) {
        const int errors = ambe_decode_49bit_packed9(decoder,
                reinterpret_cast<const uint8_t*>(packed.data() + frame * 9U),
                reinterpret_cast<int16_t*>(pcm.data() + frame * 160U));
        if (errors < 0) {
            throw_java(env, "java/lang/IllegalStateException",
                    "AMBE 49-bit decode failed");
            return nullptr;
        }
        frame_errors[frame] = static_cast<jint>(errors);
    }
    env->SetIntArrayRegion(error_array, 0, static_cast<jsize>(frame_count),
            frame_errors.data());
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    jshortArray out = env->NewShortArray(static_cast<jsize>(pcm.size()));
    if (out != nullptr) {
        env->SetShortArrayRegion(out, 0, static_cast<jsize>(pcm.size()),
                pcm.data());
    }
    return out;
}
