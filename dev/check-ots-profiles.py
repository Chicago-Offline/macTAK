#!/usr/bin/env python3
"""Fail when OpenTAKServer's certificate profiles drift from the pinned copy.

dev/g1/headless/test/tls-purpose.sh proves commo accepts an OpenTAKServer
server certificate and refuses a user certificate, using the profiles
pinned in dev/g1/headless/test/fixtures/ots-cert-profiles.cnf. That proof
is only as current as the copy. This compares the copy with the [ client ]
and [ server ] sections of upstream's opentakserver/ca_config.py: every
occurrence (the file holds two templates) must carry exactly the pinned
active lines. It only reads the file as text; nothing of OpenTAKServer runs.

usage: check-ots-profiles.py [--file CA_CONFIG_PY]
       (no --file: download upstream master)
exit:  0 match, 1 drift, 2 usage or download/parse failure
"""
import pathlib
import re
import sys
import urllib.request

UPSTREAM = ("https://raw.githubusercontent.com/brian7704/OpenTAKServer/"
            "master/opentakserver/ca_config.py")
PINNED = (pathlib.Path(__file__).resolve().parent
          / "g1/headless/test/fixtures/ots-cert-profiles.cnf")
SECTIONS = ("client", "server")
# commo never checks the hostname, so the server template's SAN is not pinned.
IGNORED_KEYS = ("subjectAltName",)
EXIT_OK, EXIT_DRIFT, EXIT_ERROR = 0, 1, 2


def normalize(line):
    """'key = a, b' and 'key=a,b' compare equal."""
    return re.sub(r"\s+", "", line)


def profiles(text):
    """{section: [active-line list, one per occurrence]} for SECTIONS."""
    found = {name: [] for name in SECTIONS}
    current = None
    for raw in text.splitlines():
        line = raw.strip()
        header = re.match(r"\[\s*(\w+)\s*\]", line)
        if header:
            current = None
            if header.group(1) in found:
                current = []
                found[header.group(1)].append(current)
            continue
        if current is None:
            continue
        # The python string holding a template can end mid-section, and on
        # a comment line: keep what precedes the quotes, then close.
        ends_template = '"""' in line
        line = line.split('"""')[0].strip()
        is_active = (line and not line.startswith("#")
                     and line.split("=")[0].strip() not in IGNORED_KEYS)
        if is_active:
            current.append(normalize(line))
        if ends_template:
            current = None
    return found


def main(argv):
    if len(argv) == 2 and argv[0] == "--file":
        upstream_text = pathlib.Path(argv[1]).read_text()
        source = argv[1]
    elif not argv:
        try:
            with urllib.request.urlopen(UPSTREAM, timeout=30) as reply:
                upstream_text = reply.read().decode("utf-8")
        except OSError as e:
            print(f"[check-ots-profiles] cannot download {UPSTREAM}: {e}",
                  file=sys.stderr)
            return EXIT_ERROR
        source = UPSTREAM
    else:
        print(__doc__, file=sys.stderr)
        return EXIT_ERROR

    pinned = profiles(PINNED.read_text())
    upstream = profiles(upstream_text)
    drift = False
    for name in SECTIONS:
        if len(pinned[name]) != 1:
            print(f"[check-ots-profiles] {PINNED.name}: expected one "
                  f"[ {name} ] section, found {len(pinned[name])}",
                  file=sys.stderr)
            return EXIT_ERROR
        if not upstream[name]:
            print(f"[check-ots-profiles] no [ {name} ] section in {source}",
                  file=sys.stderr)
            return EXIT_ERROR
        want = pinned[name][0]
        for n, got in enumerate(upstream[name], 1):
            if got != want:
                drift = True
                print(f"[check-ots-profiles] DRIFT [ {name} ] "
                      f"(occurrence {n} of {len(upstream[name])}):")
                print(f"    pinned:   {want}")
                print(f"    upstream: {got}")
    if drift:
        print("[check-ots-profiles] FAIL: update "
              "dev/g1/headless/test/fixtures/ots-cert-profiles.cnf and "
              "re-run test/tls-purpose.sh (dev/g1/NOTES.md §G1.14)")
        return EXIT_DRIFT
    print("[check-ots-profiles] OK: upstream [ client ] and [ server ] "
          "match the pinned profiles")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
