#!/usr/bin/env bash
# Build a synthetic ATAK data package for the --datapackage canary
# (test/datapackage.sh): a real self-signed PKCS#12 pair plus the
# preference.pref the importer reads — cot_streams address0 (host:port:ssl),
# location_units callsign, plugin_firecrypt CAFile/ClientFile/Password.
#
# Usage: make-datapackage.sh OUT.dp [CALLSIGN]
#
# Not a real OpenTAKServer package — the importer's key names are verified
# against this fixture only; live validation against a real package is the
# documented follow-up (dev/g1/NOTES.md, G1.5).
set -euo pipefail

command -v openssl >/dev/null 2>&1 || {
    echo "make-datapackage: openssl not found in PATH — it builds the fixture certs" \
        "(any OpenSSL; macOS's LibreSSL is fine; e.g. brew install openssl)" >&2
    exit 1
}
command -v zip >/dev/null 2>&1 || {
    echo "make-datapackage: zip not found in PATH — it packs the .dp" >&2
    exit 1
}

[[ $# -ge 1 && $# -le 2 ]] || {
    echo "usage: make-datapackage.sh OUT.dp [CALLSIGN]" >&2
    exit 2
}
OUT_DIR="$(cd "$(dirname "$1")" && pwd)"
OUT="$OUT_DIR/$(basename "$1")"
CALLSIGN="${2:-DP-UNIT}"

WORK="$(mktemp -d /tmp/mactak-dp-fixture.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

CERTS="$WORK/certs"
mkdir -p "$CERTS"

# Real PKCS#12s (self-signed, password atakatak) so the fixture is honest
# bytes; the canary checks sizes, not a TLS handshake. Client and CA both
# self-sign via plain `req -x509`: LibreSSL (/usr/bin/openssl on macOS) has
# no -CA/-CAkey in req, and no assertion here needs a signed chain.
openssl req -x509 -newkey rsa:2048 -keyout "$WORK/ca.key" -out "$WORK/ca.crt" \
    -days 30 -nodes -subj "/CN=mactak-dp-fixture-ca"
openssl req -x509 -newkey rsa:2048 -keyout "$WORK/client.key" \
    -out "$WORK/client.crt" -days 30 -nodes \
    -subj "/CN=mactak-dp-fixture-client"
openssl pkcs12 -export -in "$WORK/client.crt" -inkey "$WORK/client.key" \
    -out "$CERTS/client.p12" -passout pass:atakatak -name client
openssl pkcs12 -export -in "$WORK/ca.crt" -inkey "$WORK/ca.key" \
    -out "$CERTS/truststore.p12" -passout pass:atakatak -name truststore

cat > "$WORK/preference.pref" <<XML
<preferences>
  <preference version="1" name="cot_streams">
    <entry key="count" class="class java.lang.Integer">1</entry>
    <entry key="description0" class="class java.lang.String">mactak datapackage fixture</entry>
    <entry key="address0" class="class java.lang.String">dp.example.test:8089:ssl</entry>
    <entry key="username0" class="class java.lang.String">dp-user</entry>
    <entry key="password0" class="class java.lang.String">dp-pass</entry>
  </preference>
  <preference version="1" name="location_units">
    <entry key="callsign" class="class java.lang.String">$CALLSIGN</entry>
  </preference>
  <preference version="1" name="plugin_firecrypt">
    <entry key="CAFile" class="class java.lang.String">certs/truststore.p12</entry>
    <entry key="ClientFile" class="class java.lang.String">certs/client.p12</entry>
    <entry key="Password" class="class java.lang.String">dp-cert-pass</entry>
  </preference>
</preferences>
XML

rm -f "$OUT"
(cd "$WORK" && zip -q "$OUT" preference.pref certs/client.p12 certs/truststore.p12)
echo "fixture: $OUT (callsign $CALLSIGN)"