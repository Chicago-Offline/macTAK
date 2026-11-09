/*
 * G1.9 kernel mode entry point — the test suite as a shared library,
 * loaded by JniTestsMain (dev/g1/jni-tests/run.sh) AFTER the java
 * launcher has System.load'ed the engine dylibs. JNI_OnLoad ran in that
 * healthy launcher context (the raw JNI_CreateJavaVM boot crashes inside
 * hotspot's jni_FindClass when IT invokes JNI_OnLoad — see run.sh), so
 * the kernel tests only need the JNIEnv of the already-running VM.
 */
#include <jni.h>
#include <gtest/gtest.h>
#include <cstdlib>
#include <cstring>

static JNIEnv *s_env = NULL;

extern "C" JNIEnv *takengine_jni_tests_env(void)
{
    return s_env;
}

extern "C" JNIEXPORT jint JNICALL Java_JniTestsMain_takengineJniTestsMain
  (JNIEnv *env, jclass cls, jobjectArray jargs)
{
    (void)cls;
    s_env = env;
    const jsize n = jargs ? env->GetArrayLength(jargs) : 0;
    int argc = (int)n + 1;
    char **argv = (char **)calloc((size_t)argc + 1, sizeof(char *));
    argv[0] = strdup("takengine-jni-tests");
    for(jsize i = 0; i < n; i++) {
        jstring js = (jstring)env->GetObjectArrayElement(jargs, i);
        const char *c = env->GetStringUTFChars(js, NULL);
        argv[i + 1] = strdup(c);
        env->ReleaseStringUTFChars(js, c);
        env->DeleteLocalRef(js);
    }
    ::testing::InitGoogleTest(&argc, argv);
    const int rc = RUN_ALL_TESTS();
    for(int i = 1; i < argc; i++)
        free(argv[i]);
    free(argv[0]);
    free(argv);
    return rc;
}
