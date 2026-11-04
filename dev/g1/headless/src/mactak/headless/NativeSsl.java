package mactak.headless;

/**
 * macTAK JNI shim in {@code libcommoncommojni.dylib}
 * ({@code dev/g1/jni-extra/mactak_ssl.cpp}): loads OpenSSL 3's legacy
 * provider so commo can parse the RC2-40 PKCS#12 files ATAK data packages
 * carry.
 */
final class NativeSsl {

    /** Idempotent; returns false if the legacy provider module is missing. */
    static native boolean loadLegacyProvider();

    private NativeSsl() {}
}
