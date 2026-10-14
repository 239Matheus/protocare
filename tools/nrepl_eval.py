#!/usr/bin/env python3
"""Minimal nREPL client (bencode over TCP), no dependencies.

Used inside the devenv container to talk to shadow-cljs (port 3447) or the
backend (port 6064) when file watchers do not fire (Docker Desktop bind
mounts on Windows do not forward file events).

    python3 nrepl_eval.py 3447 "(shadow.cljs.devtools.api/watch-compile! :main)"
"""
import socket
import sys


def bencode(obj):
    if isinstance(obj, int):
        return b"i%de" % obj
    if isinstance(obj, str):
        obj = obj.encode("utf-8")
    if isinstance(obj, bytes):
        return b"%d:%s" % (len(obj), obj)
    if isinstance(obj, list):
        return b"l" + b"".join(bencode(x) for x in obj) + b"e"
    if isinstance(obj, dict):
        items = sorted(obj.items())
        return b"d" + b"".join(bencode(k) + bencode(v) for k, v in items) + b"e"
    raise TypeError(type(obj))


def bdecode(data, i=0):
    c = data[i:i + 1]
    if c == b"i":
        j = data.index(b"e", i)
        return int(data[i + 1:j]), j + 1
    if c == b"l":
        i += 1
        out = []
        while data[i:i + 1] != b"e":
            v, i = bdecode(data, i)
            out.append(v)
        return out, i + 1
    if c == b"d":
        i += 1
        out = {}
        while data[i:i + 1] != b"e":
            k, i = bdecode(data, i)
            v, i = bdecode(data, i)
            out[k.decode()] = v
        return out, i + 1
    j = data.index(b":", i)
    n = int(data[i:j])
    return data[j + 1:j + 1 + n], j + 1 + n


def main():
    port = int(sys.argv[1])
    code = sys.argv[2]
    sock = socket.create_connection(("127.0.0.1", port), timeout=900)
    sock.sendall(bencode({"op": "eval", "code": code, "id": "1"}))
    buf = b""
    done = False
    while not done:
        chunk = sock.recv(65536)
        if not chunk:
            break
        buf += chunk
        while buf:
            try:
                msg, used = bdecode(buf)
            except (ValueError, IndexError):
                break
            buf = buf[used:]
            for key in ("out", "err", "value", "ex"):
                if key in msg:
                    sys.stdout.write("%s: %s\n" % (key, msg[key].decode("utf-8", "replace").rstrip()))
            status = [s.decode() for s in msg.get("status", [])]
            if "done" in status:
                done = True
    sock.close()


if __name__ == "__main__":
    main()
