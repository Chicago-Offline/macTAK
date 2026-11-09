/**
 * G1.9 kernel-mode launcher (dev/g1/jni-tests/run.sh).
 *
 * The kernel tests run UNDER THE JAVA LAUNCHER — the smoke's proven boot
 * (EngineLoadSmoke): a raw JNI_CreateJavaVM boot crashes inside hotspot's
 * jni_FindClass when the VM itself invokes JNI_OnLoad (as_Klass(NULL) on
 * Zulu 8 aarch64), while the launcher context runs it clean.
 *
 * The static block loads the three staged dylibs in smoke order (core,
 * JNI glue — JNI_OnLoad runs here — then the gtest suite as a library)
 * and main() hands the gtest arguments to the suite's JNI entry.
 */
public final class JniTestsMain
{
    static
    {
        final String libdir = System.getenv("TAKJNI_TEST_LIBDIR");
        if(libdir == null || libdir.isEmpty())
            throw new IllegalStateException(
                "TAKJNI_TEST_LIBDIR unset (run.sh exports it as the staged dir)");
        System.load(libdir + "/libtakengine.dylib");
        System.load(libdir + "/libtakenginejni.dylib");
        System.load(libdir + "/libtakengine-jni-tests.dylib");
    }

    public static void main(final String[] args)
    {
        System.exit(takengineJniTestsMain(args));
    }

    private static native int takengineJniTestsMain(String[] args);
}
