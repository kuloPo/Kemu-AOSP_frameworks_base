#include <jni.h>

JNIEXPORT jint JNICALL Java_com_android_nativebridge_probe_Probe_answer(
        JNIEnv* env, jclass cls) {
    (void)env;
    (void)cls;
    return 42;
}
