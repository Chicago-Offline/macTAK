// macTAK addition to the commo JNI glue (copied into jni/native and built
// into libcommoncommojni.dylib by dev/g1/build-commo.sh). ATAK data packages
// from OpenTAKServer / TAK Server carry PKCS#12 files encrypted with
// pbeWithSHA1And40BitRC2-CBC; OpenSSL 3 only decrypts those once its legacy
// provider is loaded, otherwise commo's PKCS12_parse fails with "Invalid
// format for client cert". Loading into the default library context here
// reaches commo because both link the same libcrypto.3.dylib.
#include <jni.h>

#include <openssl/provider.h>

extern "C" JNIEXPORT jboolean JNICALL Java_mactak_headless_NativeSsl_loadLegacyProvider
  (JNIEnv *, jclass)
{
    static OSSL_PROVIDER *legacy = nullptr;
    static OSSL_PROVIDER *deflt = nullptr;
    if (!legacy)
        legacy = OSSL_PROVIDER_load(nullptr, "legacy");
    // Explicitly loading any provider disables the implicit default; keep it.
    if (!deflt)
        deflt = OSSL_PROVIDER_load(nullptr, "default");
    return (legacy && deflt) ? JNI_TRUE : JNI_FALSE;
}
