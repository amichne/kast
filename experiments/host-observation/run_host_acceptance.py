#!/usr/bin/env python3
"""Opt-in real-host checks. Invokes IDEA's native ideScript command, which may launch IDEA."""
import argparse
import base64
import hashlib
import json
import subprocess
import tempfile
import time
from pathlib import Path
import controller as c


def wait_for(label, condition, seconds=30):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = condition()
        if result:
            return result
        time.sleep(0.05)
    raise AssertionError(f"Unmet condition: {label}")


class Acceptance:
    def __init__(self, idea):
        assert c.read_json(idea / "Resources/product-info.json", 1024 * 1024)["buildNumber"] == c.BUILD, "Unsupported installation"
        self.launcher = idea / "MacOS/idea"
        self.root = Path(tempfile.mkdtemp(prefix="kast-host-acceptance-")).resolve()
        self.project = self.root / "fixture"
        (self.project / "src").mkdir(parents=True)
        self.serial = 0
        self.sessions = []
        self.proofs = []
        self.digest = hashlib.sha256((c.ROOT / "observer.kts").read_bytes()).hexdigest()
        self.completed = False
        print(f"Evidence: {self.root}", flush=True)

    def native(self, script):
        subprocess.run([str(self.launcher), "ideScript", str(script)], check=True, timeout=90,
                       stdout=subprocess.DEVNULL)

    def action(self, action, project=None, prefix="kast-observation-", rounds=0):
        self.serial += 1
        directory = self.root / f"action-{self.serial}"
        directory.mkdir(mode=0o700)
        path = directory / "input.json"
        path.write_text(json.dumps(dict(action=action, project=str(project or self.project), threadPrefix=prefix, rounds=rounds)))
        source = (c.ROOT / "host-action.kts.template").read_text().replace("@INPUT_BASE64@", base64.b64encode(str(path).encode()).decode())
        script = directory / "action.kts"; script.write_text(source)
        self.native(script)
        return c.read_json(directory / "result.json", c.MAX_RECEIPT)

    def invoke(self, name, mode=None, session_id=None):
        directory = self.root / name
        prepared = c.prepare(directory, None if name == "preflight" else self.root / "preflight", str(self.project))
        assert isinstance(prepared, c.Prepared), prepared
        if session_id is not None:
            request = c.read_json(directory / "request.json", c.MAX_REQUEST)
            request["sessionId"] = session_id
            (directory / "request.json").write_text(json.dumps(request))
        if name != "preflight":
            self.sessions.append(directory)
        if mode:
            (directory / "test.json").write_text(json.dumps(dict(mode=mode)))
            source = (directory / "console.kts").read_text()
            hooks = json.dumps((c.ROOT / "host-hooks.kts").read_text())
            call = '\\nKastHostObservation.preflight(bindings)\\n'
            replacement = '\\nKastHostObservation.preflight(bindings, HostAcceptanceHooks(java.nio.file.Path.of(bindings.getValue(\\"kast.observation.request\\") as String).parent))\\n'
            source = source.replace('String(bytes, Charsets.UTF_8) + ', 'String(bytes, Charsets.UTF_8) + ' + hooks + ' + ')
            source = source.replace(call, replacement)
            (directory / "console.kts").write_text(source)
        self.native(directory / "console.kts")
        evaluation = c.read_json(directory / "evaluation.json", c.MAX_RECEIPT)
        assert evaluation["artifactSha256"] == self.digest == hashlib.sha256((c.ROOT / "observer.kts").read_bytes()).hexdigest()
        assert evaluation["outcome"] not in ("EXECUTION_UNCONFIRMED", "RESOURCE_EXHAUSTED"), evaluation
        return directory, c.verify_directory(directory)

    def terminal(self, directory):
        request = c.read_json(directory / "request.json", c.MAX_REQUEST)
        location = c.session_directory(request)
        def done():
            try:
                value = c.read_json(location / "status.json", c.MAX_RECEIPT)
                return value if value["state"] == "DETACHED" and not (location.parent.parent / "admission").exists() else None
            except FileNotFoundError:
                return None
        value = wait_for("original owner detached and released admission", done)
        assert value["callbackCount"] == 0 and value["stopSignal"]["type"] == "REQUESTED"
        (directory / "terminal.json").write_text(json.dumps(value, indent=2) + "\n")
        summary = dict(type="JOURNAL_BOUNDS", segments=[dict(name=p.name, bytes=p.stat().st_size) for p in sorted(location.glob("journal-*.jsonl"))])
        (directory / "journal-bounds.json").write_text(json.dumps(summary) + "\n")
        return location, value

    def trigger(self, wave):
        for i in range(150):
            (self.project / "src" / f"Wave{i}.java").write_text(f"class Wave{i} {{ int value = {wave}; }}\n")
        return self.action("REFRESH")

    def records(self, directory):
        location = c.session_directory(c.read_json(directory / "request.json", c.MAX_REQUEST))
        records = []
        for path in location.glob("journal-*.jsonl"):
            for line in path.read_bytes().splitlines(keepends=True):
                if line.endswith(b"\n"):
                    records.append(json.loads(line))
        return records

    def record(self, case):
        self.proofs.append(case)
        print(f"PASS: {case}", flush=True)

    def run(self):
        try:
            initial = self.action("OPEN")
            _, preflight = self.invoke("preflight")
            assert isinstance(preflight, c.Observed), preflight
            first, result = self.invoke("first")
            assert isinstance(result, c.Observed), result
            foreign = self.root / "foreign"; (foreign / "src").mkdir(parents=True)
            self.action("OPEN", foreign)
            try:
                duplicate, denied = self.invoke("duplicate")
                assert denied == c.Rejected(c.Reason.OWNERSHIP_CONFLICT), denied
                self.trigger(1)
                wait_for("post-evaluation VFS event", lambda: any(r["event"]["type"] == "VFS" for r in self.records(first)))
                wait_for("real indexing event", lambda: any(r["event"].get("activity") == "INDEX_FINISHED" for r in self.records(first)))
                extra = self.root / "fixture-external"; extra.mkdir()
                (extra / "Additional.java").write_text("class Additional {}\n")
                self.action("ADD_SOURCE")
                wait_for("real scanning event", lambda: any(r["event"].get("activity") == "SCAN_FINISHED" for r in self.records(first)))
                self.record("fixture source-root change emits real scanning history")
                (foreign / "src" / "Foreign.java").write_text("class Foreign {}\n")
                self.action("REFRESH", foreign)
                assert isinstance(c.stop_session(first), c.Observed)
                first_location, _ = self.terminal(first)
                assert all(not p.startswith(str(foreign)) for r in self.records(first) for p in r["event"].get("paths", []))
                self.record("survives evaluation; exact target despite foreign focus; duplicate fails closed; VFS/indexing; original-owner stop")
            finally:
                self.action("CLOSE", foreign)
            old_bytes = {p.name: p.read_bytes() for p in first_location.glob("journal-*.jsonl")}
            old_evidence = {p.name: p.read_bytes() for p in first_location.iterdir()}
            _, replay = self.invoke("retired-session-replay", session_id=c.read_json(first / "request.json", c.MAX_REQUEST)["sessionId"])
            assert replay == c.Rejected(c.Reason.REQUEST_REJECTED), replay
            assert old_evidence == {p.name: p.read_bytes() for p in first_location.iterdir()}
            self.record("retired-session replay rejects before acquisition and preserves original evidence")
            second, result = self.invoke("replacement")
            assert isinstance(result, c.Observed), result
            self.trigger(2)
            wait_for("replacement event", lambda: bool(self.records(second)))
            self.action("BURST", rounds=1400)
            wait_for("journal rotated", lambda: "JOURNAL_ROTATED" in c.read_status(second).receipt["coverage"]["reasons"])
            assert isinstance(c.stop_session(second), c.Observed)
            second_location, _ = self.terminal(second)
            segments = list(second_location.glob("journal-*.jsonl"))
            assert len(segments) == 4 and all(p.stat().st_size <= 1024 * 1024 for p in segments)
            self.record("real VFS burst rotates journal within four 1 MiB segments and records coverage loss")
            assert old_bytes == {p.name: p.read_bytes() for p in first_location.glob("journal-*.jsonl")}
            continuity = self.action("INSPECT")
            assert (continuity["pid"], continuity["identity"]) == (initial["pid"], initial["identity"])
            assert continuity["threads"] == 0
            self.record("replacement preserves host PID/project object and old journal; no observer threads after stop")
            for resource in ("STORAGE", "DISPOSABLE", "VFS", "INDEXING", "DUMB", "WRITER", "CONTROL"):
                directory, rejected = self.invoke(f"fail-{resource.lower()}", "FAIL_" + resource)
                assert rejected == c.Rejected(c.Reason.RETIREMENT_UNCONFIRMED), rejected
                self.terminal(directory)
                self.record("rollback after " + resource)
            for mode in ("WRITER", "CALLBACK"):
                directory, result = self.invoke("barrier-" + mode.lower(), mode)
                assert isinstance(result, c.Observed), result
                location = c.session_directory(c.read_json(directory / "request.json", c.MAX_REQUEST))
                # Trigger runs independently because a callback barrier can hold refresh completion.
                (self.project / "src" / "Barrier.java").write_text("class Barrier { int wave = " + str(self.serial) + "; }\n")
                import concurrent.futures
                with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
                    refresh = pool.submit(self.action, "REFRESH")
                    try:
                        wait_for("test barrier entered", lambda: (directory / "entered").exists())
                        if mode == "WRITER":
                            self.action("BURST", rounds=350)
                            wait_for("queue overflow reported outside queue", lambda: "QUEUE_OVERFLOW" in c.read_status(directory).receipt["coverage"]["reasons"])
                            self.record("stalled writer saturates bounded queue; loss remains observable")
                        stop = dict(type="STOP", version=1, sessionId=c.read_json(directory / "request.json", c.MAX_REQUEST)["sessionId"])
                        pending = location / "acceptance-stop.pending"; pending.write_text(json.dumps(stop)); pending.rename(location / "stop.json")
                        wait_for("retirement started", lambda: (directory / "retiring").exists())
                        wait_for("retirement timeout remains unconfirmed", lambda: c.read_json(location / "status.json", c.MAX_RECEIPT)["state"] == "RETIREMENT_UNCONFIRMED", 20)
                        assert (location.parent.parent / "admission").exists()
                    finally:
                        (directory / "release").touch()
                    refresh.result(timeout=60)
                self.terminal(directory)
                self.record(mode.lower() + " blocks clean retirement; timeout retains admission; late completion detaches")
            for mode, cause in (("WRITE_FAILURE", "OUTPUT_FAILURE"), ("PROJECTION_FAILURE", "CALLBACK_FAILURE")):
                directory, result = self.invoke(mode.lower(), mode)
                assert isinstance(result, c.Observed), result
                self.trigger(self.serial)
                _, status = self.terminal(directory)
                assert status["stopSignal"] == dict(type="REQUESTED", cause=cause)
                if mode == "WRITE_FAILURE":
                    assert "OUTPUT_FAILURE" in status["coverage"]["reasons"] and status["coverage"]["dropped"] > 0
                self.record(mode.lower() + " emits typed failure and retires its owner")
            closing, result = self.invoke("project-close")
            assert isinstance(result, c.Observed), result
            self.action("CLOSE")
            self.terminal(closing)
            self.record("project close retires original owner")
            self.completed = True
        finally:
            cleanup = "UNCONFIRMED"
            try:
                for directory in self.sessions:
                    if isinstance(c.verify_directory(directory), c.Observed):
                        c.stop_session(directory)
                if self.action("INSPECT")["open"]:
                    self.action("CLOSE")
                cleanup = "CONFIRMED"
            finally:
                (self.root / "report.json").write_text(json.dumps(dict(type="HOST_ACCEPTANCE",
                    outcome="PASSED" if self.completed and cleanup == "CONFIRMED" else "INCOMPLETE",
                    cleanup=cleanup, cases=self.proofs, artifactSha256=self.digest), indent=2) + "\n")



if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--idea-contents", type=Path, required=True)
    Acceptance(parser.parse_args().idea_contents).run()
