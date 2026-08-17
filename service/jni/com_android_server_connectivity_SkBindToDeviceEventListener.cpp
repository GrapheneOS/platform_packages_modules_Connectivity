// Based on Connectivity/service/jni/com_android_server_connectivity_LocalNetEventListener.cpp

#define LOG_TAG "SkBindToDeviceEventListenerNative"

#include <android-base/unique_fd.h>
#include <android/file_descriptor_jni.h>
#include <jni.h>
#include <nativehelper/JNIPlatformHelp.h>
#include <nativehelper/jni_macros.h>
#include <nativehelper/scoped_local_ref.h>
#include <utils/misc.h>

#include "bpf/BpfUtils.h"
#include "libeventpolling/SkBindToDeviceEventHandler.h"

namespace android {

using android::net::eventpolling::SkBindToDeviceEventHandler;

static jobject nativeGetSkBindToDeviceEventRingbufFd(JNIEnv *env, jclass clazz) {
    android::base::unique_fd fd = SkBindToDeviceEventHandler::GetNewRingbufFd();
    if (!fd.ok()) {
        ALOGE("Failed to get sk_bind_to_device_event_ringbuf fd");
        return nullptr;
    }
    return jniCreateFileDescriptor(env, fd.release());
}

static jintArray nativeConsumeAllSkBindToDeviceEvents(JNIEnv *env,
                                                      jclass clazz) {
    std::vector<uint32_t> uids_pids = SkBindToDeviceEventHandler::ConsumeAll();
    if (uids_pids.empty()) {
        return env->NewIntArray(0);
    }

    ScopedLocalRef<jintArray> result(env, env->NewIntArray(uids_pids.size()));
    if (!result.get()) {
        ALOGE("Failed to allocate jintArray");
        return nullptr;
    }
    env->SetIntArrayRegion(result.get(), 0, uids_pids.size(),
                           reinterpret_cast<const jint *>(uids_pids.data()));
    return result.release();
}

static const JNINativeMethod gMethods[] = {
    MAKE_JNI_NATIVE_METHOD("nativeGetSkBindToDeviceEventRingbufFd",
                           "()Ljava/io/FileDescriptor;",
                           nativeGetSkBindToDeviceEventRingbufFd),
    MAKE_JNI_NATIVE_METHOD_AUTOSIG("nativeConsumeAllSkBindToDeviceEvents",
                                   nativeConsumeAllSkBindToDeviceEvents),
};

int register_com_android_server_connectivity_SkBindToDeviceEventListener(
    JNIEnv *env) {
    return jniRegisterNativeMethods(env,
                                    "android/net/connectivity/com/android/"
                                    "server/connectivity/SkBindToDeviceEventListener",
                                    gMethods, NELEM(gMethods));
}
} // namespace android
