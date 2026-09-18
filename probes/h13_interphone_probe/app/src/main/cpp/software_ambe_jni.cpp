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
Java_net_elfradio_h13interphoneprobe_SoftwareAmbeEncoder_nativeCreate(
        JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_ambe_mutex);
    AmbeEncoder encoder = ambe_encoder_create();
    if (encoder == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "创建软件AMBE编码器失败");
        return 0;
    }
    return reinterpret_cast<jlong>(encoder);
}

extern "C" JNIEXPORT void JNICALL
Java_net_elfradio_h13interphoneprobe_SoftwareAmbeEncoder_nativeDestroy(
        JNIEnv*, jclass, jlong handle) {
    if (handle == 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(g_ambe_mutex);
    ambe_encoder_destroy(reinterpret_cast<AmbeEncoder>(handle));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_net_elfradio_h13interphoneprobe_SoftwareAmbeEncoder_nativeEncodeBatch(
        JNIEnv* env, jclass, jlong handle, jshortArray pcm_array) {
    if (handle == 0) {
        throw_java(env, "java/lang/IllegalStateException", "软件AMBE编码器已经关闭");
        return nullptr;
    }
    if (pcm_array == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "PCM不能为空");
        return nullptr;
    }
    const jsize sample_count = env->GetArrayLength(pcm_array);
    if (sample_count <= 0 || sample_count % 160 != 0) {
        throw_java(env, "java/lang/IllegalArgumentException",
                "PCM采样数必须为非零且是160的整数倍");
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
            throw_java(env, "java/lang/IllegalStateException", "软件AMBE编码失败");
            return nullptr;
        }
        for (size_t index = 0; index < 9U; ++index) {
            encoded[frame * 9U + index] = static_cast<jbyte>(work_buffer[index]);
        }
    }

    jbyteArray output = env->NewByteArray(static_cast<jsize>(encoded.size()));
    if (output == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(output, 0, static_cast<jsize>(encoded.size()),
            encoded.data());
    return output;
}
