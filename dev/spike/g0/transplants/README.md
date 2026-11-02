# `android-port-3.1.0.jar` — G0 transplant for CI + fresh clones

`gov.tak.support:android-port` is only on TAK's gated artifactory, so the G0
spike built a compile-only substitute (**not for runtime use**):

- `android.jar` API-33 framework signatures (Android SDK)
- TAK classes pulled from the public ATAK-CIV 5.5.1.8 apk via dex2jar
- one hand-written `JOGLGLES` compile stub

Full provenance: the jar's `.pom` description and `dev/spike/g0/NOTES.md` §3.

## Install into mavenLocal

The G0 gradle patch resolves the artifact from mavenLocal:

```sh
mvn org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file \
  -Dfile=dev/spike/g0/transplants/android-port-3.1.0.jar \
  -DpomFile=dev/spike/g0/transplants/android-port-3.1.0.pom
```

Derived exclusively from public sources (Android SDK + the public CIV apk) —
safe for the public repo under the no-GOTS-artifacts policy.
