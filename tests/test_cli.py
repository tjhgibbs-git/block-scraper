"""CLI tests against tests/fake_mod.py. Run: python3 -m unittest discover tests"""

import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLI = ROOT / "block-scraper"
FAKE = Path(__file__).resolve().parent / "fake_mod.py"


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def run(*args, port, cwd=None, env=None):
    e = dict(os.environ, BLOCK_SCRAPER_PORT=str(port), DISPLAY=":99")
    e.update(env or {})
    return subprocess.run([sys.executable, str(CLI), *args], capture_output=True, text=True, cwd=cwd, env=e, timeout=60)


def wait_for(port):
    for _ in range(100):
        try:
            socket.create_connection(("127.0.0.1", port), timeout=1).close()
            return
        except OSError:
            time.sleep(0.05)
    raise AssertionError("fake mod did not start")


class AgainstFakeMod(unittest.TestCase):
    def setUp(self):
        self.port = free_port()
        self.proc = subprocess.Popen([sys.executable, str(FAKE), f"-Pblock-scraper.port={self.port}"])
        wait_for(self.port)
        self.tmp = Path(tempfile.mkdtemp())

    def tearDown(self):
        self.proc.kill()
        self.proc.wait()

    def cli(self, *args):
        return run(*args, port=self.port, cwd=self.tmp)

    def test_status_human_and_json(self):
        r = self.cli("status")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("minecraft: 26.3", r.stdout)
        r = self.cli("status", "--json")
        self.assertEqual(json.loads(r.stdout)["screen"], "net.minecraft.client.gui.screens.TitleScreen")

    def test_open_passes_args(self):
        r = self.cli("--json", "open", "sample-config", "--arg", "name=Steve", "--arg", "x=a=b")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(json.loads(r.stdout)["args"], {"name": "Steve", "x": "a=b"})

    def test_error_reply_exits_1_with_message(self):
        r = self.cli("open", "nope")
        self.assertEqual(r.returncode, 1)
        self.assertIn("unknown screen 'nope'", r.stderr)

    def test_usage_errors_exit_2(self):
        self.assertEqual(self.cli("size", "big").returncode, 2)
        self.assertEqual(self.cli("click").returncode, 2)

    def test_shot_resolves_relative_path_and_prints_it(self):
        r = self.cli("shot", "out/a.png")
        self.assertEqual(r.returncode, 0, r.stderr)
        path = str(self.tmp / "out" / "a.png")
        self.assertTrue(r.stdout.startswith(path + " 128x72"), r.stdout)
        self.assertTrue(Path(path).exists())

    def test_click_changes_second_shot(self):
        self.cli("shot", "a.png")
        self.cli("click", "--text", "Clicked 0 times")
        self.cli("shot", "b.png")
        same = self.cli("diff", "a.png", "a.png")
        self.assertEqual(same.returncode, 0, same.stderr)
        changed = self.cli("diff", "a.png", "b.png")
        self.assertEqual(changed.returncode, 1)
        self.assertIn("images differ", changed.stderr)

    def test_widgets_table(self):
        r = self.cli("widgets")
        self.assertIn("Button", r.stdout)
        self.assertIn("value='Steve' [focused]", r.stdout)

    def test_stats_and_blank_check(self):
        self.cli("shot", "a.png")
        r = self.cli("--json", "stats", "a.png")
        stats = json.loads(r.stdout)
        self.assertEqual((stats["width"], stats["height"], stats["stddev"]), (128, 72, 0.0))
        r = self.cli("stats", "a.png", "--min-stddev", "5")
        self.assertEqual(r.returncode, 1)
        self.assertIn("looks blank", r.stderr)

    def test_reload_without_build(self):
        r = self.cli("reload", "--no-build")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("redefined: dev.blockscraper.sample.SampleConfigScreen", r.stdout)

    def test_reload_reports_build_failure(self):
        r = self.cli("reload", "--build-cmd", "echo 'error: ; expected' && false")
        self.assertEqual(r.returncode, 1)
        self.assertIn("build failed", r.stderr)
        self.assertIn("; expected", r.stderr)

    def test_run_script_stops_at_first_error(self):
        script = self.tmp / "s.txt"
        script.write_text("# comment\nopen sample-config\n\nshot one.png\nopen missing\nshot two.png\n")
        r = self.cli("run", str(script))
        self.assertEqual(r.returncode, 1)
        self.assertIn("$ shot one.png", r.stdout)
        self.assertIn(":5: open missing: unknown screen 'missing'", r.stderr)
        self.assertTrue((self.tmp / "one.png").exists())
        self.assertFalse((self.tmp / "two.png").exists())


class NotRunning(unittest.TestCase):
    def test_exit_3_when_no_client(self):
        r = run("status", port=free_port())
        self.assertEqual(r.returncode, 3)
        self.assertIn("block-scraper start", r.stderr)


class Lifecycle(unittest.TestCase):
    """start/stop with a fake gradlew that runs the fake mod."""

    def setUp(self):
        self.port = free_port()
        self.project = Path(tempfile.mkdtemp())
        (self.project / "settings.gradle").write_text("")
        gradlew = self.project / "gradlew"
        gradlew.write_text(f"#!/bin/sh\nexec {sys.executable} {FAKE} \"$@\"\n")
        gradlew.chmod(0o755)

    def cli(self, *args):
        return run(*args, port=self.port, cwd=self.project)

    def tearDown(self):
        self.cli("stop")

    def test_start_twice_then_stop(self):
        r = self.cli("start", "--timeout", "30")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("minecraft: 26.3", r.stdout)
        state = json.loads((self.project / ".block-scraper" / "client.json").read_text())
        self.assertEqual(state["port"], self.port)
        self.assertIsNotNone(state["gamePid"])

        again = self.cli("start", "--timeout", "30")
        self.assertEqual(again.returncode, 1)
        self.assertIn("already running", again.stderr)

        r = self.cli("stop")
        self.assertEqual((r.returncode, r.stdout.strip()), (0, "stopped"))
        self.assertFalse((self.project / ".block-scraper" / "client.json").exists())
        self.assertEqual(self.cli("status").returncode, 3)
        self.assertEqual(self.cli("stop").stdout.strip(), "not running")

    def test_start_reports_early_exit_with_log(self):
        (self.project / "gradlew").write_text("#!/bin/sh\necho 'Could not resolve net.fabricmc:fabric-loom'\nexit 1\n")
        r = self.cli("start", "--timeout", "30")
        self.assertEqual(r.returncode, 1)
        self.assertIn("exited with code 1", r.stderr)
        self.assertIn("Could not resolve", r.stderr)


if __name__ == "__main__":
    unittest.main()
