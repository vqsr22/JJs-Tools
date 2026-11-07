#!/usr/bin/env python3
"""
JJ's Tools stash sync server.

A small server for sharing Control Panel stashes between players, for a VPS or any machine
that stays on. It needs nothing but Python 3.8 or newer: no packages to install.

Everyone who uses the same token sees the same stashes. Stashes are kept as JSON files in the
data folder, one per stash, and merged container by container: the newest look at each
container wins, exactly as the Control Panel merges them itself, so nobody overwrites what
someone else saw.

Run it:
    python3 jjs-sync-server.py --token YOUR_SECRET_TOKEN
Options:
    --port 8787          port to listen on
    --host 0.0.0.0       address to listen on (127.0.0.1 if it sits behind Caddy or nginx)
    --data ./stash-data  where the stash files are kept
    --token              the shared secret (or set JJS_TOKEN instead)

The API, all with "Authorization: Bearer <token>":
    GET    /api/stashes               every stash's version, and recently deleted stashes
    GET    /api/stashes/<name>?since=N   what changed in a stash after version N
    POST   /api/stashes/<name>        send changed containers (and settings) to merge in
    DELETE /api/stashes/<name>        delete a stash for everyone
Bodies may be gzipped (Content-Encoding: gzip); replies are gzipped when asked for.
"""

import argparse
import gzip
import hmac
import json
import os
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlparse, parse_qs

MAX_BODY = 64 * 1024 * 1024
META_FIELDS = ("server", "dimension", "x", "y", "z", "located", "radius", "metaChanged")

LOCK = threading.Lock()
STASHES = {}   # lower-case name -> stash record
DELETED = {}   # lower-case name -> time deleted, in milliseconds
ARGS = None


def now_ms():
    return int(time.time() * 1000)


def safe_name(name):
    s = re.sub(r"[^A-Za-z0-9 _.@-]", "_", name).strip()
    return s or "stash"


def path_for(name):
    return os.path.join(ARGS.data, safe_name(name) + ".json")


def load():
    os.makedirs(ARGS.data, exist_ok=True)
    for f in os.listdir(ARGS.data):
        full = os.path.join(ARGS.data, f)
        if f == "_deleted.json":
            try:
                with open(full, encoding="utf-8") as fh:
                    DELETED.update(json.load(fh))
            except Exception as e:
                print("could not read", full, e, file=sys.stderr)
            continue
        if not f.endswith(".json"):
            continue
        try:
            with open(full, encoding="utf-8") as fh:
                rec = json.load(fh)
            if rec.get("name"):
                rec.setdefault("containers", {})
                rec.setdefault("revs", {})
                rec.setdefault("version", 0)
                rec.setdefault("metaRev", 0)
                STASHES[rec["name"].lower()] = rec
        except Exception as e:
            print("could not read", full, e, file=sys.stderr)
    print("loaded %d stashes from %s" % (len(STASHES), ARGS.data))


def write(rec):
    tmp = path_for(rec["name"]) + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(rec, fh, separators=(",", ":"))
    os.replace(tmp, path_for(rec["name"]))


def write_deleted():
    tmp = os.path.join(ARGS.data, "_deleted.json.tmp")
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(DELETED, fh)
    os.replace(tmp, os.path.join(ARGS.data, "_deleted.json"))


def merge(name, body):
    """Merges what a player sent into the stash. Returns the stash's new version."""
    key = name.lower()
    rec = STASHES.get(key)
    if rec is None:
        rec = {"name": name, "containers": {}, "revs": {}, "version": 0, "metaRev": 0, "metaChanged": 0}
        STASHES[key] = rec
    # Sending to a deleted stash brings it back.
    DELETED.pop(key, None)

    changed = False
    meta = body.get("meta")
    if isinstance(meta, dict) and meta.get("metaChanged", 0) > rec.get("metaChanged", 0):
        for f in META_FIELDS:
            if f in meta:
                rec[f] = meta[f]
        rec["version"] += 1
        rec["metaRev"] = rec["version"]
        changed = True

    for ck, theirs in (body.get("containers") or {}).items():
        if not isinstance(theirs, dict):
            continue
        mine = rec["containers"].get(ck)
        if mine is None or theirs.get("updated", 0) > mine.get("updated", 0):
            rec["containers"][ck] = theirs
            rec["version"] += 1
            rec["revs"][ck] = rec["version"]
            changed = True

    if changed:
        write(rec)
    return rec["version"]


def since(rec, n):
    """What changed in a stash after version n."""
    out = {"version": rec["version"], "name": rec["name"], "containers": {}}
    if rec.get("metaRev", 0) > n:
        out["meta"] = {f: rec[f] for f in META_FIELDS if f in rec}
    for ck, rev in rec["revs"].items():
        if rev > n and ck in rec["containers"]:
            out["containers"][ck] = rec["containers"][ck]
    return out


class Handler(BaseHTTPRequestHandler):
    server_version = "JJsToolsSync/1"

    def log_message(self, fmt, *args):
        if ARGS.verbose:
            sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def authorised(self):
        given = self.headers.get("Authorization", "")
        expected = "Bearer " + ARGS.token
        if hmac.compare_digest(given.encode(), expected.encode()):
            return True
        self.reply(401, {"error": "bad token"})
        return False

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length > MAX_BODY:
            raise ValueError("too big")
        raw = self.rfile.read(length) if length else b"{}"
        if self.headers.get("Content-Encoding", "") == "gzip":
            raw = gzip.decompress(raw)
        return json.loads(raw.decode("utf-8") or "{}")

    def reply(self, code, obj):
        data = json.dumps(obj, separators=(",", ":")).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        if "gzip" in self.headers.get("Accept-Encoding", "") and len(data) > 512:
            data = gzip.compress(data, 6)
            self.send_header("Content-Encoding", "gzip")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def stash_name(self):
        parts = urlparse(self.path).path.split("/")
        # /api/stashes/<name>
        if len(parts) >= 4 and parts[1] == "api" and parts[2] == "stashes" and parts[3]:
            return unquote(parts[3])
        return None

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/health":
            return self.reply(200, {"ok": True})
        if not self.authorised():
            return
        if url.path.rstrip("/") == "/api/stashes":
            with LOCK:
                return self.reply(200, {
                    "stashes": {r["name"]: r["version"] for r in STASHES.values()},
                    "deleted": DELETED,
                })
        name = self.stash_name()
        if name is None:
            return self.reply(404, {"error": "not found"})
        n = int((parse_qs(url.query).get("since") or ["0"])[0])
        with LOCK:
            rec = STASHES.get(name.lower())
            if rec is None:
                return self.reply(404, {"error": "no such stash"})
            return self.reply(200, since(rec, n))

    def do_POST(self):
        if not self.authorised():
            return
        name = self.stash_name()
        if name is None:
            return self.reply(404, {"error": "not found"})
        try:
            body = self.body()
        except Exception as e:
            return self.reply(400, {"error": "bad body: %s" % e})
        with LOCK:
            version = merge(name, body)
            write_deleted()
        self.reply(200, {"version": version})

    def do_DELETE(self):
        if not self.authorised():
            return
        name = self.stash_name()
        if name is None:
            return self.reply(404, {"error": "not found"})
        with LOCK:
            rec = STASHES.pop(name.lower(), None)
            DELETED[name.lower()] = now_ms()
            if rec is not None:
                try:
                    os.remove(path_for(rec["name"]))
                except FileNotFoundError:
                    pass
            write_deleted()
        self.reply(200, {"ok": True})


def main():
    global ARGS
    p = argparse.ArgumentParser(description="JJ's Tools stash sync server")
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=8787)
    p.add_argument("--data", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "stash-data"))
    p.add_argument("--token", default=os.environ.get("JJS_TOKEN", ""))
    p.add_argument("--verbose", action="store_true")
    ARGS = p.parse_args()
    if len(ARGS.token) < 16:
        sys.exit("Give a token of at least 16 characters with --token or JJS_TOKEN.")
    load()
    httpd = ThreadingHTTPServer((ARGS.host, ARGS.port), Handler)
    print("JJ's Tools sync server on %s:%d" % (ARGS.host, ARGS.port))
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
