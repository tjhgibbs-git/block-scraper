#!/usr/bin/env python3
"""A stand-in for the block-scraper mod: speaks the same protocol with canned behaviour, for CLI tests."""

import json
import os
import socket
import struct
import sys
import threading
import zlib


def write_png(path, width, height, colour):
    raw = b"".join(b"\x00" + bytes(colour) * width for _ in range(height))

    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


class FakeMod:
    def __init__(self):
        self.screen = "net.minecraft.client.gui.screens.TitleScreen"
        self.last = None
        self.size = (1280, 720)
        self.clicks = 0
        self.quit = False

    def handle(self, cmd, a):
        if cmd == "ping":
            return "pong"
        if cmd == "status":
            return {"minecraft": "26.3", "pid": os.getpid(), "screen": self.screen,
                    "width": self.size[0], "height": self.size[1]}
        if cmd == "open":
            if a["id"] != "sample-config":
                raise ValueError(f"unknown screen '{a['id']}'")
            self.screen, self.last = "dev.blockscraper.sample.SampleConfigScreen", a
            return {"screen": self.screen, "args": a.get("args", {})}
        if cmd == "size":
            self.size = (a["width"], a["height"])
            return {"width": a["width"], "height": a["height"]}
        if cmd == "click":
            self.clicks += 1
            return {"widget": None, "screen": self.screen}
        if cmd == "widgets":
            return {"screen": self.screen, "guiWidth": 427, "guiHeight": 240, "widgets": [
                {"class": "net.minecraft.client.gui.components.Button", "x": 113, "y": 120, "w": 200, "h": 20,
                 "text": f"Clicked {self.clicks} times", "active": True, "visible": True, "focused": False, "hovered": False},
                {"class": "net.minecraft.client.gui.components.EditBox", "x": 113, "y": 70, "w": 200, "h": 20,
                 "text": "Name", "value": "Steve", "active": True, "visible": True, "focused": True, "hovered": False}]}
        if cmd == "shot":
            # A gradient-free but non-uniform image: colour depends on the click count.
            w, h = a.get("width") or self.size[0], a.get("height") or self.size[1]
            os.makedirs(os.path.dirname(a["path"]), exist_ok=True)
            write_png(a["path"], w // 10, h // 10, (40 + self.clicks * 50, 80, 120, 255))
            return {"path": a["path"], "width": w // 10, "height": h // 10, "screen": self.screen, "millis": 5}
        if cmd in ("hover", "mouse", "wait", "key", "type"):
            return {"screen": self.screen}
        if cmd == "command":
            return a["text"]
        if cmd == "reload":
            return {"redefined": ["dev.blockscraper.sample.SampleConfigScreen"], "pending": [], "resources": a["resources"]}
        if cmd == "quit":
            self.quit = True
            return "bye"
        raise ValueError(f"unknown command '{cmd}'")


def serve(port):
    mod = FakeMod()
    server = socket.create_server(("127.0.0.1", port))
    lock = threading.Lock()

    def conn(c):
        with c, c.makefile("rw") as f:
            for line in f:
                req = json.loads(line)
                try:
                    with lock:
                        reply = {"id": req["id"], "ok": True, "result": mod.handle(req["cmd"], req.get("args", {}))}
                except Exception as e:  # noqa: BLE001 - mirrors the mod's error replies
                    reply = {"id": req["id"], "ok": False, "error": str(e)}
                f.write(json.dumps(reply) + "\n")
                f.flush()
                if mod.quit:
                    os._exit(0)

    while True:
        c, _ = server.accept()
        threading.Thread(target=conn, args=(c,), daemon=True).start()


if __name__ == "__main__":
    port = 25599
    for arg in sys.argv[1:]:
        if arg.startswith("-Pblockscraper.port="):
            port = int(arg.split("=", 1)[1])
    serve(port)
