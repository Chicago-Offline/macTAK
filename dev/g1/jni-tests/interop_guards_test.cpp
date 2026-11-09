/*
 * G1.9 guards-mode pins: Interop::Math marshaling when the kernel math
 * classes are absent (empty classpath). checkInit() must fail and the
 * marshal must bail as TE_IllegalState instead of NewObject'ing through
 * a null class (a pending-exception crash on Android).
 * Patch under test: projection-math-interop-guards.patch.
 */
#include <jni.h>

#include <math/Matrix.h>
#include <math/Rectangle2.h>

#include <gtest/gtest.h>

#include "jni_test_env.hpp"
#include "interop/math/Interop.h"

namespace
{
    class JniGuardsInterop : public JniGuardsTest
    { };
}

TEST_F(JniGuardsInterop, MatrixMarshalBailsIllegalState)
{
    JNIEnv *env = jnitest::jni_env();
    ASSERT_NE(env, nullptr);

    TAK::Engine::Math::Matrix2 matrix;
    TAKEngineJNI::Interop::Java::JNILocalRef value(*env, nullptr);
    EXPECT_EQ(TAKEngineJNI::Interop::Math::Interop_marshal(value, *env, matrix),
              TAK::Engine::Util::TE_IllegalState);
}

TEST_F(JniGuardsInterop, RectangleMarshalBailsIllegalState)
{
    JNIEnv *env = jnitest::jni_env();
    ASSERT_NE(env, nullptr);

    const TAK::Engine::Math::Rectangle2<double> rect(0.0, 0.0, 10.0, 10.0);
    TAKEngineJNI::Interop::Java::JNILocalRef value(*env, nullptr);
    EXPECT_EQ(TAKEngineJNI::Interop::Math::Interop_marshal(value, *env, rect),
              TAK::Engine::Util::TE_IllegalState);
}
