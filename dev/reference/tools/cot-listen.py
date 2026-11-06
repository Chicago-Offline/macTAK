#!/usr/bin/env python3
"""Listen on an FTS CoT stream and print every incoming CoT event's uid/type.

Usage: python3 cot-listen.py [host] [port] [seconds] > /tmp/cot-listen.log
"""
import datetime
import socket
import sys


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8089
    seconds = int(sys.argv[3]) if len(sys.argv) > 3 else 15

    now = datetime.datetime.now(datetime.timezone.utc)
    stamp = now.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
    stale = (now + datetime.timedelta(minutes=5)).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
    listen_uid = "REF-LISTENER-" + now.strftime("%Y%m%dT%H%M%S")
    hello = (
        '<event version="2.0" uid="%s" type="a-n-G" how="m-g" '
        'time="%s" start="%s" stale="%s">'
        '<point lat="37.7755" lon="-122.4205" ce="10.0" hae="0.0" le="0.0"/>'
        "<detail>"
        '<contact callsign="REF-LISTENER" endpoint="tcp://%s:%s"/>'
        '<link uid="%s" production_server="tcp://%s:%s" type="a-n-G"/>'
        '<group name="Cyan" role="Team Member"/>'
        "</detail>"
        "</event>\n"
    ) % (listen_uid, stamp, stamp, stale, host, port, listen_uid, host, port)

    s = socket.create_connection((host, port), timeout=seconds)
    s.settimeout(seconds)
    try:
        s.sendall(hello.encode())
        buf = b""
        deadline = now + datetime.timedelta(seconds=seconds)
        print("# listening on %s:%s for %ss (handshake sent, uid=%s)" % (host, port, seconds, listen_uid), flush=True)
        while datetime.datetime.now(datetime.timezone.utc) < deadline:
            try:
                chunk = s.recv(65536)
            except socket.timeout:
                break
            if not chunk:
                break
            buf += chunk
            while b"</event>" in buf:
                event, buf = buf.split(b"</event>", 1)
                text = event.decode(errors="replace")
                ev_uid = type_ = cs = "?"
                lat = lon = ""
                for token in text.split("<"):
                    if token.startswith("event "):
                        for part in token.split():
                            if part.startswith("uid="):
                                ev_uid = part.split('"')[1]
                            if part.startswith("type="):
                                type_ = part.split('"')[1]
                    if token.startswith("point "):
                        for part in token.split():
                            if part.startswith("lat="):
                                lat = part.split('"')[1]
                            if part.startswith("lon="):
                                lon = part.split('"')[1]
                    if token.startswith("contact callsign="):
                        cs = token.split('"')[1]
                print("RECV %s | type=%s | callsign=%s | lat=%s lon=%s" % (ev_uid, type_, cs, lat, lon), flush=True)
        print("# done", flush=True)
    finally:
        s.close()


if __name__ == "__main__":
    main()