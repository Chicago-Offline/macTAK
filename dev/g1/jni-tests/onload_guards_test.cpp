/*
 * G1.9 guards-mode pin: JNI_OnLoad against a bare desktop JVM (empty
 * classpath). The wave-5 classloader guards must clear the EngineLibrary
 * NoClassDefFoundError (eager early-returns there would make the JVM
 * eagerly verify EngineLibrary — which needs bcprov on the classpath —
 * turning a tolerated NULL into a fatal load error) and OnLoad must end
 * as a CLEAN JNI_ERR at the first genuinely-missing engine class
 * (GLLineBatch), never a pending-exception abort. Doubles as the F-1
 * characterization: pre-patch this call dies inside the unguarded Class
 * block. When wave 7 reopens the slot, this pin flips to the reopened
 * truth.
 * Patch under test: common-onload-classloader-guards.patch.
 */
#include <jni.h>

#include <gtest/gtest.h>

#include "jni_test_env.hpp"

namespace
{
    /* defined in libtakenginejni (common.cpp) */
    extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved);

    class JniGuardsOnLoad : public JniGuardsTest
    { };
}

TEST_F(JniGuardsOnLoad, ReturnsJniErrCleanlyWithoutKernelClasses)
{
    JavaVM *vm = jnitest::boot_jvm();
    ASSERT_NE(vm, nullptr);

    EXPECT_EQ(JNI_OnLoad(vm, nullptr), JNI_ERR);
}

TEST_F(JniGuardsOnLoad, LeavesTheEnvClean)
{
    JavaVM *vm = jnitest::boot_jvm();
    ASSERT_NE(vm, nullptr);
    JNIEnv *env = jnitest::jni_env();
    ASSERT_NE(env, nullptr);

    /* Self-contained: run OnLoad here instead of relying on the test
     * above having run it. Under --gtest_filter or --gtest_shuffle this
     * pin otherwise checked an env nothing had touched, and passed
     * vacuously. A repeat OnLoad only reassigns its globals. */
    (void)JNI_OnLoad(vm, nullptr);
    EXPECT_FALSE(env->ExceptionCheck());
}
