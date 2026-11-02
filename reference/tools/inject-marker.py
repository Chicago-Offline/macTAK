#!/usr/bin/env python3
"""Inject a CoT marker into FreeTAKServer to verify FTS->client delivery.

Usage: python3 inject-marker.py [host] [port] [lat] [lon] [callsign]
"""
import datetime
import socket
import sys

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8089
LAT = sys.argv[3] if len(sys.argv) > 3 else "37.7749"
LON = sys.argv[4] if len(sys.argv) > 4 else "-122.4194"
CALLSIGN = sys.argv[5] if len(sys.argv) > 5 else "REF-FTS-PROBE"
HOLD = float(sys.argv[6]) if len(sys.argv) > 6 else 0.0
TYPE = sys.argv[7] if len(sys.argv) > 7 else "a-n-G"

now = datetime.datetime.now(datetime.timezone.utc)
stamp = now.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
stale = (now + datetime.timedelta(minutes=5)).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
uid = "REF-MACOSPORT-" + now.strftime("%Y%m%dT%H%M%S")

event = (
    '<event version="2.0" uid="%s" type="%s" how="m-g" '
    'time="%s" start="%s" stale="%s">'
    '<point lat="%s" lon="%s" ce="10.0" hae="0.0" le="0.0"/>'
    "<detail>"
    '<contact callsign="%s" endpoint="tcp://%s:%s"/>'
    '<link uid="%s" production_server="tcp://%s:%s" type="%s"/>'
    '<group name="Cyan" role="Team Member"/>'
    '<precisionlocation geopossrc="GPS"/>'
    "<status readiness=\"true\"/>"
    "<track course=\"0.0\" speed=\"0.0\"/>"
    "</detail>"
    "</event>\n"
) % (uid, TYPE, stamp, stamp, stale, LAT, LON, CALLSIGN, HOST, PORT, uid, HOST, PORT, TYPE)

s = socket.create_connection((HOST, PORT), timeout=10)
s.sendall(event.encode())
print("sent uid=%s callsign=%s to %s:%s" % (uid, CALLSIGN, HOST, PORT))
if HOLD > 0:
    import time
    time.sleep(HOLD)
s.close()