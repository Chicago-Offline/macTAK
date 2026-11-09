/*
 * G1.9 kernel-mode pins: the Java-backed IOProviderFactory proxy as the
 * engine's global Filesystem, installed the way production installs it
 * (Java IOProviderFactory.installFactory -> IO_setFilesystem). Real JVM,
 * real java.io.File, real temp files:
 *
 *  - IO_getFileCount's isDirectory branch (the wave-5 fix: pristine
 *    calls isFile twice, so a directory fell through to TE_IllegalState),
 *  - the wave-5 ExceptionClear guards: real Java failures must surface
 *    as TE_Err with a CLEAN JNIEnv (pristine leaves the exception
 *    pending and the next JNI call misfires).
 *
 * Patches under test: ioproviderfactory-guards-and-filecount.patch,
 * ioproviderfactory-sweep-init-guards.patch.
 */
#include <jni.h>

#include <util/IO2.h>
#include <db/Database2.h>
#include <db/DatabaseFactory.h>
#include <db/DatabaseInformation.h>

#include <gtest/gtest.h>

#include <stdio.h>
#include <stdlib.h>
#include <string>

#include "jni_test_env.hpp"

namespace
{
    class JniKernelFilesystem : public JniKernelTest
    { };
    class JniKernelDatabase : public JniKernelTest
    { };

    std::string make_temp_dir()
    {
        char tmpl[] = "/tmp/takjni-kernel-XXXXXX";
        const char *d = mkdtemp(tmpl);
        return d ? std::string(d) : std::string();
    }

    void touch_file(const std::string &dir, const char *name)
    {
        const std::string p = dir + "/" + name;
        FILE *f = fopen(p.c_str(), "w");
        ASSERT_NE(f, nullptr);
        fputs("x", f);
        fclose(f);
    }
}

TEST_F(JniKernelFilesystem, GetFileCountOfADirectory)
{
    const std::string dir = make_temp_dir();
    ASSERT_FALSE(dir.empty());
    touch_file(dir, "a.txt");
    touch_file(dir, "b.txt");

    std::size_t count = 0u;
    /* through the Java-backed proxy: exists -> isFile -> isDirectory ->
     * listFiles -> recurse — the fixed isDirectory branch */
    EXPECT_EQ(TAK::Engine::Util::IO_getFileCount(&count, dir.c_str()),
              TAK::Engine::Util::TE_Ok);
    EXPECT_EQ(count, 2u);   /* pristine: TE_IllegalState (isFile twice) */

    remove(dir.c_str());
}

TEST_F(JniKernelFilesystem, GetFileCountOfAFile)
{
    const std::string dir = make_temp_dir();
    ASSERT_FALSE(dir.empty());
    touch_file(dir, "one.txt");

    std::size_t count = 0u;
    EXPECT_EQ(TAK::Engine::Util::IO_getFileCount(&count, (dir + "/one.txt").c_str()),
              TAK::Engine::Util::TE_Ok);
    EXPECT_EQ(count, 1u);

    remove(dir.c_str());
}

TEST_F(JniKernelFilesystem, GetFileCountOfAMissingPath)
{
    std::size_t count = 0u;
    EXPECT_EQ(TAK::Engine::Util::IO_getFileCount(&count, "/nonexistent-takjni/gone"),
              TAK::Engine::Util::TE_InvalidArg);
}

TEST_F(JniKernelFilesystem, CreateTempFileOnABadDirBailsClean)
{
    JNIEnv *env = jnitest::launcher_env();
    ASSERT_NE(env, nullptr);

    TAK::Engine::Port::String value;
    /* the Java side throws IOException for the missing parent directory */
    EXPECT_EQ(TAK::Engine::Util::IO_createTempFile(value, "takjni", ".tmp", "/nonexistent-takjni"),
              TAK::Engine::Util::TE_Err);
    /* the wave-5 ExceptionClear guard: pristine leaves the exception pending */
    EXPECT_FALSE(env->ExceptionCheck());
}

TEST_F(JniKernelFilesystem, DeleteOfAMissingPathIsTErr)
{
    /* no exception on the Java side — File.delete just reports false —
     * so this pins the success-check path: TE_Err either way, cleanly */
    EXPECT_EQ(TAK::Engine::Util::IO_delete("/nonexistent-takjni/gone"),
              TAK::Engine::Util::TE_Err);
}

TEST_F(JniKernelDatabase, CreateOnABadUriBailsClean)
{
    JNIEnv *env = jnitest::launcher_env();
    ASSERT_NE(env, nullptr);

    TAK::Engine::DB::DatabasePtr db(nullptr, nullptr);
    /* the proxy is a registered DatabaseProvider; the Java side fails to
     * open the (nonexistent) database and throws */
    EXPECT_EQ(TAK::Engine::DB::DatabaseFactory_create(
                  db, TAK::Engine::DB::DatabaseInformation("/nonexistent-takjni/test.db")),
              TAK::Engine::Util::TE_Err);
    EXPECT_FALSE(env->ExceptionCheck());
}
