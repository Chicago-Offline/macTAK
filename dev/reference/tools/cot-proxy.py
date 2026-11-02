#!/usr/bin/env python3
"""LAN bridge for VMPal's localhost-only container port forwarder.

VMPal publishes container ports on 127.0.0.1 only, so the bridged WinTAK VM
(<vm-ip>) cannot reach FTS via the Mac's LAN address (<mac-lan-ip>). This
forwards <mac-lan-ip>:8089 -> 127.0.0.1:8089 (FreeTAKServer CoT TCP).

Usage: python3 cot-proxy.py <mac-lan-ip> [local_port] [target_port]
"""
import socket
import sys
import threading

if len(sys.argv) < 2:
    sys.exit("usage: cot-proxy.py <mac-lan-ip> [local_port] [target_port]\n"
             "Binds <mac-lan-ip>:<local_port> -> 127.0.0.1:<target_port>")

LOCAL_BIND = (sys.argv[1],
              int(sys.argv[2]) if len(sys.argv) > 2 else 8089)
TARGET = ("127.0.0.1",
          int(sys.argv[3]) if len(sys.argv) > 3 else 8089)


def pipe(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def handle(client):
    try:
        upstream = socket.create_connection(TARGET, timeout=10)
    except OSError as exc:
        print("upstream connect failed:", exc, flush=True)
        client.close()
        return
    threading.Thread(target=pipe, args=(client, upstream), daemon=True).start()
    pipe(upstream, client)
    client.close()
    upstream.close()


def main():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(LOCAL_BIND)
    srv.listen(64)
    print("cot-proxy listening on %s -> %s" % (LOCAL_BIND, TARGET), flush=True)
    while True:
        client, addr = srv.accept()
        threading.Thread(target=handle, args=(client,), daemon=True).start()


if __name__ == "__main__":
    main()