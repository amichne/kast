#!/usr/bin/env python3
"""Capture and replay Kast CLI incident evidence through a real tmux PTY."""

from __future__ import annotations

import argparse
import dataclasses
import enum
import json
import math
import os
import re
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1
DEFAULT_OUTPUT_BUDGET_BYTES = 30_000
EXIT_SENTINEL = "::kast-repro-exit="
OBSERVATION_EXIT_SENTINEL = "__KAST_OBSERVATION_EXIT_CODE__="
CAPSULE_STATE_ENVIRONMENT_KEYS = (
    "HOME",
    "KAST_HOME",
    "KAST_CONFIG_HOME",
    "KAST_CACHE_HOME",
    "GRADLE_USER_HOME",
    "TMPDIR",
    "XDG_CACHE_HOME",
    "XDG_CONFIG_HOME",
    "XDG_DATA_HOME",
)
REQUIRED_SCENARIO_COMMANDS = (
    "runtime-stop",
    "cold-up",
    "cold-observer",
    "home",
    "help",
    "file-list",
    "symbol-search",
    "symbol-resolve",
    "symbol-show",
    "relation-references",
    "relation-calls-incoming",
    "relation-calls-outgoing",
    "relation-implementations",
    "relation-hierarchy-supertypes",
    "relation-hierarchy-subtypes",
    "graph-summary",
    "graph-nodes",
    "graph-neighbors",
    "graph-topology",
    "graph-communities",
    "graph-impact",
    "diagnostic-check",
    "workspace-refresh",
    "refresh-observer",
)
REQUIRED_SUCCESSFUL_SCENARIO_COMMANDS = tuple(
    name
    for name in REQUIRED_SCENARIO_COMMANDS
    if name
    not in {
        "cold-up",
        "cold-observer",
        "workspace-refresh",
        "refresh-observer",
    }
)


class ReproError(Exception):
    """A closed setup, capture, or evidence-validation failure."""


class FindingCode(str, enum.Enum):
    READY_DURING_PENDING_UP = "READY_DURING_PENDING_UP"
    COLD_START_DID_NOT_CONVERGE = "COLD_START_DID_NOT_CONVERGE"
    GENERIC_CONFLICT_DURING_REFRESH = "GENERIC_CONFLICT_DURING_REFRESH"
    REFRESH_DID_NOT_CONVERGE = "REFRESH_DID_NOT_CONVERGE"
    MISSING_TRAILING_NEWLINE = "MISSING_TRAILING_NEWLINE"
    DEFAULT_OUTPUT_EXCEEDS_BUDGET = "DEFAULT_OUTPUT_EXCEEDS_BUDGET"
    TRACE_CORRELATION_INCOMPLETE = "TRACE_CORRELATION_INCOMPLETE"
    CAPSULE_CONFINEMENT_VIOLATED = "CAPSULE_CONFINEMENT_VIOLATED"
    CAPSULE_PROCESS_LEAKED = "CAPSULE_PROCESS_LEAKED"
    CAPSULE_RUNTIME_STOP_FAILED = "CAPSULE_RUNTIME_STOP_FAILED"
    OBSERVER_EVIDENCE_INCOMPLETE = "OBSERVER_EVIDENCE_INCOMPLETE"


class ObservationKind(str, enum.Enum):
    HOME = "HOME"
    RESOLVE = "RESOLVE"


@dataclasses.dataclass(frozen=True)
class CommandSpec:
    name: str
    argv: tuple[str, ...]
    stdin: str | None = None
    environment: tuple[tuple[str, str], ...] = ()
    timeout_seconds: float = 180.0

    def public(self) -> dict[str, Any]:
        value: dict[str, Any] = {"name": self.name, "argv": list(self.argv)}
        if self.stdin is not None:
            value["stdinBytes"] = len(self.stdin.encode("utf-8"))
        if self.environment:
            value["environment"] = {key: value for key, value in self.environment}
        return value


@dataclasses.dataclass(frozen=True)
class ActiveCommand:
    spec: CommandSpec
    window_id: str
    started_at_epoch_millis: int
    completion_token: str
    deadline_monotonic: float
    deadline_epoch_millis: int
    observer_stop_path: Path | None = None


@dataclasses.dataclass(frozen=True)
class CommandEvidence:
    name: str
    argv: list[str]
    startedAtEpochMillis: int
    finishedAtEpochMillis: int
    exitCode: int
    timedOut: bool
    transcript: str
    outputBytes: int
    completionToken: str | None = None


@dataclasses.dataclass(frozen=True)
class Finding:
    code: str
    message: str
    evidence: list[str]


@dataclasses.dataclass(frozen=True)
class CapsuleContext:
    mode: str
    root: Path
    bundle_source: Path
    environment: dict[str, str]
    bootstrap_kastctl: Path
    idea_host: Path

    @property
    def cleanup(self) -> str:
        return "STOP_VERIFY_DELETE" if self.mode == "EPHEMERAL" else "STOP_VERIFY_KEEP"

    @property
    def kast(self) -> Path:
        return self.root / "kast-home" / "current" / "bin" / "kast"

    @property
    def kastctl(self) -> Path:
        return self.root / "kast-home" / "current" / "libexec" / "kastctl"

    def public(self) -> dict[str, Any]:
        return {
            "mode": self.mode,
            "root": str(self.root),
            "cleanup": self.cleanup,
            "bundleSource": str(self.bundle_source),
            "ideaHost": str(self.idea_host),
            "environment": self.environment,
            "install": {"kast": str(self.kast), "kastctl": str(self.kastctl)},
        }


@dataclasses.dataclass(frozen=True)
class LiveCapturePreflight:
    output: Path
    session: str


@dataclasses.dataclass(frozen=True)
class MutationProbeIdentity:
    package_name: str
    declaration_name: str
    add_file_path: str
    add_file_declaration_name: str


@dataclasses.dataclass(frozen=True)
class TimedObservation:
    kind: ObservationKind
    text: str
    exit_code: int
    finished_at_epoch_millis: int


def epoch_millis() -> int:
    return time.time_ns() // 1_000_000


def is_within(path: Path, root: Path) -> bool:
    resolved_path = path.resolve()
    resolved_root = root.resolve()
    return resolved_path == resolved_root or resolved_root in resolved_path.parents


def paths_overlap(first: Path, second: Path) -> bool:
    return is_within(first, second) or is_within(second, first)


def capsule_environment(root: Path, workspace_id: str) -> dict[str, str]:
    home = root / "home"
    return {
        "HOME": str(home),
        "KAST_HOME": str(root / "kast-home"),
        "KAST_CONFIG_HOME": str(root / "config"),
        "KAST_CACHE_HOME": str(root / "cache"),
        "GRADLE_USER_HOME": str(root / "gradle"),
        "TMPDIR": str(root / "tmp"),
        "XDG_CACHE_HOME": str(root / "xdg" / "cache"),
        "XDG_CONFIG_HOME": str(root / "xdg" / "config"),
        "XDG_DATA_HOME": str(root / "xdg" / "data"),
        "KAST_WORKSPACE_ID": workspace_id,
        "KAST_IDEA_TRACE": "true",
        "PATH": f"{home / '.local' / 'bin'}{os.pathsep}{os.environ.get('PATH', '')}",
    }


def require_contained_capsule_state_paths(
    root: Path,
    environment: dict[str, str],
) -> tuple[Path, ...]:
    paths = tuple(Path(environment[key]) for key in CAPSULE_STATE_ENVIRONMENT_KEYS)
    escaped = sorted(str(path) for path in paths if not is_within(path, root))
    if escaped:
        raise ReproError(f"capsule state path escaped its root: {', '.join(escaped)}")
    return paths


def require_contained_capsule_state_symlinks(
    root: Path,
    state_paths: tuple[Path, ...],
) -> None:
    try:
        pending = list(state_paths)
        visited: set[Path] = set()
        while pending:
            directory = pending.pop()
            resolved_directory = directory.resolve(strict=True)
            if not is_within(resolved_directory, root):
                raise ReproError(
                    "capsule state symlink escaped its root: "
                    f"{directory} -> {resolved_directory}"
                )
            if resolved_directory in visited:
                continue
            visited.add(resolved_directory)
            for candidate in directory.iterdir():
                if candidate.is_symlink():
                    resolved_candidate = candidate.resolve(strict=True)
                    if not is_within(resolved_candidate, root):
                        raise ReproError(
                            "capsule state symlink escaped its root: "
                            f"{candidate} -> {resolved_candidate}"
                        )
                if candidate.is_dir():
                    pending.append(candidate)
    except (OSError, RuntimeError) as error:
        raise ReproError(
            f"capsule state symlink could not be validated: {error}"
        ) from error


def merged_environment(overrides: dict[str, str] | None = None) -> dict[str, str]:
    environment = os.environ.copy()
    if overrides:
        environment.update(overrides)
    return environment


def capsule_lifecycle_plan(
    capsule: CapsuleContext,
    workspace: Path,
    commands: list[CommandSpec],
) -> list[CommandSpec]:
    return [
        CommandSpec(
            "capsule-setup",
            (
                str(capsule.bootstrap_kastctl),
                "--output",
                "json",
                "setup",
                "--source",
                str(capsule.bundle_source),
                "--profile",
                "development",
            ),
            timeout_seconds=420.0,
        ),
        CommandSpec(
            "capsule-idea-host",
            (
                str(capsule.kastctl),
                "--output",
                "json",
                "config",
                "set",
                "indexer.hostCommand",
                str(capsule.idea_host),
                "--workspace-root",
                str(workspace),
            ),
        ),
        *commands,
        CommandSpec(
            "capsule-runtime-stop",
            (
                str(capsule.kastctl),
                "--output",
                "json",
                "developer",
                "runtime",
                "stop",
            ),
        ),
        CommandSpec(
            "capsule-teardown-verify",
            ("internal:verify-no-capsule-processes", str(capsule.root)),
        ),
    ]


def issued_symbol_command(
    name: str,
    kast: str,
    query: str,
    exact_arguments: tuple[str, ...],
    *,
    stdin: str | None = None,
) -> CommandSpec:
    resolve = shlex.join((kast, "--output", "json", "symbol", "resolve", "--query", query))
    exact = shlex.join((kast, *exact_arguments))
    extract = shlex.quote(
        "import json,sys; value=json.load(sys.stdin).get('result',{}).get('selector'); "
        "print(value) if isinstance(value,str) and value else sys.exit(2)"
    )
    script = (
        f"resolved=$({resolve}) || exit $?; "
        'printf "%s\\n" "$resolved"; '
        f"selector=$(printf '%s' \"$resolved\" | python3 -c {extract}) || exit $?; "
        f'{exact} --selector "$selector"'
    )
    return CommandSpec(name, ("/bin/bash", "-lc", script), stdin=stdin)


def issued_graph_node_command(name: str, kast: str) -> CommandSpec:
    nodes = shlex.join((kast, "--output", "json", "graph", "nodes"))
    neighbors = shlex.join((kast, "graph", "neighbors"))
    extract = shlex.quote(
        "import json,sys; nodes=json.load(sys.stdin).get('result',{}).get('nodes',[]); "
        "value=nodes[0].get('nodeSelector') if nodes and isinstance(nodes[0],dict) else None; "
        "print(value) if isinstance(value,str) and value else sys.exit(2)"
    )
    script = (
        f"nodes=$({nodes}) || exit $?; "
        'printf "%s\\n" "$nodes"; '
        f"node_selector=$(printf '%s' \"$nodes\" | python3 -c {extract}) || exit $?; "
        f'{neighbors} --node-selector "$node_selector"'
    )
    return CommandSpec(name, ("/bin/bash", "-lc", script))


def mutation_probe_identity(
    workspace: Path,
    source: Path,
    symbol: str,
) -> MutationProbeIdentity:
    try:
        content = source.read_text(encoding="utf-8")
    except OSError as error:
        raise ReproError(f"cannot read --file for mutation planning: {error}") from error
    package = re.search(
        r"(?m)^\s*package\s+"
        r"((?:[A-Za-z_][A-Za-z0-9_]*\.)*[A-Za-z_][A-Za-z0-9_]*)\s*;?\s*$",
        content,
    )
    declaration = (
        re.fullmatch(
            rf"{re.escape(package.group(1))}\.([A-Za-z_][A-Za-z0-9_]*)",
            symbol,
        )
        if package is not None
        else None
    )
    if package is None or declaration is None:
        raise ReproError(
            "--exercise-plans requires a top-level declaration from the --file package"
        )
    for sequence in range(10_000):
        probe_name = "KastCliReproProbe" + (str(sequence) if sequence else "")
        probe_file = source.parent / f"{probe_name}.kt"
        if not probe_file.exists() and not probe_file.is_symlink():
            return MutationProbeIdentity(
                package.group(1),
                declaration.group(1),
                probe_file.relative_to(workspace).as_posix(),
                probe_name,
            )
    raise ReproError("--exercise-plans could not find an absent add-file probe path")


def command_plan(
    kast: str,
    file_path: str,
    symbol: str,
    *,
    restart_runtime: bool,
    mutation_probe: MutationProbeIdentity | None,
    samples: int,
    sample_interval: float,
    transition_timeout: float,
) -> list[CommandSpec]:
    observer = observer_script(kast, symbol, samples, sample_interval)
    commands = [
        CommandSpec("home", (kast,)),
        CommandSpec("help", (kast, "--help")),
        CommandSpec("file-list", (kast, "file", "list", "--match", file_path)),
        CommandSpec("symbol-search", (kast, "symbol", "search", "--query", symbol)),
        CommandSpec("symbol-resolve", (kast, "symbol", "resolve", "--query", symbol)),
        issued_symbol_command("symbol-show", kast, symbol, ("symbol", "show")),
        issued_symbol_command("relation-references", kast, symbol, ("relation", "references")),
        issued_symbol_command(
            "relation-calls-incoming",
            kast,
            symbol,
            ("relation", "calls", "incoming"),
        ),
        issued_symbol_command(
            "relation-calls-outgoing",
            kast,
            symbol,
            ("relation", "calls", "outgoing"),
        ),
        issued_symbol_command(
            "relation-implementations",
            kast,
            symbol,
            ("relation", "implementations"),
        ),
        issued_symbol_command(
            "relation-hierarchy-supertypes",
            kast,
            symbol,
            ("relation", "hierarchy", "supertypes"),
        ),
        issued_symbol_command(
            "relation-hierarchy-subtypes",
            kast,
            symbol,
            ("relation", "hierarchy", "subtypes"),
        ),
        CommandSpec("graph-summary", (kast, "graph", "summary", "--scope", "symbol")),
        CommandSpec("graph-nodes", (kast, "graph", "nodes")),
        issued_graph_node_command("graph-neighbors", kast),
        CommandSpec("graph-topology", (kast, "graph", "topology", "--scope", "symbol")),
        CommandSpec("graph-communities", (kast, "graph", "communities", "--scope", "symbol")),
        issued_symbol_command("graph-impact", kast, symbol, ("graph", "impact")),
        CommandSpec("diagnostic-check", (kast, "diagnostic", "check", "--file", file_path)),
    ]
    if mutation_probe is not None:
        commands.extend(
            [
                issued_symbol_command(
                    "change-plan-rename",
                    kast,
                    symbol,
                    (
                        "change",
                        "plan",
                        "rename",
                        "--name",
                        f"{mutation_probe.declaration_name}ReproProbe",
                    ),
                ),
                CommandSpec(
                    "change-plan-add-file",
                    (
                        kast,
                        "change",
                        "plan",
                        "add-file",
                        "--file",
                        mutation_probe.add_file_path,
                    ),
                    stdin=(
                        f"package {mutation_probe.package_name}\n\n"
                        f"internal object {mutation_probe.add_file_declaration_name}"
                    ),
                ),
                CommandSpec(
                    "change-plan-add-declaration",
                    (kast, "change", "plan", "add-declaration", "--file", file_path),
                    stdin="private const val KAST_CLI_REPRO_SENTINEL = 1",
                ),
                issued_symbol_command(
                    "change-plan-replace",
                    kast,
                    symbol,
                    ("change", "plan", "replace"),
                    stdin=f"class {mutation_probe.declaration_name}ReproProbe",
                ),
            ]
        )
    commands.extend(
        [
            CommandSpec(
                "workspace-refresh",
                (kast, "workspace", "refresh", "--file", file_path),
                timeout_seconds=transition_timeout,
            ),
            CommandSpec("refresh-observer", ("/bin/bash", "-lc", observer), timeout_seconds=transition_timeout),
        ]
    )
    if restart_runtime:
        commands.extend(
            [
                CommandSpec(
                    "cold-up",
                    (kast, "workspace", "ensure"),
                    environment=(("KAST_IDEA_TRACE", "true"),),
                    timeout_seconds=transition_timeout,
                ),
                CommandSpec("cold-observer", ("/bin/bash", "-lc", observer), timeout_seconds=transition_timeout),
            ]
        )
    return commands


def scenario_execution_order(commands: list[CommandSpec]) -> list[CommandSpec]:
    transitions = {
        "cold-up",
        "cold-observer",
        "workspace-refresh",
        "refresh-observer",
    }
    by_name = {command.name: command for command in commands}
    ordered = [
        by_name[name]
        for name in ("cold-up", "cold-observer")
        if name in by_name
    ]
    ordered.extend(command for command in commands if command.name not in transitions)
    ordered.extend(
        by_name[name]
        for name in ("workspace-refresh", "refresh-observer")
        if name in by_name
    )
    return ordered


def observer_script(kast: str, symbol: str, samples: int, interval: float) -> str:
    kast_command = shlex.quote(kast)
    symbol_argument = shlex.quote(symbol)
    return (
        'stop=${KAST_REPRO_OBSERVER_STOP:?}; i=1; stop_seen=0; '
        f'while [ "$i" -le {samples} ] || [ "$stop_seen" -eq 0 ]; do '
        'if [ -e "$stop" ]; then stop_seen=1; fi; '
        'printf "__KAST_SAMPLE__=%s\\n" "$i"; '
        'printf "__KAST_OBSERVATION__=HOME\\n"; '
        f"{kast_command}; observation_status=$?; "
        f'printf "{OBSERVATION_EXIT_SENTINEL}%s\\n" "$observation_status"; '
        'printf "__KAST_OBSERVATION_EPOCH_MILLIS__="; '
        "python3 -c 'import time; print(time.time_ns() // 1000000)'; "
        'printf "__KAST_OBSERVATION__=RESOLVE\\n"; '
        f"{kast_command} symbol resolve --query {symbol_argument}; observation_status=$?; "
        f'printf "{OBSERVATION_EXIT_SENTINEL}%s\\n" "$observation_status"; '
        'printf "__KAST_OBSERVATION_EPOCH_MILLIS__="; '
        "python3 -c 'import time; print(time.time_ns() // 1000000)'; "
        'i=$((i + 1)); '
        f"sleep {interval}; "
        "done"
    )


class TmuxCapture:
    def __init__(
        self,
        session: str,
        workspace: Path,
        evidence: Path,
        keep_session: bool,
        base_environment: dict[str, str] | None = None,
    ) -> None:
        self.session = session
        self.workspace = workspace
        self.evidence = evidence
        self.keep_session = keep_session
        self.base_environment = (
            os.environ.copy() if base_environment is None else dict(base_environment)
        )
        self.commands: list[CommandEvidence] = []

    def start(self) -> None:
        existing = subprocess.run(
            ["tmux", "has-session", "-t", self.session],
            capture_output=True,
            check=False,
        )
        if existing.returncode == 0:
            raise ReproError(f"tmux session already exists: {self.session}")
        run_checked("tmux", "new-session", "-d", "-s", self.session, "-c", str(self.workspace))
        run_checked("tmux", "set-option", "-t", self.session, "history-limit", "1000000")

    def close(self) -> None:
        if not self.keep_session:
            subprocess.run(["tmux", "kill-session", "-t", self.session], check=False, capture_output=True)

    def begin(self, spec: CommandSpec) -> ActiveCommand:
        command = shlex.join(spec.argv)
        completion_token = uuid.uuid4().hex
        completion_marker = f"{EXIT_SENTINEL}{completion_token}:"
        environment = dict(self.base_environment)
        environment.update(dict(spec.environment))
        observer_stop_path: Path | None = None
        if spec.name in {"cold-observer", "refresh-observer"}:
            observer_stop_path = self.evidence / f".{spec.name}-{completion_token}.stop"
            environment["KAST_REPRO_OBSERVER_STOP"] = str(observer_stop_path)
        if environment:
            environment = " ".join(
                f"{shlex.quote(key)}={shlex.quote(value)}" for key, value in environment.items()
            )
            command = f"env {environment} {command}"
        if spec.stdin is not None:
            command = f"printf %s {shlex.quote(spec.stdin)} | {command}"
        completion_wrapper = (
            "import subprocess,time;"
            f"status=subprocess.run(['/bin/bash','-lc',{command!r}]).returncode;"
            "status=128-status if status<0 else status;"
            "completed_at=time.time_ns()//1000000;"
            f"print({completion_marker!r}+str(status)+':'+str(completed_at),flush=True)"
        )
        shell = f"python3 -c {shlex.quote(completion_wrapper)}; exec sleep 2147483647"
        window_name = re.sub(r"[^A-Za-z0-9_-]", "-", spec.name)[:40]
        started_at_epoch_millis = epoch_millis()
        deadline_monotonic = time.monotonic() + spec.timeout_seconds
        deadline_epoch_millis = started_at_epoch_millis + math.ceil(
            spec.timeout_seconds * 1_000
        )
        result = run_checked(
            "tmux",
            "new-window",
            "-d",
            "-P",
            "-F",
            "#{window_id}",
            "-t",
            self.session,
            "-n",
            window_name,
            "-c",
            str(self.workspace),
            f"/bin/bash -lc {shlex.quote(shell)}",
        )
        return ActiveCommand(
            spec,
            result.stdout.strip(),
            started_at_epoch_millis,
            completion_token,
            deadline_monotonic,
            deadline_epoch_millis,
            observer_stop_path,
        )

    def request_observer_completion(self, active: ActiveCommand) -> None:
        if active.observer_stop_path is None:
            raise ReproError(f"command {active.spec.name} is not a transition observer")
        try:
            active.observer_stop_path.write_text("", encoding="utf-8")
        except OSError as error:
            raise ReproError(
                f"cannot stop transition observer {active.spec.name}: {error}"
            ) from error

    def finish(self, active: ActiveCommand) -> CommandEvidence:
        timed_out = False
        transcript = ""
        exit_code: int | None = None
        completed_at_epoch_millis: int | None = None
        while True:
            capture = subprocess.run(
                ["tmux", "capture-pane", "-p", "-J", "-S", "-", "-t", active.window_id],
                text=True,
                capture_output=True,
                check=False,
            )
            if capture.returncode != 0:
                raise ReproError(f"tmux lost capture window for {active.spec.name}")
            transcript = capture.stdout
            match = re.search(
                rf"{re.escape(EXIT_SENTINEL)}{re.escape(active.completion_token)}:"
                r"([0-9]+):([0-9]+)\r?$",
                transcript,
                re.MULTILINE,
            )
            if match is not None:
                completed_at_epoch_millis = int(match.group(2))
                if completed_at_epoch_millis > active.deadline_epoch_millis:
                    timed_out = True
                else:
                    exit_code = int(match.group(1))
                break
            if time.monotonic() >= active.deadline_monotonic:
                timed_out = True
                subprocess.run(
                    ["tmux", "send-keys", "-t", active.window_id, "C-c"],
                    check=False,
                    capture_output=True,
                )
                time.sleep(0.25)
                capture = subprocess.run(
                    ["tmux", "capture-pane", "-p", "-J", "-S", "-", "-t", active.window_id],
                    text=True,
                    capture_output=True,
                    check=False,
                )
                transcript = capture.stdout
                break
            time.sleep(0.1)
        transcript_path = self.evidence / "transcripts" / f"{active.spec.name}.txt"
        transcript_path.write_text(transcript, encoding="utf-8")
        resolved_exit_code = exit_code if exit_code is not None else 124 if timed_out else 125
        evidence = CommandEvidence(
            name=active.spec.name,
            argv=list(active.spec.argv),
            startedAtEpochMillis=active.started_at_epoch_millis,
            finishedAtEpochMillis=(
                completed_at_epoch_millis
                if completed_at_epoch_millis is not None
                else epoch_millis()
            ),
            exitCode=resolved_exit_code,
            timedOut=timed_out,
            transcript=str(transcript_path.relative_to(self.evidence)),
            outputBytes=len(transcript.encode("utf-8")),
            completionToken=active.completion_token,
        )
        self.commands.append(evidence)
        if not self.keep_session or timed_out:
            subprocess.run(
                ["tmux", "kill-window", "-t", active.window_id],
                check=False,
                capture_output=True,
            )
        if active.observer_stop_path is not None:
            try:
                active.observer_stop_path.unlink(missing_ok=True)
            except OSError as error:
                raise ReproError(
                    f"cannot clear transition observer stop marker: {error}"
                ) from error
        return evidence

    def run(self, spec: CommandSpec) -> CommandEvidence:
        return self.finish(self.begin(spec))

    def record(self, name: str, argv: list[str], payload: dict[str, Any], exit_code: int) -> None:
        started = epoch_millis()
        rendered = json.dumps(payload, indent=2) + "\n"
        transcript_path = self.evidence / "transcripts" / f"{name}.txt"
        transcript_path.write_text(rendered, encoding="utf-8")
        self.commands.append(
            CommandEvidence(
                name=name,
                argv=argv,
                startedAtEpochMillis=started,
                finishedAtEpochMillis=epoch_millis(),
                exitCode=exit_code,
                timedOut=False,
                transcript=str(transcript_path.relative_to(self.evidence)),
                outputBytes=len(rendered.encode("utf-8")),
            )
        )


def run_checked(*argv: str) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(argv, text=True, capture_output=True, check=False)
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or f"exit {result.returncode}"
        raise ReproError(f"{shlex.join(argv)} failed: {detail}")
    return result


def require_success(command: CommandEvidence) -> None:
    if command.exitCode != 0 or command.timedOut:
        raise ReproError(
            f"capture infrastructure command {command.name} failed with exit {command.exitCode}"
        )


def finish_observed_operation(
    runner: TmuxCapture,
    operation: ActiveCommand,
    observer: ActiveCommand,
) -> tuple[CommandEvidence, CommandEvidence]:
    operation_evidence = runner.finish(operation)
    runner.request_observer_completion(observer)
    observer_evidence = runner.finish(observer)
    return operation_evidence, observer_evidence


def discover_developer_cli(
    kast: str,
    workspace: Path,
    environment: dict[str, str] | None = None,
) -> Path:
    result = subprocess.run(
        [kast],
        cwd=workspace,
        env=merged_environment(environment),
        text=True,
        capture_output=True,
        check=False,
    )
    match = re.search(r"(?m)^\s+cli: (.+)$", result.stdout)
    if match is None:
        raise ReproError("public Kast home did not expose developerOperations.cli")
    rendered_path = match.group(1).strip()
    if rendered_path.startswith('"'):
        try:
            decoded_path = json.loads(rendered_path)
        except json.JSONDecodeError as error:
            raise ReproError("public Kast home exposed an invalid quoted developer CLI path") from error
        if not isinstance(decoded_path, str):
            raise ReproError("public Kast home exposed a non-string developer CLI path")
        rendered_path = decoded_path
    path = Path(rendered_path).expanduser()
    if not path.is_file() or not os.access(path, os.X_OK):
        raise ReproError(f"discovered developer CLI is not executable: {path}")
    return path


def read_config(
    kastctl: Path,
    workspace: Path,
    environment: dict[str, str] | None = None,
) -> dict[str, Any]:
    result = subprocess.run(
        [str(kastctl), "--output", "json", "config", "list", "--workspace-root", str(workspace)],
        env=merged_environment(environment),
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        raise ReproError(f"config discovery failed: {result.stderr.strip() or result.stdout.strip()}")
    try:
        payload = json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise ReproError(f"config discovery returned invalid JSON: {error}") from error
    if not isinstance(payload, dict):
        raise ReproError("config discovery did not return a JSON object")
    if payload.get("ok") is not True:
        raise ReproError("config discovery did not return ok=true")
    effective = payload.get("effective")
    if not isinstance(effective, dict):
        raise ReproError("config discovery field effective was not a JSON object")
    for field in ("indexer", "paths", "telemetry"):
        if field in effective and not isinstance(effective[field], dict):
            raise ReproError(
                f"config discovery field effective.{field} was not a JSON object"
            )
    return payload


def discover_idea_host(
    requested: Path | None,
    bootstrap: Path,
    workspace: Path,
    *,
    dry_run: bool,
) -> Path:
    if requested is not None:
        host = requested.expanduser().resolve(strict=not dry_run)
        if not dry_run and (not host.is_dir() or host.suffix != ".app"):
            raise ReproError("--idea-host must name an installed IntelliJ IDEA or Android Studio .app")
        return host
    if dry_run:
        return Path("<supported-idea-host>")

    host_config = read_config(bootstrap, workspace)
    configured = host_config.get("effective", {}).get("indexer", {}).get("hostCommand")
    if isinstance(configured, str) and configured and configured != "idea":
        configured_path = Path(configured).expanduser().resolve(strict=True)
        if configured_path.is_dir() and configured_path.suffix == ".app":
            return configured_path

    roots = [Path("/Applications")]
    host_home = os.environ.get("HOME")
    if host_home:
        home = Path(host_home)
        roots.extend(
            [
                home / "Applications",
                home / "Library" / "Application Support" / "JetBrains" / "Toolbox" / "apps",
            ]
        )
    candidates: set[Path] = set()
    for root in roots:
        if not root.is_dir():
            continue
        for candidate in root.rglob("*.app"):
            if candidate.name.startswith(("IntelliJ IDEA", "Android Studio")):
                candidates.add(candidate.resolve())
    installed = sorted(candidates)
    if not installed:
        raise ReproError(
            "no IntelliJ IDEA or Android Studio host is discoverable outside the capsule; "
            "pass --idea-host"
        )
    if len(installed) > 1:
        raise ReproError(
            "multiple IntelliJ IDEA or Android Studio hosts are installed; pass --idea-host: "
            + ", ".join(str(path) for path in installed)
        )
    return installed[0]


def build_capsule(
    args: argparse.Namespace,
    workspace: Path,
    host_kast: str,
    *,
    dry_run: bool,
) -> CapsuleContext | None:
    if args.capsule_root is None and not args.ephemeral_capsule:
        if args.bundle_source is not None or args.idea_host is not None:
            raise ReproError(
                "--bundle-source and --idea-host require --capsule-root or --ephemeral-capsule"
            )
        return None

    if dry_run:
        bootstrap = Path("<host-kastctl>")
    else:
        bootstrap = discover_developer_cli(host_kast, workspace)
    idea_host = discover_idea_host(
        args.idea_host,
        bootstrap,
        workspace,
        dry_run=dry_run,
    )

    if args.bundle_source is not None:
        bundle_source = args.bundle_source.expanduser().resolve(strict=True)
    elif dry_run:
        bundle_source = Path("<immutable-setup-bundle>")
    else:
        resolved_bootstrap = bootstrap.resolve(strict=True)
        if len(resolved_bootstrap.parents) < 2:
            raise ReproError("active developer CLI did not resolve inside an installation bundle")
        active_release = resolved_bootstrap.parents[1]
        version: str | None = None
        try:
            receipt = json.loads((active_release / "receipt.json").read_text(encoding="utf-8"))
            if isinstance(receipt.get("activeVersion"), str):
                version = receipt["activeVersion"]
        except (OSError, json.JSONDecodeError):
            pass
        setup_directory = workspace / "build" / "setup"
        candidates = sorted(
            (
                path
                for path in setup_directory.glob("kast-*.tar.gz")
                if version is None or version in path.name
            ),
            key=lambda path: path.stat().st_mtime_ns,
            reverse=True,
        )
        if not candidates:
            version_detail = f" matching active version {version}" if version else ""
            raise ReproError(
                "no immutable setup archive"
                f"{version_detail} exists under {setup_directory}; "
                "pass --bundle-source or run ./gradlew packageDevelopmentSetupBundle"
            )
        bundle_source = candidates[0].resolve(strict=True)
    if not dry_run and not (bundle_source.is_dir() or bundle_source.is_file()):
        raise ReproError(f"bundle source is unavailable: {bundle_source}")

    if args.ephemeral_capsule:
        root = (
            Path("<ephemeral>")
            if dry_run
            else Path(tempfile.mkdtemp(prefix="kast-capsule-", dir="/private/tmp"))
        )
        mode = "EPHEMERAL"
    else:
        root = args.capsule_root.expanduser().resolve()
        mode = "PERSISTENT"

    workspace_id = (
        "<capsule-workspace-id>"
        if dry_run
        else str(uuid.uuid5(uuid.NAMESPACE_URL, f"kast-cli-repro:{root}:{workspace}"))
    )
    environment = capsule_environment(root, workspace_id)
    capsule = CapsuleContext(mode, root, bundle_source, environment, bootstrap, idea_host)
    try:
        if not dry_run and paths_overlap(root, workspace):
            raise ReproError("capsule root must not overlap the observed workspace")
        socket_probe = root / "tmp" / "kast-indexer-000000000000.sock"
        if not dry_run and len(os.fsencode(str(socket_probe))) >= 104:
            raise ReproError(
                "capsule root is too long for a macOS Unix-domain socket; choose a shorter --capsule-root"
            )
        if not dry_run:
            state_paths = require_contained_capsule_state_paths(root, environment)
            for path in state_paths:
                path.mkdir(parents=True, exist_ok=True)
            require_contained_capsule_state_paths(root, environment)
            require_contained_capsule_state_symlinks(root, state_paths)
        return capsule
    except (OSError, ReproError, KeyboardInterrupt):
        discard_unstarted_ephemeral_capsule(capsule)
        raise


def verify_capsule_install(
    capsule: CapsuleContext,
    workspace: Path,
) -> tuple[Path, dict[str, Any], dict[str, Any]]:
    for executable in (capsule.kast, capsule.kastctl):
        if not executable.is_file() or not os.access(executable, os.X_OK):
            raise ReproError(f"capsule setup did not install an executable: {executable}")
        if not is_within(executable, capsule.root):
            raise ReproError(f"capsule executable escaped its root: {executable.resolve()}")
    discovered = discover_developer_cli(str(capsule.kast), workspace, capsule.environment)
    if discovered.resolve() != capsule.kastctl.resolve():
        raise ReproError(
            "capsule public home exposed a developer CLI outside the capsule installation"
        )
    config = read_config(discovered, workspace, capsule.environment)
    candidate_paths = [capsule.kast.resolve(), capsule.kastctl.resolve()]
    config_path = config.get("configPath")
    if isinstance(config_path, str) and config_path:
        candidate_paths.append(Path(config_path))
    effective_paths = config.get("effective", {}).get("paths", {})
    if isinstance(effective_paths, dict):
        candidate_paths.extend(
            Path(value) for value in effective_paths.values() if isinstance(value, str) and value
        )
    candidate_paths.append(telemetry_output_path(config, workspace))
    escaped = sorted(str(path) for path in candidate_paths if not is_within(path, capsule.root))
    proof = {
        "installationContained": not escaped,
        "declaredPaths": sorted(str(path) for path in candidate_paths),
        "escapedPaths": escaped,
    }
    if escaped:
        raise ReproError(f"capsule configuration escaped its root: {', '.join(escaped)}")
    return discovered, config, proof


def process_table() -> dict[int, tuple[int, str]]:
    result = subprocess.run(
        ["/bin/ps", "-axo", "pid=,ppid=,command="],
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        raise ReproError(f"process inspection failed: {result.stderr.strip() or result.returncode}")
    table: dict[int, tuple[int, str]] = {}
    for line in result.stdout.splitlines():
        match = re.match(r"\s*(\d+)\s+(\d+)\s+(.*)$", line)
        if match is not None:
            table[int(match.group(1))] = (int(match.group(2)), match.group(3))
    return table


def process_ancestry(table: dict[int, tuple[int, str]]) -> set[int]:
    ancestry: set[int] = set()
    current = os.getpid()
    while current > 0 and current not in ancestry:
        ancestry.add(current)
        entry = table.get(current)
        if entry is None:
            break
        current = entry[0]
    return ancestry


def command_mentions_capsule_root(command: str, root: Path) -> bool:
    for spelling in {str(root), str(root.resolve())}:
        pattern = re.compile(
            rf"(?:^|[\s'\"=:,]){re.escape(spelling)}(?=$|[/\s'\"=:,])"
        )
        if pattern.search(command):
            return True
    return False


def capsule_processes(root: Path, seeds: dict[int, str] | None = None) -> dict[int, str]:
    table = process_table()
    excluded = process_ancestry(table)
    owned = {
        pid
        for pid, (_, command) in table.items()
        if pid not in excluded and command_mentions_capsule_root(command, root)
    }
    owned.update(
        pid
        for pid, prior_command in (seeds or {}).items()
        if pid in table
        and pid not in excluded
        and table[pid][1] == prior_command
    )
    changed = True
    while changed:
        changed = False
        for pid, (parent, _) in table.items():
            if pid not in excluded and parent in owned and pid not in owned:
                owned.add(pid)
                changed = True
    return {pid: table[pid][1] for pid in owned}


def signal_capsule_processes(
    root: Path,
    known: dict[int, str],
    process_signal: signal.Signals,
    seconds: float,
) -> tuple[set[int], dict[int, str], dict[int, str]]:
    deadline = time.monotonic() + seconds
    targeted: set[int] = set()
    stable_empty_scans = 0
    remaining = capsule_processes(root, known)
    while True:
        known.update(remaining)
        for process_id in set(remaining) - targeted:
            try:
                os.kill(process_id, process_signal)
            except ProcessLookupError:
                pass
            except PermissionError as error:
                raise ReproError(
                    f"cannot terminate capsule-owned PID {process_id}"
                ) from error
            else:
                targeted.add(process_id)
        if remaining:
            stable_empty_scans = 0
            if time.monotonic() >= deadline:
                break
        else:
            stable_empty_scans += 1
            if stable_empty_scans >= 2:
                break
        time.sleep(0.1)
        remaining = capsule_processes(root, known)
    return targeted, known, remaining


def terminate_capsule_processes(
    root: Path,
    observed: dict[int, str],
) -> tuple[list[int], list[int]]:
    terminated, known, remaining = signal_capsule_processes(
        root,
        dict(observed),
        signal.SIGTERM,
        5.0,
    )
    if remaining:
        killed, _, remaining = signal_capsule_processes(
            root,
            known,
            signal.SIGKILL,
            2.0,
        )
        terminated.update(killed)
    return sorted(terminated), sorted(remaining)


def capture_state_specs(
    kastctl: Path,
    workspace: Path,
    transition_timeout: float,
) -> list[CommandSpec]:
    base = (str(kastctl), "--output", "json")
    return [
        CommandSpec(
            "telemetry-enable",
            (
                *base,
                "config",
                "set",
                "telemetry.enabled",
                "true",
                "--workspace-root",
                str(workspace),
            ),
        ),
        CommandSpec(
            "telemetry-verbose",
            (
                *base,
                "config",
                "set",
                "telemetry.detail",
                "verbose",
                "--workspace-root",
                str(workspace),
            ),
        ),
        CommandSpec(
            "runtime-stop",
            (
                *base,
                "developer",
                "runtime",
                "stop",
                "--workspace-root",
                str(workspace),
            ),
            timeout_seconds=transition_timeout,
        ),
    ]


def config_restore_specs(kastctl: Path, workspace: Path, config: dict[str, Any]) -> list[CommandSpec]:
    mutable = {
        item.get("key"): item
        for item in config.get("mutableFields", [])
        if isinstance(item, dict) and isinstance(item.get("key"), str)
    }
    telemetry = config.get("effective", {}).get("telemetry", {})
    specs: list[CommandSpec] = []
    for key, leaf in (("telemetry.enabled", "enabled"), ("telemetry.detail", "detail")):
        base = (str(kastctl), "--output", "json", "config")
        if mutable.get(key, {}).get("workspaceOverride") is True:
            value = telemetry.get(leaf)
            rendered = str(value).lower() if isinstance(value, bool) else str(value)
            argv = (*base, "set", key, rendered, "--workspace-root", str(workspace))
        else:
            argv = (*base, "unset", key, "--workspace-root", str(workspace))
        specs.append(CommandSpec(f"restore-{leaf}", argv))
    return specs


def config_restore_preview_specs() -> list[CommandSpec]:
    return [
        CommandSpec(
            "restore-enabled",
            ("internal:restore-config", "telemetry.enabled"),
        ),
        CommandSpec(
            "restore-detail",
            ("internal:restore-config", "telemetry.detail"),
        ),
    ]


def runtime_restore_specs(
    kastctl: Path,
    kast: str,
    workspace: Path,
    transition_timeout: float,
) -> list[CommandSpec]:
    return [
        CommandSpec(
            "restore-runtime-stop",
            (
                str(kastctl),
                "--output",
                "json",
                "developer",
                "runtime",
                "stop",
                "--workspace-root",
                str(workspace),
            ),
            timeout_seconds=transition_timeout,
        ),
        CommandSpec(
            "restore-runtime-start",
            (kast, "workspace", "ensure"),
            timeout_seconds=transition_timeout,
        ),
    ]


def restore_config(
    runner: TmuxCapture,
    kastctl: Path,
    workspace: Path,
    config: dict[str, Any],
) -> list[str]:
    errors: list[str] = []
    for spec in config_restore_specs(kastctl, workspace, config):
        try:
            require_success(runner.run(spec))
        except ReproError as error:
            errors.append(str(error))
    return errors


def restore_config_and_runtime(
    runner: TmuxCapture,
    kastctl: Path,
    kast: str,
    workspace: Path,
    config: dict[str, Any],
    transition_timeout: float,
) -> list[str]:
    errors = restore_config(runner, kastctl, workspace, config)
    runtime_specs = runtime_restore_specs(
        kastctl,
        kast,
        workspace,
        transition_timeout,
    )
    try:
        require_success(runner.run(runtime_specs[0]))
    except ReproError as error:
        errors.append(str(error))
        return errors
    if errors:
        return errors
    try:
        require_success(runner.run(runtime_specs[1]))
    except ReproError as error:
        errors.append(str(error))
    return errors


def telemetry_output_path(config: dict[str, Any], workspace: Path) -> Path:
    output = config.get("effective", {}).get("telemetry", {}).get("outputFile")
    if isinstance(output, str) and output:
        configured = Path(output)
        return (configured if configured.is_absolute() else workspace / configured).resolve()
    config_path = Path(str(config.get("configPath", "")))
    return (config_path.parent / "telemetry" / "idea-spans.jsonl").resolve()


def log_paths(config: dict[str, Any], workspace: Path) -> tuple[Path, Path]:
    config_path = Path(str(config.get("configPath", "")))
    if len(config_path.parent.name) != 64:
        raise ReproError("config discovery did not expose a canonical workspace data directory")
    telemetry = telemetry_output_path(config, workspace)
    cache_root = Path(str(config.get("effective", {}).get("paths", {}).get("cacheDir", "")))
    idea_log = cache_root / "idea-sidecars" / config_path.parent.name[:12] / "idea-log" / "idea.log"
    return telemetry, idea_log


def file_size(path: Path) -> int:
    try:
        return path.stat().st_size
    except FileNotFoundError:
        return 0


def copy_delta(source: Path, offset: int, destination: Path) -> None:
    if not source.is_file():
        destination.write_text("", encoding="utf-8")
        return
    with source.open("rb") as stream:
        if source.stat().st_size >= offset:
            stream.seek(offset)
        destination.write_bytes(stream.read())


def write_manifest(
    directory: Path,
    workspace: Path,
    session: str,
    commands: list[CommandEvidence],
    capsule: dict[str, Any] | None = None,
) -> None:
    payload = {
        "schemaVersion": SCHEMA_VERSION,
        "workspaceRoot": str(workspace),
        "sessionName": session,
        "commands": [dataclasses.asdict(command) for command in commands],
        "telemetry": "telemetry.jsonl",
        "structuredTrace": "idea-trace.log",
    }
    if capsule is not None:
        payload["capsule"] = capsule
    (directory / "manifest.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def live_capture_preflight(args: argparse.Namespace, workspace: Path) -> LiveCapturePreflight:
    if shutil.which("tmux") is None:
        raise ReproError("tmux is required for live capture")
    output = args.output_dir or (
        Path(tempfile.gettempdir())
        / "kast-cli-repro"
        / f"{time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())}-{os.getpid()}"
    )
    output = output.resolve()
    if output == workspace or workspace in output.parents:
        raise ReproError("--output-dir must be outside the observed workspace so capture cannot trigger indexing")
    if output.exists() and not output.is_dir():
        raise ReproError(f"evidence output is not a directory: {output}")
    if output.exists() and any(output.iterdir()):
        raise ReproError(f"evidence output is not empty: {output}")
    session = args.session_name or f"kast-cli-repro-{os.getpid()}"
    if re.fullmatch(r"[A-Za-z0-9_-]+", session) is None:
        raise ReproError("--session-name must contain only letters, digits, underscores, or hyphens")
    existing = subprocess.run(
        ["tmux", "has-session", "-t", session],
        capture_output=True,
        check=False,
    )
    if existing.returncode == 0:
        raise ReproError(f"tmux session already exists: {session}")
    return LiveCapturePreflight(output, session)


def require_runtime_restart_authority(
    *,
    restart_requested: bool,
    capsule: CapsuleContext | None,
    dry_run: bool,
) -> None:
    if not dry_run and capsule is None and not restart_requested:
        raise ReproError(
            "--restart-runtime is required for live non-capsule capture because "
            "telemetry settings bind when the runtime starts"
        )


def discard_unstarted_ephemeral_capsule(capsule: CapsuleContext | None) -> None:
    if capsule is None or capsule.mode != "EPHEMERAL" or not capsule.root.exists():
        return
    expected_parent = Path("/private/tmp").resolve()
    if (
        capsule.root.parent.resolve() != expected_parent
        or not capsule.root.name.startswith("kast-capsule-")
    ):
        raise ReproError("refused to clean an unrecognized ephemeral capsule root")
    shutil.rmtree(capsule.root)


def ephemeral_capsule_is_safely_deletable(
    capsule: CapsuleContext | None,
    proof: dict[str, Any] | None,
    teardown_errors: list[str],
) -> bool:
    return (
        capsule is not None
        and capsule.mode == "EPHEMERAL"
        and proof is not None
        and proof.get("runtimeStopped") is True
        and proof.get("runtimeStopSucceeded") is True
        and proof.get("installationContained") is True
        and proof.get("stateContained") is True
        and not teardown_errors
    )


def capture(args: argparse.Namespace) -> int:
    try:
        workspace = args.workspace_root.resolve(strict=True)
    except (OSError, RuntimeError) as error:
        raise ReproError(f"workspace root is unavailable: {error}") from error
    file_path = Path(args.file)
    if file_path.is_absolute() or ".." in file_path.parts:
        raise ReproError("--file must be a workspace-relative path without parent traversal")
    try:
        source = (workspace / file_path).resolve(strict=True)
    except (OSError, RuntimeError) as error:
        raise ReproError(f"capture file is unavailable: {error}") from error
    if workspace not in source.parents or source.suffix != ".kt":
        raise ReproError("--file must resolve to a Kotlin file inside the workspace")
    mutation_probe = (
        mutation_probe_identity(workspace, source, args.symbol)
        if args.exercise_plans
        else None
    )
    host_kast = shutil.which(args.kast)
    if host_kast is None and not args.dry_run:
        raise ReproError(f"Kast executable is unavailable: {args.kast}")
    host_kast = host_kast or args.kast
    preflight = None if args.dry_run else live_capture_preflight(args, workspace)
    capsule = build_capsule(args, workspace, host_kast, dry_run=args.dry_run)
    require_runtime_restart_authority(
        restart_requested=args.restart_runtime,
        capsule=capsule,
        dry_run=args.dry_run,
    )
    kast = str(capsule.kast) if capsule is not None else host_kast
    plan = command_plan(
        kast,
        file_path.as_posix(),
        args.symbol,
        restart_runtime=args.restart_runtime or capsule is not None,
        mutation_probe=mutation_probe,
        samples=args.samples,
        sample_interval=args.sample_interval,
        transition_timeout=args.transition_timeout,
    )
    if args.dry_run:
        preview_kastctl = (
            capsule.kastctl if capsule is not None else Path("<developer-cli>")
        )
        dry_plan = [
            *capture_state_specs(
                preview_kastctl,
                workspace,
                args.transition_timeout,
            ),
            *scenario_execution_order(plan),
        ]
        if capsule is not None:
            dry_plan = capsule_lifecycle_plan(capsule, workspace, dry_plan)
        else:
            dry_plan.extend(config_restore_preview_specs())
            dry_plan.extend(
                runtime_restore_specs(
                    preview_kastctl,
                    kast,
                    workspace,
                    args.transition_timeout,
                )
            )
        payload: dict[str, Any] = {
            "schemaVersion": SCHEMA_VERSION,
            "commands": [spec.public() for spec in dry_plan],
        }
        if capsule is not None:
            payload["capsule"] = capsule.public()
        print(json.dumps(payload, indent=2))
        return 0
    if preflight is None:
        raise ReproError("live capture preflight was not established")
    output = preflight.output
    session = preflight.session
    try:
        if capsule is not None and paths_overlap(output, capsule.root):
            raise ReproError("--output-dir must not overlap the capsule root")
        (output / "transcripts").mkdir(parents=True, exist_ok=True)
    except (OSError, ReproError):
        discard_unstarted_ephemeral_capsule(capsule)
        raise
    runner = TmuxCapture(
        session,
        workspace,
        output,
        args.keep_session,
        capsule.environment if capsule is not None else None,
    )
    config: dict[str, Any] | None = None
    kastctl: Path | None = None
    telemetry_path: Path | None = None
    idea_log_path: Path | None = None
    telemetry_offset = 0
    trace_offset = 0
    installation_proof: dict[str, Any] = {}
    capture_failure: ReproError | None = None
    teardown_errors: list[str] = []
    runner_started = False
    try:
        runner.start()
        runner_started = True
        if capsule is not None:
            lifecycle = capsule_lifecycle_plan(capsule, workspace, [])
            setup = lifecycle[0]
            setup = dataclasses.replace(setup, timeout_seconds=args.transition_timeout)
            require_success(runner.run(setup))
            kastctl, config, installation_proof = verify_capsule_install(capsule, workspace)
            idea_host = dataclasses.replace(
                lifecycle[1],
                timeout_seconds=args.transition_timeout,
            )
            require_success(runner.run(idea_host))
            config = read_config(kastctl, workspace, capsule.environment)
            installation_proof["readOnlyAuthorities"] = {
                "bundleSource": str(capsule.bundle_source),
                "ideaHost": str(capsule.idea_host),
                "workspaceRoot": str(workspace),
            }
        else:
            kastctl = discover_developer_cli(kast, workspace)
            config = read_config(kastctl, workspace)
        (output / "config-before.json").write_text(
            json.dumps(config, indent=2) + "\n",
            encoding="utf-8",
        )
        telemetry_path, idea_log_path = log_paths(config, workspace)
        telemetry_offset = file_size(telemetry_path)
        trace_offset = file_size(idea_log_path)
        for spec in capture_state_specs(
            kastctl,
            workspace,
            args.transition_timeout,
        ):
            require_success(runner.run(spec))
        cold_up = next(spec for spec in plan if spec.name == "cold-up")
        cold_observer = next(spec for spec in plan if spec.name == "cold-observer")
        up_active = runner.begin(cold_up)
        observer_active = runner.begin(cold_observer)
        finish_observed_operation(runner, up_active, observer_active)
        for spec in plan:
            if spec.name in {"cold-up", "cold-observer", "workspace-refresh", "refresh-observer"}:
                continue
            require_success(runner.run(spec))
        refresh = next(spec for spec in plan if spec.name == "workspace-refresh")
        observer = next(spec for spec in plan if spec.name == "refresh-observer")
        refresh_active = runner.begin(refresh)
        observer_active = runner.begin(observer)
        finish_observed_operation(runner, refresh_active, observer_active)
    except ReproError as error:
        capture_failure = error
    except OSError as error:
        capture_failure = ReproError(str(error))
    except KeyboardInterrupt:
        capture_failure = ReproError("capture interrupted")
    finally:
        capsule_proof: dict[str, Any] | None = None
        if capsule is not None and not runner_started:
            try:
                discard_unstarted_ephemeral_capsule(capsule)
            except ReproError as error:
                teardown_errors.append(str(error))
        if capsule is not None and runner_started:
            observed_processes: dict[int, str] = {}
            try:
                observed_processes = capsule_processes(capsule.root)
            except ReproError as error:
                teardown_errors.append(str(error))
            stop_exit_code = 125
            if runner_started and capsule.kastctl.is_file():
                try:
                    stopped = runner.run(
                        CommandSpec(
                            "capsule-runtime-stop",
                            (
                                str(capsule.kastctl),
                                "--output",
                                "json",
                                "developer",
                                "runtime",
                                "stop",
                                "--workspace-root",
                                str(workspace),
                            ),
                            timeout_seconds=args.transition_timeout,
                        )
                    )
                    stop_exit_code = stopped.exitCode
                except ReproError as error:
                    teardown_errors.append(str(error))
            if runner_started:
                runner.close()
            try:
                remaining_candidates = capsule_processes(capsule.root, observed_processes)
                terminated, remaining = terminate_capsule_processes(
                    capsule.root,
                    remaining_candidates,
                )
            except ReproError as error:
                teardown_errors.append(str(error))
                terminated = []
                remaining = sorted(observed_processes)
            state_contained = all(
                is_within(Path(capsule.environment[key]), capsule.root)
                for key in CAPSULE_STATE_ENVIRONMENT_KEYS
            )
            capsule_proof = {
                **capsule.public(),
                **installation_proof,
                "stateContained": state_contained,
                "runtimeStopExitCode": stop_exit_code,
                "observedProcessIds": sorted(observed_processes),
                "terminatedProcessIds": terminated,
                "processesRemaining": remaining,
                "runtimeStopSucceeded": stop_exit_code == 0,
                "runtimeStopped": not remaining,
                "rootDeleted": False,
            }
            runner.record(
                "capsule-teardown-verify",
                ["internal:verify-no-capsule-processes", str(capsule.root)],
                capsule_proof,
                0 if capsule_proof["runtimeStopped"] and state_contained else 1,
            )
        elif runner_started:
            if kastctl is not None and config is not None:
                teardown_errors.extend(
                    restore_config_and_runtime(
                        runner,
                        kastctl,
                        kast,
                        workspace,
                        config,
                        args.transition_timeout,
                    )
                )
            runner.close()

        if telemetry_path is None:
            (output / "telemetry.jsonl").write_text("", encoding="utf-8")
        else:
            copy_delta(telemetry_path, telemetry_offset, output / "telemetry.jsonl")
        if idea_log_path is None:
            (output / "idea-trace.log").write_text("", encoding="utf-8")
        else:
            copy_delta(idea_log_path, trace_offset, output / "idea-trace.log")
        write_manifest(output, workspace, session, runner.commands, capsule_proof)

        if ephemeral_capsule_is_safely_deletable(
            capsule,
            capsule_proof,
            teardown_errors,
        ):
            temp_root = Path("/private/tmp").resolve()
            if (
                capsule.root.parent.resolve() != temp_root
                or not capsule.root.name.startswith("kast-capsule-")
            ):
                teardown_errors.append("refused to delete an unrecognized ephemeral capsule root")
            else:
                try:
                    shutil.rmtree(capsule.root)
                    capsule_proof["rootDeleted"] = not capsule.root.exists()
                    if not capsule_proof["rootDeleted"]:
                        teardown_errors.append("ephemeral capsule root still exists after deletion")
                    write_manifest(output, workspace, session, runner.commands, capsule_proof)
                except OSError as error:
                    teardown_errors.append(f"ephemeral capsule cleanup failed: {error}")

    if capture_failure is not None or teardown_errors:
        details = [str(capture_failure)] if capture_failure is not None else []
        details.extend(teardown_errors)
        raise ReproError(f"capture retained evidence at {output}: {'; '.join(details)}")
    report, exit_code = analyze(output)
    report["evidenceDirectory"] = str(output)
    if args.keep_session:
        report["tmuxSession"] = session
    print(json.dumps(report, indent=2))
    return exit_code


def read_manifest(directory: Path) -> tuple[dict[str, Any], dict[str, dict[str, Any]]]:
    manifest_path = directory / "manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ReproError(f"invalid evidence manifest: {error}") from error
    if not isinstance(manifest, dict):
        raise ReproError("evidence manifest root must be an object")
    if manifest.get("schemaVersion") != SCHEMA_VERSION:
        raise ReproError(f"unsupported evidence schema: {manifest.get('schemaVersion')!r}")
    if "capsule" in manifest and not isinstance(manifest["capsule"], dict):
        raise ReproError("evidence manifest capsule proof must be an object")
    capsule = manifest.get("capsule")
    if isinstance(capsule, dict):
        mode = capsule.get("mode")
        if mode not in {"PERSISTENT", "EPHEMERAL"}:
            raise ReproError("evidence manifest capsule mode is invalid")
        for field in (
            "installationContained",
            "stateContained",
            "runtimeStopSucceeded",
            "runtimeStopped",
            "rootDeleted",
        ):
            if not isinstance(capsule.get(field), bool):
                raise ReproError(f"evidence manifest capsule {field} must be a boolean")
        for field in ("terminatedProcessIds", "processesRemaining"):
            process_ids = capsule.get(field)
            if not isinstance(process_ids, list) or not all(
                isinstance(process_id, int)
                and not isinstance(process_id, bool)
                and process_id >= 0
                for process_id in process_ids
            ):
                raise ReproError(
                    f"evidence manifest capsule {field} must be an array of process IDs"
                )
    commands = manifest.get("commands")
    if not isinstance(commands, list):
        raise ReproError("evidence manifest commands must be an array")
    indexed: dict[str, dict[str, Any]] = {}
    for command in commands:
        if not isinstance(command, dict) or not isinstance(command.get("name"), str):
            raise ReproError("every evidence command must have a string name")
        if command["name"] in indexed:
            raise ReproError(f"duplicate evidence command: {command['name']}")
        indexed[command["name"]] = command
    return manifest, indexed


def transcript(directory: Path, command: dict[str, Any]) -> str:
    relative = command.get("transcript")
    if not isinstance(relative, str) or not relative:
        raise ReproError(f"command {command.get('name')} has no transcript")
    path = (directory / relative).resolve()
    if directory.resolve() not in path.parents:
        raise ReproError(f"command {command.get('name')} transcript escapes the evidence directory")
    try:
        return path.read_text(encoding="utf-8")
    except OSError as error:
        raise ReproError(f"cannot read transcript for {command.get('name')}: {error}") from error


def overlaps(first: dict[str, Any], second: dict[str, Any]) -> bool:
    values = (
        first.get("startedAtEpochMillis"), first.get("finishedAtEpochMillis"),
        second.get("startedAtEpochMillis"), second.get("finishedAtEpochMillis"),
    )
    if not all(isinstance(value, int) and not isinstance(value, bool) for value in values):
        raise ReproError("command timing fields must be integers")
    return values[0] < values[3] and values[2] < values[1]


def timed_observations(observer_text: str) -> list[TimedObservation]:
    sample_pattern = re.compile(
        r"(?ms)^__KAST_SAMPLE__=\d+\n(.*?)(?=^__KAST_SAMPLE__=\d+\n|\Z)"
    )
    observation_pattern = re.compile(
        r"(?ms)^__KAST_OBSERVATION__=(HOME|RESOLVE)\n"
        r"(.*?)(?=^__KAST_OBSERVATION__=|\Z)"
    )
    timestamp_pattern = re.compile(r"(?m)^__KAST_OBSERVATION_EPOCH_MILLIS__=(\d+)$")
    exit_pattern = re.compile(
        rf"(?m)^{re.escape(OBSERVATION_EXIT_SENTINEL)}([0-9]+)$"
    )
    observations: list[TimedObservation] = []
    for sample in sample_pattern.findall(observer_text):
        for kind, observation in observation_pattern.findall(sample):
            timestamp = timestamp_pattern.search(observation)
            exit_code = exit_pattern.search(observation)
            if timestamp is None or exit_code is None:
                raise ReproError(
                    f"{kind} observation lacks an exit code or completion timestamp"
                )
            observations.append(
                TimedObservation(
                    ObservationKind(kind),
                    observation,
                    int(exit_code.group(1)),
                    int(timestamp.group(1)),
                )
            )
    if not observations:
        raise ReproError("observer transcript has no timed observations")
    return observations


def observation_has_expected_outcome(observation: TimedObservation) -> bool:
    return observation.exit_code == 0 or (
        observation.kind == ObservationKind.RESOLVE
        and observation.exit_code == 1
        and re.search(r"(?m)^error: CONFLICT$", observation.text) is not None
    )


def observation_completed_during(
    operation: dict[str, Any],
    observations: list[TimedObservation],
    required_kind: ObservationKind,
) -> bool:
    started = operation.get("startedAtEpochMillis")
    finished = operation.get("finishedAtEpochMillis")
    if not all(
        isinstance(value, int) and not isinstance(value, bool)
        for value in (started, finished)
    ):
        raise ReproError("command timing fields must be integers")
    return any(
        observation.kind == required_kind
        and started <= observation.finished_at_epoch_millis < finished
        for observation in observations
    )


def observer_covers_operation_completion(
    operation: dict[str, Any],
    observer: dict[str, Any],
    observations: list[TimedObservation],
) -> bool:
    operation_finished = operation.get("finishedAtEpochMillis")
    observer_finished = observer.get("finishedAtEpochMillis")
    if not all(
        isinstance(value, int) and not isinstance(value, bool)
        for value in (operation_finished, observer_finished)
    ):
        raise ReproError("command timing fields must be integers")
    post_completion_kinds = {
        observation.kind
        for observation in observations
        if observation.finished_at_epoch_millis >= operation_finished
    }
    return (
        observer_finished >= operation_finished
        and post_completion_kinds == {ObservationKind.HOME, ObservationKind.RESOLVE}
    )


def ready_sample_before(
    observations: list[TimedObservation],
    finished_at_epoch_millis: int,
) -> bool:
    return any(
        observation.kind == ObservationKind.HOME
        and observation.finished_at_epoch_millis < finished_at_epoch_millis
        and re.search(r"(?m)^ready: true$", observation.text)
        for observation in observations
    )


def conflict_sample_before(
    observations: list[TimedObservation],
    finished_at_epoch_millis: int,
) -> bool:
    return any(
        observation.kind == ObservationKind.RESOLVE
        and observation.finished_at_epoch_millis < finished_at_epoch_millis
        and "error: CONFLICT" in observation.text
        and "Run `kast --help`" in observation.text
        for observation in observations
    )


def telemetry_missing_fields(directory: Path, manifest: dict[str, Any]) -> list[str]:
    relative = manifest.get("telemetry")
    if not isinstance(relative, str):
        return ["runtimeInstanceId", "semanticGenerationStart", "semanticGenerationEnd", "dumbModeState", "typedOutcome"]
    path = (directory / relative).resolve()
    if directory.resolve() not in path.parents or not path.is_file():
        return ["runtimeInstanceId", "semanticGenerationStart", "semanticGenerationEnd", "dumbModeState", "typedOutcome"]
    attributes: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict) and isinstance(value.get("attributes"), dict):
            attributes.append(value["attributes"])
    aliases = {
        "runtimeInstanceId": {"runtimeInstanceId", "kast.runtime.instance_id"},
        "semanticGenerationStart": {"semanticGenerationStart", "startGeneration", "kast.semantic.generation.start"},
        "semanticGenerationEnd": {"semanticGenerationEnd", "endGeneration", "kast.semantic.generation.end"},
        "dumbModeState": {"dumbModeState", "kast.idea.dumb_mode"},
        "typedOutcome": {"typedOutcome", "outcome", "errorCode", "kast.outcome.code"},
    }
    missing_by_request = [
        [name for name, keys in aliases.items() if not keys & set(item)]
        for item in attributes
    ]
    return min(missing_by_request, key=len) if missing_by_request else list(aliases)


def analyze(directory: Path) -> tuple[dict[str, Any], int]:
    directory = directory.resolve(strict=True)
    manifest, commands = read_manifest(directory)
    capsule = manifest.get("capsule")
    required_commands = set(REQUIRED_SCENARIO_COMMANDS)
    if isinstance(capsule, dict):
        required_commands.add("capsule-runtime-stop")
    missing_commands = sorted(required_commands - set(commands))
    if missing_commands:
        raise ReproError(
            "evidence manifest is missing required scenario commands: "
            + ", ".join(missing_commands)
        )
    invalid_status_commands = sorted(
        name
        for name in required_commands
        if (
            not isinstance(commands[name].get("exitCode"), int)
            or isinstance(commands[name].get("exitCode"), bool)
            or commands[name].get("exitCode", -1) < 0
            or not isinstance(commands[name].get("timedOut"), bool)
            or (
                commands[name].get("timedOut") is True
                and commands[name].get("exitCode") != 124
            )
        )
    )
    if invalid_status_commands:
        raise ReproError(
            "required scenario commands have invalid status evidence: "
            + ", ".join(invalid_status_commands)
        )
    if isinstance(capsule, dict):
        capsule_stop = commands["capsule-runtime-stop"]
        captured_stop_succeeded = (
            capsule_stop["exitCode"] == 0 and capsule_stop["timedOut"] is False
        )
        if capsule["runtimeStopSucceeded"] is not captured_stop_succeeded:
            raise ReproError(
                "evidence manifest capsule runtimeStopSucceeded does not match "
                "capsule-runtime-stop status"
            )
    failed_commands = sorted(
        name
        for name in REQUIRED_SUCCESSFUL_SCENARIO_COMMANDS
        if (
            not isinstance(commands[name].get("exitCode"), int)
            or isinstance(commands[name].get("exitCode"), bool)
            or commands[name].get("exitCode") != 0
            or commands[name].get("timedOut") is not False
        )
    )
    if failed_commands:
        raise ReproError(
            "required scenario commands did not complete successfully: "
            + ", ".join(failed_commands)
        )
    findings: list[Finding] = []
    cold_up = commands.get("cold-up")
    cold_observer = commands.get("cold-observer")
    refresh = commands.get("workspace-refresh")
    refresh_observer = commands.get("refresh-observer")
    incomplete_observers: list[str] = []
    complete_observers: dict[str, list[TimedObservation]] = {}
    for operation, observer, observer_name, required_kind in (
        (cold_up, cold_observer, "cold-observer", ObservationKind.HOME),
        (refresh, refresh_observer, "refresh-observer", ObservationKind.RESOLVE),
    ):
        if operation is None:
            if observer is not None:
                incomplete_observers.append(f"orphaned:{observer_name}")
            continue
        if observer is None:
            incomplete_observers.append(f"missing:{observer_name}")
            continue
        observer_text = transcript(directory, observer)
        try:
            observations = timed_observations(observer_text)
        except ReproError:
            incomplete_observers.append(str(observer.get("transcript")))
            continue
        observation_kinds = {observation.kind for observation in observations}
        if (
            observer.get("exitCode") != 0
            or observer.get("timedOut") is True
            or observation_kinds != {ObservationKind.HOME, ObservationKind.RESOLVE}
            or not overlaps(operation, observer)
            or not observer_covers_operation_completion(
                operation,
                observer,
                observations,
            )
            or not observation_completed_during(operation, observations, required_kind)
            or not all(observation_has_expected_outcome(item) for item in observations)
        ):
            incomplete_observers.append(str(observer.get("transcript")))
        else:
            complete_observers[observer_name] = observations
    if incomplete_observers:
        findings.append(
            Finding(
                FindingCode.OBSERVER_EVIDENCE_INCOMPLETE.value,
                "Transition observer evidence was missing, unsuccessful, or incomplete.",
                sorted(incomplete_observers),
            )
        )
    if cold_up and (cold_up.get("exitCode") != 0 or cold_up.get("timedOut") is True):
        findings.append(
            Finding(
                FindingCode.COLD_START_DID_NOT_CONVERGE.value,
                "Cold-start workspace ensure did not complete successfully.",
                [str(cold_up.get("transcript"))],
            )
        )
    cold_observations = complete_observers.get("cold-observer")
    if cold_up and cold_observations is not None:
        finished_at = cold_up.get("finishedAtEpochMillis")
        if isinstance(finished_at, int) and ready_sample_before(cold_observations, finished_at):
            findings.append(
                Finding(
                    FindingCode.READY_DURING_PENDING_UP.value,
                    "The public home reported READY while `kast workspace ensure` was still pending.",
                    ["transcripts/cold-up.txt", "transcripts/cold-observer.txt"],
                )
            )
    if refresh and (refresh.get("exitCode") != 0 or refresh.get("timedOut") is True):
        findings.append(
            Finding(
                FindingCode.REFRESH_DID_NOT_CONVERGE.value,
                "Focused refresh did not converge to a successful semantic result.",
                [str(refresh.get("transcript"))],
            )
        )
    refresh_observations = complete_observers.get("refresh-observer")
    if refresh_observations is not None:
        finished_at = refresh.get("finishedAtEpochMillis")
        if isinstance(finished_at, int) and conflict_sample_before(refresh_observations, finished_at):
            findings.append(
                Finding(
                    FindingCode.GENERIC_CONFLICT_DURING_REFRESH.value,
                    "A semantic read during refresh returned generic CONFLICT guidance.",
                    [str(refresh_observer.get("transcript"))],
                )
            )
    framing_evidence: list[str] = []
    transcript_sizes: dict[str, int] = {}
    for name, command in commands.items():
        text = transcript(directory, command)
        transcript_sizes[name] = len(text.encode("utf-8"))
        completion_token = command.get("completionToken")
        if not isinstance(completion_token, str) or not completion_token:
            if name in required_commands:
                raise ReproError(f"required command {name} has no completion token")
            continue
        exit_code = command.get("exitCode")
        finished_at = command.get("finishedAtEpochMillis")
        if (
            not isinstance(exit_code, int)
            or isinstance(exit_code, bool)
            or not isinstance(finished_at, int)
            or isinstance(finished_at, bool)
        ):
            if name in required_commands:
                raise ReproError(f"required command {name} has invalid completion fields")
            continue
        nonce_frames = list(
            re.finditer(
                rf"{re.escape(EXIT_SENTINEL)}{re.escape(completion_token)}:"
                r"([0-9]+):([0-9]+)\r?(?:\n|$)",
                text,
            )
        )
        if command.get("timedOut") is True:
            frame_match = next(
                (
                    match
                    for match in nonce_frames
                    if int(match.group(2)) == finished_at
                ),
                None,
            )
            if frame_match is None and not nonce_frames:
                continue
        else:
            frame_match = next(
                (
                    match
                    for match in nonce_frames
                    if int(match.group(1)) == exit_code
                    and int(match.group(2)) == finished_at
                ),
                None,
            )
        if frame_match is None:
            if name in required_commands:
                raise ReproError(
                    f"required command {name} has no exact nonce-bound completion frame"
                )
            continue
        if frame_match.start() > 0 and text[frame_match.start() - 1] != "\n":
            framing_evidence.append(str(command.get("transcript")))
    if framing_evidence:
        findings.append(
            Finding(
                FindingCode.MISSING_TRAILING_NEWLINE.value,
                "One or more CLI results were not newline-terminated in the tmux PTY.",
                sorted(framing_evidence),
            )
        )
    oversized = [
        f"{name}:{transcript_sizes[name]}"
        for name in commands
        if name in {"graph-nodes", "graph-topology", "graph-communities"}
        and transcript_sizes[name] > DEFAULT_OUTPUT_BUDGET_BYTES
    ]
    if oversized:
        findings.append(
            Finding(
                FindingCode.DEFAULT_OUTPUT_EXCEEDS_BUDGET.value,
                f"Default graph output exceeded the {DEFAULT_OUTPUT_BUDGET_BYTES}-byte agent budget.",
                sorted(oversized),
            )
        )
    missing = telemetry_missing_fields(directory, manifest)
    if missing:
        findings.append(
            Finding(
                FindingCode.TRACE_CORRELATION_INCOMPLETE.value,
                "Telemetry cannot correlate a request with runtime, generation movement, dumb mode, and typed outcome.",
                missing,
            )
        )
    if isinstance(capsule, dict):
        confinement_evidence: list[str] = []
        if capsule.get("installationContained") is not True:
            confinement_evidence.extend(str(value) for value in capsule.get("escapedPaths", []))
        if capsule.get("stateContained") is not True:
            confinement_evidence.append("stateContained=false")
        if capsule.get("mode") == "EPHEMERAL" and capsule.get("rootDeleted") is not True:
            confinement_evidence.append("rootDeleted=false")
        if confinement_evidence:
            findings.append(
                Finding(
                    FindingCode.CAPSULE_CONFINEMENT_VIOLATED.value,
                    "The capsule did not prove that installation and mutable state remained confined.",
                    confinement_evidence,
                )
            )
        remaining = capsule.get("processesRemaining")
        terminated = capsule.get("terminatedProcessIds")
        forced_cleanup = terminated if isinstance(terminated, list) else []
        if (
            capsule.get("runtimeStopped") is not True
            or not isinstance(remaining, list)
            or remaining
            or forced_cleanup
        ):
            leak_evidence = (
                [str(value) for value in remaining]
                if isinstance(remaining, list)
                else ["process proof missing"]
            )
            leak_evidence.extend(f"forced-cleanup:{value}" for value in forced_cleanup)
            if capsule.get("runtimeStopped") is not True and not leak_evidence:
                leak_evidence.append("runtimeStopped=false")
            findings.append(
                Finding(
                    FindingCode.CAPSULE_PROCESS_LEAKED.value,
                    "The capsule did not prove that every observed runtime process terminated.",
                    leak_evidence,
                )
            )
        if capsule.get("runtimeStopSucceeded") is not True:
            findings.append(
                Finding(
                    FindingCode.CAPSULE_RUNTIME_STOP_FAILED.value,
                    "The public runtime stop command failed before capsule process cleanup completed.",
                    ["transcripts/capsule-runtime-stop.txt"],
                )
            )
    findings.sort(key=lambda finding: finding.code)
    report = {
        "schemaVersion": SCHEMA_VERSION,
        "status": "OBSERVATIONS_REPRODUCED" if findings else "PASS",
        "workspaceRoot": manifest.get("workspaceRoot"),
        "sessionName": manifest.get("sessionName"),
        "findingCount": len(findings),
        "findings": [dataclasses.asdict(finding) for finding in findings],
    }
    return report, 1 if findings else 0


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(
        description="Capture a real Kast CLI session in tmux and replay its incident evidence offline.",
    )
    commands = root.add_subparsers(dest="command", required=True)
    capture_parser = commands.add_parser("capture", help="run the live tmux scenario and write an evidence bundle")
    capture_parser.add_argument("--workspace-root", type=Path, default=Path.cwd())
    capture_parser.add_argument("--file", required=True, help="workspace-relative Kotlin file used by focused operations")
    capture_parser.add_argument("--symbol", required=True, help="compiler-resolvable fully qualified Kotlin symbol")
    capture_parser.add_argument("--output-dir", type=Path)
    capture_parser.add_argument("--session-name")
    capture_parser.add_argument("--kast", default="kast")
    capsule_group = capture_parser.add_mutually_exclusive_group()
    capsule_group.add_argument(
        "--capsule-root",
        type=Path,
        help="reuse an explicit directory that owns the Kast install, state, and runtime",
    )
    capsule_group.add_argument(
        "--ephemeral-capsule",
        action="store_true",
        help="create an isolated capsule, prove process teardown, then delete it",
    )
    capture_parser.add_argument(
        "--bundle-source",
        type=Path,
        help="setup bundle archive; defaults to an active-version archive under build/setup",
    )
    capture_parser.add_argument(
        "--idea-host",
        type=Path,
        help="read-only supported IntelliJ IDEA or Android Studio .app used by the isolated indexer",
    )
    capture_parser.add_argument("--samples", type=int, default=20)
    capture_parser.add_argument("--sample-interval", type=float, default=1.0)
    capture_parser.add_argument("--transition-timeout", type=float, default=420.0)
    capture_parser.add_argument(
        "--restart-runtime",
        action="store_true",
        help="required for live non-capsule capture; stop and cold-start the exact-root runtime with telemetry enabled",
    )
    capture_parser.add_argument(
        "--exercise-plans",
        action="store_true",
        help="exercise mutation planning for a top-level --symbol without applying a plan",
    )
    capture_parser.add_argument("--keep-session", action="store_true", help="leave the completed tmux session available for attachment")
    capture_parser.add_argument("--dry-run", action="store_true", help="print the command matrix without touching tmux or Kast state")
    analyze_parser = commands.add_parser("analyze", help="replay an existing evidence bundle without a live runtime")
    analyze_parser.add_argument("--evidence-dir", type=Path, required=True)
    analyze_parser.add_argument("--format", choices=("human", "json"), default="human")
    return root


def validate_numeric_arguments(args: argparse.Namespace) -> None:
    if getattr(args, "samples", 1) <= 0:
        raise ReproError("--samples must be positive")
    sample_interval = getattr(args, "sample_interval", 1.0)
    if not math.isfinite(sample_interval) or sample_interval < 0:
        raise ReproError("--sample-interval must be non-negative")
    transition_timeout = getattr(args, "transition_timeout", 1.0)
    if not math.isfinite(transition_timeout) or transition_timeout <= 0:
        raise ReproError("--transition-timeout must be positive")


def main() -> int:
    args = parser().parse_args()
    try:
        validate_numeric_arguments(args)
        if args.command == "capture":
            return capture(args)
        report, exit_code = analyze(args.evidence_dir)
        if args.format == "json":
            print(json.dumps(report, indent=2))
        else:
            print(f"{report['status']}: {report['findingCount']} finding(s)")
            for finding in report["findings"]:
                print(f"- {finding['code']}: {finding['message']}")
        return exit_code
    except (ReproError, OSError, UnicodeError) as error:
        print(json.dumps({"schemaVersion": SCHEMA_VERSION, "status": "INVALID_EVIDENCE", "error": str(error)}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
