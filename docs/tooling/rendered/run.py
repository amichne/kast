#!/usr/bin/env -S uv run --locked --script
# /// script
# requires-python = ">=3.12,<3.14"
# dependencies = ["jsonschema==4.26.0"]
# [tool.uv]
# no-build = true
# required-version = ">=0.12.9"
# [[tool.uv.index]]
# name = "pypi"
# url = "https://pypi.org/simple"
# default = true
# ///
"""Collect bounded rendered evidence through the installed Agent Browser CLI."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import signal
import subprocess
import threading
from collections import deque
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Literal, TypedDict, TypeGuard

from jsonschema import ValidationError, validate

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
WORDS = re.compile(r"[^\W_]+(?:['’\-][^\W_]+)*", re.UNICODE)
ROUTES = (
    "/",
    "/start",
    "/explanation/query-lifecycle",
    "/explanation/value-journey",
    "/reference/callables/query-symbols",
    "/reference/callables/check-diagnostics",
    "/reference/callables/add-declaration",
    "/reference/callables/replace-body",
    "/reference/callables/workspace-lifecycle",
)


class Envelope(TypedDict):
    success: bool
    data: dict[str, object] | None
    error: str | None


class InputDigest(TypedDict):
    path: str
    sha256: str


class Snapshot(TypedDict):
    type: str
    sourceRevision: str
    inputs: list[InputDigest]
    inputsDigest: str


class Segment(TypedDict):
    text: str
    initialViewport: bool


class DomEvidence(TypedDict):
    title: str
    route: str
    segments: list[Segment]
    code: list[str]
    fonts: list[dict[str, str]]
    browser: str
    viewport: dict[str, float]
    theme: str
    documentOverflow: bool
    headingCount: int
    missingImageAlternatives: int
    focus: dict[str, object] | None
    controls: list[dict[str, str | None]]


def is_dom(value: object) -> TypeGuard[DomEvidence]:
    """Reject missing fixed evidence before a consumer reads a DOM report."""
    try:
        validate(value, json.loads(HERE.joinpath("dom.schema.json").read_text()))
    except ValidationError:
        return False
    return True


def is_envelope(value: object) -> TypeGuard[Envelope]:
    """The command-specific data stays opaque until its own admission step."""
    try:
        validate(
            value,
            {
                "type": "object",
                "additionalProperties": False,
                "required": ["success", "data", "error"],
                "properties": {
                    "success": {"type": "boolean"},
                    "data": {"type": ["object", "null"]},
                    "error": {"type": ["string", "null"]},
                },
            },
        )
    except ValidationError:
        return False
    return True


@dataclass(frozen=True)
class Footprint:
    W: int
    L: int
    initialViewportW: int


@dataclass(frozen=True)
class Preview:
    origin: str
    version: str
    staticDigest: str


@dataclass(frozen=True)
class State:
    route: str
    name: str
    status: Literal["passed", "failed", "not-run"]
    footprint: Footprint | None
    dom: DomEvidence | None
    screenshot: str | None
    failure: str | None

    def __post_init__(self) -> None:
        if self.status == "passed" and (
            self.footprint is None or self.dom is None or self.failure is not None
        ):
            raise ValueError("A passed state needs measured DOM evidence and no failure.")
        if self.status != "passed" and not self.failure:
            raise ValueError("A rejected or unmeasured state needs a reason.")
        if self.status == "not-run" and (self.footprint is not None or self.dom is not None):
            raise ValueError("An unrun state cannot contain a measurement.")


def footprint(dom: DomEvidence) -> Footprint:
    """Count each rendered text segment and each nonblank code line once per state."""
    return Footprint(
        W=sum(len(WORDS.findall(segment["text"])) for segment in dom["segments"]),
        L=sum(bool(line.strip()) for block in dom["code"] for line in block.splitlines()),
        initialViewportW=sum(
            len(WORDS.findall(segment["text"]))
            for segment in dom["segments"]
            if segment["initialViewport"]
        ),
    )


class BrowserFailure(Exception):
    """An external browser command did not establish its requested outcome."""


def required_program(name: str) -> str:
    program = shutil.which(name)
    if program is None:
        raise BrowserFailure("RENDERED_PROGRAM_UNAVAILABLE:" + name)
    return program


class Browser:
    def __init__(self, session: str, executable: Path | None = None) -> None:
        self.session = session
        self.binary = HERE / "node_modules/.bin/agent-browser"
        version = "155.0.8059.84"
        base = Path.home() / ".agent-browser/browsers" / ("chrome-" + version)
        self.executable = base / (
            "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"
            if platform.system() == "Darwin"
            else "chrome"
        )
        if executable is not None:
            self.executable = executable.resolve()

    def call(self, *arguments: str, source: str | None = None) -> dict[str, object]:
        try:
            result = subprocess.run(
                [
                    str(self.binary),
                    "--session",
                    self.session,
                    "--executable-path",
                    str(self.executable),
                    *arguments,
                    "--json",
                ],
                input=source,
                text=True,
                capture_output=True,
                timeout=40,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            raise BrowserFailure(type(error).__name__) from error
        try:
            envelope: object = json.loads(result.stdout)
        except (json.JSONDecodeError, UnicodeDecodeError) as error:
            raise BrowserFailure("BROWSER_RESPONSE_INVALID") from error
        if not is_envelope(envelope):
            raise BrowserFailure("BROWSER_RESPONSE_INVALID")
        if result.returncode or envelope["success"] is not True or envelope["data"] is None:
            raise BrowserFailure((envelope["error"] or "BROWSER_COMMAND_FAILED")[:1000])
        return envelope["data"]

    def evaluate(self, source: str) -> object:
        return self.call("eval", "--stdin", source=source).get("result")

    def zoom(self, factor: Literal[1, 2]) -> None:
        """Use Chrome's own settings API in this disposable browser profile."""
        settings = self.call("tab", "new", "chrome://settings/appearance")
        tab = settings.get("tabId")
        if not isinstance(tab, str):
            raise BrowserFailure("SETTINGS_TAB_ID_MISSING")
        try:
            result = self.evaluate(
                "new Promise(resolve => chrome.settingsPrivate.setDefaultZoom("
                + str(factor)
                + ", success => resolve({success})))"
            )
            if result != {"success": True}:
                raise BrowserFailure("BROWSER_ZOOM_REJECTED")
        finally:
            _ = self.call("tab", "close", tab)


def input_snapshot() -> Snapshot:
    result = subprocess.run(
        [
            required_program("uv"),
            "run",
            "--locked",
            "--project",
            "docs/tooling",
            "python",
            "docs/tooling/publication.py",
            "--root",
            str(ROOT),
            "--snapshot",
        ],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
        timeout=60,
    )
    snapshot: Snapshot = json.loads(result.stdout)
    if result.returncode or snapshot.get("type") != "DOCUMENTATION_INPUTS":
        raise BrowserFailure("PUBLICATION_INPUT_SNAPSHOT_FAILED")
    return snapshot


def environment(browser: Browser) -> dict[str, str]:
    configuration: dict[str, str] = json.loads(HERE.joinpath("environment.json").read_text())
    node = subprocess.check_output([required_program("node"), "--version"], text=True).strip()
    agent = subprocess.check_output([str(browser.binary), "--version"], text=True).strip()
    chrome = subprocess.check_output([str(browser.executable), "--version"], text=True).strip()
    mint: dict[str, str] = json.loads((ROOT / ".github/tools/mintlify/package.json").read_text())[
        "dependencies"
    ]
    if (
        node != configuration["nodeVersion"]
        or agent != configuration["agentBrowserVersion"]
        or configuration["browserVersion"] not in chrome
        or mint["mint"] != configuration["mintlifyVersion"]
    ):
        raise BrowserFailure("RENDERED_ENVIRONMENT_MISMATCH")
    return {
        "node": node,
        "agentBrowser": agent,
        "chrome": chrome,
        "chromeExecutableSha256": hashlib.sha256(browser.executable.read_bytes()).hexdigest(),
        "mint": mint["mint"],
        "mintClient": configuration["mintlifyClientVersion"],
    }


@contextmanager
def owned_preview(port: int, diagnostics: deque[str]) -> Iterator[Preview]:
    config: dict[str, str] = json.loads(HERE.joinpath("environment.json").read_text())
    ready = threading.Event()
    command = [
        str(ROOT / ".github/tools/mintlify/node_modules/.bin/mint"),
        "dev",
        "--no-open",
        "--disable-prefetch",
        "--port",
        str(port),
        "--client-version",
        config["mintlifyClientVersion"],
    ]
    with subprocess.Popen(
        command,
        cwd=ROOT / "docs/public",
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        start_new_session=True,
    ) as process:
        stream = process.stdout
        if stream is None:
            raise BrowserFailure("PREVIEW_OUTPUT_UNAVAILABLE")

        def consume() -> None:
            for line in stream:
                diagnostics.append(line[-500:])
                if "preview ready" in line:
                    ready.set()

        reader = threading.Thread(target=consume, daemon=True)
        reader.start()
        try:
            if not ready.wait(60):
                raise BrowserFailure("PREVIEW_START_FAILED")
            client = Path.home() / ".mintlify/previews" / str(port) / "mint"
            actual = (client / "mint-version.txt").read_text().strip()
            if actual != config["mintlifyClientVersion"]:
                raise BrowserFailure("PREVIEW_VERSION_MISMATCH")
            static = client / "apps/client/.next/static"
            assets = sorted(path for path in static.rglob("*") if path.is_file())
            if not assets:
                raise BrowserFailure("PREVIEW_STATIC_INPUTS_MISSING")
            digest = hashlib.sha256()
            for asset in assets:
                digest.update(str(asset.relative_to(static)).encode())
                digest.update(b"\0")
                digest.update(hashlib.sha256(asset.read_bytes()).digest())
            yield Preview(f"http://localhost:{port}", actual, digest.hexdigest())
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    _ = process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    _ = process.wait(timeout=5)
            reader.join(timeout=5)


def collect(
    browser: Browser, route: str, name: str, output: Path, *, screenshot: bool = True
) -> State:
    try:
        dom = browser.evaluate(HERE.joinpath("inspect.js").read_text())
        if not is_dom(dom) or dom["route"] != route or dom["headingCount"] != 1:
            raise BrowserFailure("RENDERED_ROUTE_MISMATCH")
        file = output / f"{route.strip('/').replace('/', '-') or 'overview'}-{name}.png"
        if screenshot:
            _ = browser.call("screenshot", str(file))
        failures: list[str] = []
        if dom["documentOverflow"]:
            failures.append("DOCUMENT_HORIZONTAL_OVERFLOW")
        if dom["missingImageAlternatives"]:
            failures.append("IMAGE_ALTERNATIVE_MISSING")
        if name == "keyboard-focus" and (
            dom["focus"] is None or dom["focus"].get("focusVisible") is not True
        ):
            failures.append("KEYBOARD_FOCUS_NOT_VISIBLE")
        return State(
            route,
            name,
            "failed" if failures else "passed",
            footprint(dom),
            dom,
            file.name if screenshot else None,
            ",".join(failures) or None,
        )
    except BrowserFailure as error:
        return State(route, name, "failed", None, None, None, str(error))


def bounded_count(browser: Browser, selector: str, maximum: int) -> int:
    count = browser.evaluate(f"document.querySelectorAll({json.dumps(selector)}).length")
    if not isinstance(count, int) or isinstance(count, bool) or count < 1 or count > maximum:
        raise BrowserFailure("INTERACTION_SCOPE_CHANGED")
    return count


def explorer_states(browser: Browser, output: Path) -> list[State]:
    route = "/explanation/value-journey"
    states: list[State] = []
    if bounded_count(browser, ".vj-cases button", 4) != 4:
        raise BrowserFailure("EXPLORER_EXAMPLES_MISSING")
    for example in range(1, 5):
        _ = browser.call("focus", f".vj-cases button:nth-of-type({example})")
        _ = browser.call("press", "Enter")
        contexts = browser.evaluate("document.querySelectorAll('.vj-route-choice button').length")
        if not isinstance(contexts, int) or contexts < 0 or contexts > 2:
            raise BrowserFailure("EXPLORER_CONTEXT_SCOPE_CHANGED")
        for context in range(1, max(contexts, 1) + 1):
            if contexts:
                _ = browser.call("focus", f".vj-route-choice button:nth-of-type({context})")
                _ = browser.call("press", "Enter")
            steps = bounded_count(browser, ".vj-steps button", 10)
            for step in range(1, steps + 1):
                _ = browser.call("focus", f".vj-steps button:nth-of-type({step})")
                _ = browser.call("press", "Enter")
                if (
                    browser.evaluate(
                        f"document.querySelector('.vj-steps button:nth-of-type({step})').getAttribute('aria-current')"
                    )
                    != "step"
                ):
                    raise BrowserFailure("EXPLORER_KEYBOARD_SELECTION_FAILED")
                states.append(
                    collect(
                        browser,
                        route,
                        f"example-{example}-context-{context}-step-{step}",
                        output,
                        screenshot=False,
                    )
                )
    return states


def run_routes(browser: Browser, origin: str, routes: list[str], output: Path) -> list[State]:
    states: list[State] = []
    _ = browser.call("open", "about:blank")
    browser.zoom(1)
    unavailable = False
    for route in routes:
        if unavailable:
            states.append(
                State(
                    route,
                    "initial",
                    "not-run",
                    None,
                    None,
                    None,
                    "PREVIEW_UNAVAILABLE_AFTER_CALLABLE_FAILURE",
                )
            )
            continue
        try:
            _ = browser.call("open", origin.rstrip("/") + route)
            _ = browser.call("wait", "--fn", "document.querySelector('#page-title') !== null")
            for theme in ("light", "dark"):
                _ = browser.call("set", "media", theme, "reduced-motion")
                _ = browser.evaluate(
                    f"document.documentElement.classList.toggle('dark', {str(theme == 'dark').lower()})"
                )
                for width, height, label in ((1440, 1000, "desktop"), (390, 844, "mobile")):
                    _ = browser.call("set", "viewport", str(width), str(height), "1")
                    _ = browser.evaluate("window.scrollTo(0,0)")
                    states.append(collect(browser, route, f"{label}-{theme}", output))
            _ = browser.call("set", "viewport", "1440", "1000", "1")
            browser.zoom(2)
            _ = browser.evaluate("window.scrollTo(0,0)")
            zoom_state = collect(browser, route, "browser-zoom-200-dark", output)
            if zoom_state.dom is not None and zoom_state.dom["viewport"]["width"] != 720:
                raise BrowserFailure("BROWSER_ZOOM_NOT_ESTABLISHED")
            states.append(zoom_state)
            browser.zoom(1)
            _ = browser.evaluate(
                "document.querySelector('#content a, #content button, #content [role=tab]').focus()"
            )
            _ = browser.call("press", "Tab")
            states.append(collect(browser, route, "keyboard-focus", output))
            if route == "/explanation/value-journey":
                states.extend(explorer_states(browser, output))
        except BrowserFailure as error:
            states.append(State(route, "initial", "failed", None, None, None, str(error)))
            if "/reference/callables/" in route:
                unavailable = True
    return states


def receipt(
    snapshot: Snapshot, states: list[State], output: Path, *, input_match: bool, root: Path = ROOT
) -> dict[str, object]:
    required = set(ROUTES[:5])
    report_path = output / "report.json"
    evidence = [
        {
            "path": str(report_path.relative_to(root)),
            "sha256": hashlib.sha256(report_path.read_bytes()).hexdigest(),
        }
    ]
    evidence.extend(
        {
            "path": str((output / state.screenshot).relative_to(root)),
            "sha256": hashlib.sha256((output / state.screenshot).read_bytes()).hexdigest(),
        }
        for state in states
        if state.screenshot is not None
    )
    evidence.sort(key=lambda item: item["path"])
    expected_names = {
        "representative-pages": {"desktop-light"},
        "keyboard": {"keyboard-focus"},
        "focus": {"keyboard-focus"},
        "mobile": {"mobile-light", "mobile-dark"},
        "zoom-200": {"browser-zoom-200-dark"},
        "themes": {"desktop-light", "desktop-dark"},
        "screenshots": {
            "desktop-light",
            "desktop-dark",
            "mobile-light",
            "mobile-dark",
            "browser-zoom-200-dark",
            "keyboard-focus",
        },
    }
    checks: list[dict[str, object]] = []
    for name, names in expected_names.items():
        selected = [state for state in states if state.route in required and state.name in names]
        passed = (
            input_match
            and len(selected) == len(required) * len(names)
            and all(state.status == "passed" for state in selected)
            and (name != "screenshots" or all(state.screenshot is not None for state in selected))
        )
        checks.append(
            {
                "name": name,
                "status": "passed" if passed else "failed",
                "evidence": evidence,
                "reason": "All five representative routes satisfy the named assertions."
                if passed
                else "The report keeps failed routes, missing states, or changed candidate inputs.",
            }
        )
    measured_routes = {
        state.route for state in states if state.footprint is not None and state.status == "passed"
    }
    explorer = [state for state in states if state.name.startswith("example-")]
    schemas = {
        state.route
        for state in states
        if state.name == "schema-depth-2-all-variants" and state.status == "passed"
    }
    bounded_complete = (
        input_match
        and measured_routes == set(ROUTES)
        and len(explorer) == 18
        and all(state.status == "passed" for state in explorer)
        and schemas == set(ROUTES[4:])
    )
    checks.append(
        {
            "name": "bounded-measurements",
            "status": "passed" if bounded_complete else "failed",
            "evidence": evidence,
            "reason": "The explorer has all 18 declared step states. Every callable needs a rendered recursive scope before full measurement acceptance.",
        }
    )
    return {
        "schemaVersion": 1,
        "kind": "KAST_DOCUMENTATION_RENDERED",
        "status": "completed" if all(check["status"] == "passed" for check in checks) else "failed",
        "sourceRevision": snapshot["sourceRevision"],
        "inputs": snapshot["inputs"],
        "checks": checks,
        "limits": [
            "Sampled browser behavior does not prove complete accessibility, reader comprehension, or native Kast execution.",
            "Each footprint describes one exposed DOM state. Recursive schema expansion needs a declared finite scope.",
            "Screenshot capture supplies review evidence. Pixel comparison needs identical environment and font digests.",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument("--origin", default="http://localhost:3337")
    _ = parser.add_argument("--output", type=Path, required=True)
    _ = parser.add_argument("--session", default="kast-documentation-rendered")
    _ = parser.add_argument("--route", action="append", dest="routes")
    _ = parser.add_argument("--preview", action="store_true")
    _ = parser.add_argument("--port", type=int, default=3339)
    _ = parser.add_argument("--browser-executable", type=Path)
    arguments = parser.parse_args()
    output: Path = arguments.output.resolve()
    if not output.is_relative_to(ROOT):
        parser.error("The evidence output must reside in the repository.")
    output.mkdir(parents=True, exist_ok=True)
    before = input_snapshot()
    browser = Browser(arguments.session, arguments.browser_executable)
    states: list[State] = []
    runtime: dict[str, str] = {}
    diagnostics: deque[str] = deque(maxlen=80)
    routes: list[str] = arguments.routes or list(ROUTES)
    try:
        runtime = environment(browser)
        if arguments.preview:
            with owned_preview(arguments.port, diagnostics) as preview:
                runtime = runtime | {
                    "mintClient": preview.version,
                    "mintClientStaticDigest": preview.staticDigest,
                }
                states = run_routes(browser, preview.origin, routes, output)
        else:
            states = run_routes(browser, arguments.origin, routes, output)
    except (
        BrowserFailure,
        OSError,
        subprocess.TimeoutExpired,
        subprocess.CalledProcessError,
    ) as error:
        states.append(State("/", "environment", "failed", None, None, None, str(error)[:1000]))
    finally:
        try:
            _ = browser.call("close")
        except BrowserFailure:
            diagnostics.append(
                "The owned browser did not answer close. Its isolated session remains named in the command."
            )
    after = input_snapshot()
    report = {
        "schemaVersion": 1,
        "status": "completed" if before == after else "input-mismatch",
        "sourceRevision": before["sourceRevision"],
        "inputs": before["inputs"],
        "finalInputs": after["inputs"],
        "platform": {
            "system": platform.system(),
            "release": platform.release(),
            "machine": platform.machine(),
        },
        "environment": runtime,
        "states": [asdict(state) for state in states],
        "previewDiagnostics": list(diagnostics),
        "limits": [
            "Each W/L measurement describes one exposed DOM state, not the complete source footprint.",
            "Chrome settingsPrivate sets native page zoom to 2, then restores 1 in the disposable profile.",
            "Screenshots need author review. No pixel comparison or comprehension claim follows from capture.",
            "A failed route keeps its measurements unknown.",
        ],
    }
    _ = (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    publication_receipt = receipt(
        before, states, output, input_match=before == after and arguments.preview
    )
    _ = (output / "receipt.json").write_text(json.dumps(publication_receipt, indent=2) + "\n")
    return 0 if publication_receipt["status"] == "completed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
