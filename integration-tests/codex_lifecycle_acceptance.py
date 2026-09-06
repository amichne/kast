#!/usr/bin/env python3
"""Exercise installed kast-codex and its owned broker against a private cold fixture.

Requires the existing Codex account and configured default model. This invokes a real
cloud turn. It never edits account configuration or launches a broker separately.
The evidence directory must not exist. Raw PTY output is bounded and kept private.
"""
from __future__ import annotations

import argparse
from collections import Counter
import errno
import fcntl
import json
import os
from pathlib import Path
import pty
import re
import selectors
import shutil
import signal
import sqlite3
import struct
import subprocess
import sys
import termios
import time

from lifecycle_convergence_acceptance import (
    EvidenceError, parse_spans, physical_runtime_directory, require, topology_rows,
)

EXPECTED_TOOLS = ['symbol_lookup', 'symbol_inspect', 'impact_analyze', 'impact_analyze']
LOOKUP_ARGUMENTS = {"mode": "name", "query": "genericRead", "kind": "symbol", "match": "exact-name", "limit": 10}
IMPACT_ARGUMENTS = {"selector": "<exact symbol.selector from inspection>", "relation": "callees",
                    "maximumDepth": 2, "maximumResults": 20}
PROMPT = (
    'Use only Kast semantic tools. Perform exactly these four Kast tool calls in order. '
    'If using functions.exec, resolve the actual callable tool name from the provided tool metadata. '
    'For each call, await its result and emit the entire returned value with text(result). '
    'Do not assume a content array or extract only result.content: Kast may return a direct structured object. '
    'Use the wrapper wait helper until a yielded call completes, then read the emitted complete result. '
    'Use the exact argument names and JSON shapes shown here, from the installed tool schema. '
    '1. Call symbol_lookup with ' + json.dumps(LOOKUP_ARGUMENTS) + '. '
    '2. From the matching genericRead declaration, copy its complete candidateSelector token. '
    'Call symbol_inspect with {"candidate":"<that exact candidateSelector token>"}, '
    'substituting the real token for the placeholder. '
    '3. Copy the complete symbol.selector token returned by inspection. Call impact_analyze with '
    + json.dumps(IMPACT_ARGUMENTS) + ', substituting that real selector token for the placeholder. '
    '4. Repeat precisely the same impact_analyze JSON with the same selector and limits. '
    'Apart from tool orchestration and wait helpers, do not call any other tool. '
    'Do not start, sync, build, inspect runtime status, or stop '
    'a runtime. After the four successful calls, briefly report their results and finish.'
)

ANSI = re.compile(rb'\x1b(?:\[[0-?]*[ -/]*[@-~]|\][^\x07\x1b]*(?:\x07|\x1b\\))')
MAX_TRANSCRIPT = 16 * 1024 * 1024
MAX_ACTIVITY_LINE = 1024 * 1024
# Keep command text and Enter in separate terminal reads. A TUI can otherwise
# classify the single burst as pasted composer text instead of a submission.
GRACEFUL_EXIT_STEPS = ((8.0, 'quit-text', b'/quit'), (8.5, 'quit-submit', b'\r'),
                       (18.0, 'quit-retry-text', b'\x15/quit'), (18.5, 'quit-retry-submit', b'\r'),
                       (28.0, 'eof', b'\x04'))
GRACEFUL_EXIT_TIMEOUT = 38.0


class Activities:
    """Accept complete broker lifecycle records, retaining their explicit invocation identities."""
    def __init__(self):
        self.pending = b''
        self.events: list[dict] = []
        self.started: dict[str, dict] = {}
        self.finished: list[dict] = []
        self.context: tuple[str, str] | None = None

    def feed(self, data: bytes) -> None:
        self.pending += data
        while b'\n' in self.pending:
            line, self.pending = self.pending.split(b'\n', 1)
            require(len(line) <= MAX_ACTIVITY_LINE, 'broker output line exceeded evidence bound')
            clean = ANSI.sub(b'', line).replace(b'\r', b'').decode('utf-8', errors='replace')
            marker = re.search(r'\{\s*"component"\s*:\s*"kast-broker"', clean)
            if marker is None:
                continue
            try:
                document, _ = json.JSONDecoder().raw_decode(clean[marker.start():])
            except ValueError as error:
                raise EvidenceError('malformed broker activity JSON') from error
            self.accept(document)
        require(len(self.pending) <= MAX_ACTIVITY_LINE, 'unterminated output exceeded evidence bound')

    def accept(self, document: dict) -> None:
        self.events.append(document)
        event = document.get('event')
        if event == 'broker-startup-stage':
            require(document.get('outcome') in ('started', 'completed'), 'broker startup rejected')
            return
        require(event in ('tool-call-started', 'tool-call-finished'), 'unknown broker activity event')
        require(document.get('namespace') == 'kast', 'unexpected broker tool namespace')
        require(document.get('tool') in EXPECTED_TOOLS, 'unexpected tool or model-visible lifecycle call')
        for key in ('threadId', 'turnId', 'callId'):
            require(isinstance(document.get(key), str) and bool(document[key]), f'missing broker {key}')
        context = (document['threadId'], document['turnId'])
        if self.context is None:
            self.context = context
        require(context == self.context, 'calls crossed Codex thread or turn identity')
        call = document['callId']
        if event == 'tool-call-started':
            require(call not in self.started, 'duplicate broker call start')
            position = len(self.started)
            require(position < len(EXPECTED_TOOLS), 'unexpected additional tool call')
            require(document['tool'] == EXPECTED_TOOLS[position], 'semantic tool call order differed')
            require(len(self.finished) == position, 'semantic calls overlapped or prior call did not finish')
            self.started[call] = document
        else:
            require(call in self.started, 'broker completion lacked matching start')
            require(not any(previous['callId'] == call for previous in self.finished), 'duplicate broker completion')
            require(document['tool'] == self.started[call]['tool'], 'broker tool changed within a call')
            require(document.get('completion') == 'completed', 'broker invocation rejected or cancelled')
            self.finished.append(document)

    def complete(self) -> bool:
        return len(self.finished) == len(EXPECTED_TOOLS)


def require_no_host(codex_home: Path) -> list[Path]:
    sockets = [codex_home / 'kast-integration/client/app-server-control.sock',
               codex_home / 'kast-integration/upstream.sock']
    require(not any(os.path.lexists(path) for path in sockets),
            'existing Codex integration socket; close its owning kast-codex before this run')
    return sockets


def command(kast: Path, workspace: Path, environment: dict, evidence: Path,
            name: str, *arguments: str) -> dict:
    try:
        result = subprocess.run([str(kast), *arguments], cwd=workspace, env=environment,
                                capture_output=True, timeout=60)
    except subprocess.TimeoutExpired as error:
        (evidence / f'{name}.stdout').write_bytes(error.stdout or b'')
        (evidence / f'{name}.stderr').write_bytes(error.stderr or b'')
        raise EvidenceError(f'{name} timed out') from error
    (evidence / f'{name}.stdout').write_bytes(result.stdout)
    (evidence / f'{name}.stderr').write_bytes(result.stderr)
    require(result.returncode == 0, f'{name} exited {result.returncode}')
    document = json.loads(result.stdout)
    require(isinstance(document, dict) and document.get('status') == 'complete', f'{name} did not complete')
    return document


def stop_owned(kast: Path, workspace: Path, environment: dict, evidence: Path) -> None:
    stopped = command(kast, workspace, environment, evidence, 'stop', 'stop')
    require(stopped.get('runtime') == 'stopped', 'owned runtime did not stop')


def terminate_owned(process: subprocess.Popen, master: int | None = None, drain=None,
                    grace_seconds: float = 3, signal_seconds: float = 3) -> dict:
    """Bounded cleanup for our new-session child; failure is recorded without raising."""
    stages = []

    def wait_draining(seconds: float) -> bool:
        deadline = time.monotonic() + seconds
        while process.poll() is None and time.monotonic() < deadline:
            if drain is None:
                time.sleep(min(0.05, max(0, deadline - time.monotonic())))
            else:
                try:
                    drain(min(0.1, max(0, deadline - time.monotonic())))
                except (OSError, ValueError) as error:
                    stages.append({'stage': 'drain', 'outcome': type(error).__name__})
                    return False
        return process.poll() is not None

    def result() -> dict:
        code = process.poll()
        return {'outcome': 'stopped' if code is not None else 'not-reaped', 'exitCode': code, 'stages': stages}

    if process.poll() is not None:
        return result()
    if master is not None:
        for name, request in (('quit', b'/quit\r'), ('eof', b'\x04')):
            try:
                os.write(master, request)
                stages.append({'stage': name, 'outcome': 'requested'})
            except OSError as error:
                stages.append({'stage': name, 'outcome': type(error).__name__})
            if wait_draining(grace_seconds):
                return result()
    for name, requested_signal in (('terminate', signal.SIGTERM), ('kill-group', signal.SIGKILL)):
        if process.poll() is not None:
            return result()
        try:
            # start_new_session=True established ownership. If that group changed, signal only
            # the exact child retained by Popen, never a different shared process group.
            if os.getpgid(process.pid) == process.pid:
                os.killpg(process.pid, requested_signal)
            else:
                process.send_signal(requested_signal)
            stages.append({'stage': name, 'outcome': 'requested'})
        except OSError as error:
            stages.append({'stage': name, 'outcome': type(error).__name__})
        if wait_draining(signal_seconds):
            return result()
    if process.poll() is None:
        try:
            process.kill()
            stages.append({'stage': 'kill-exact-child', 'outcome': 'requested'})
        except OSError as error:
            stages.append({'stage': 'kill-exact-child', 'outcome': type(error).__name__})
        wait_draining(signal_seconds)
    return result()


def run_client(host: Path, workspace: Path, environment: dict, evidence: Path,
               timeout: int, sockets: list[Path]) -> Activities:
    activities = Activities()
    master, slave = pty.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 50, 180, 0, 0))
    process = None
    raw_count = 0
    deadline = time.monotonic() + timeout
    completed_at = None
    quit_sent = None
    graceful_steps = []
    query_tail = b''
    sockets_seen: set[str] = set()
    primary_error = None
    cleanup = {'outcome': 'not-started', 'stages': []}
    selector = selectors.DefaultSelector()
    transcript = (evidence / 'client.raw').open('wb')

    def read_pty(wait_seconds: float, validate: bool) -> None:
        nonlocal raw_count, query_tail
        for _, _ in selector.select(timeout=wait_seconds):
            try:
                data = os.read(master, 65536)
            except OSError as error:
                if error.errno != errno.EIO:
                    raise
                data = b''
            if not data:
                selector.unregister(master)
                continue
            remaining = max(0, MAX_TRANSCRIPT - raw_count)
            transcript.write(data[:remaining])
            transcript.flush()
            raw_count += len(data)
            if validate:
                require(raw_count <= MAX_TRANSCRIPT, 'PTY transcript exceeded 16 MiB evidence bound')
                activities.feed(data)
            queries = query_tail + data
            for query, response in ((b'\x1b[6n', b'\x1b[1;1R'),
                                    (b'\x1b]11;?', b'\x1b]11;rgb:0000/0000/0000\x1b\\')):
                offset = queries.rfind(query)
                if offset >= 0 and offset + len(query) > len(query_tail):
                    os.write(master, response)
            query_tail = queries[-16:]

    try:
        process = subprocess.Popen(
            [str(host), '--no-alt-screen', '--sandbox', 'read-only', '--ask-for-approval', 'never',
             '--disable', 'shell_tool', '--disable', 'plugins', '--disable', 'apps', PROMPT],
            cwd=workspace, env=dict(environment, TERM='xterm-256color'), stdin=slave, stdout=slave,
            stderr=slave, start_new_session=True, close_fds=True,
        )
        os.close(slave)
        slave = -1
        selector.register(master, selectors.EVENT_READ)
        while True:
            now = time.monotonic()
            require(now < deadline, 'installed Codex semantic journey timed out')
            for path in sockets:
                if path.is_socket():
                    sockets_seen.add(str(path))
            read_pty(0.2, validate=True)
            if activities.complete() and completed_at is None:
                completed_at = now
            if completed_at is not None and process.poll() is None:
                elapsed = now - completed_at
                next_step = len(graceful_steps)
                if next_step < len(GRACEFUL_EXIT_STEPS):
                    delay, stage, request = GRACEFUL_EXIT_STEPS[next_step]
                    if elapsed >= delay:
                        os.write(master, request)
                        graceful_steps.append({'stage': stage, 'elapsedSeconds': round(elapsed, 3)})
                        if quit_sent is None:
                            quit_sent = now
                require(elapsed < GRACEFUL_EXIT_TIMEOUT,
                        'Codex client did not exit after bounded graceful shutdown')
            if process.poll() is not None:
                require(activities.complete(), 'Codex exited before all four semantic invocations completed')
                require(process.returncode == 0, f'Codex client exited {process.returncode}')
                require(quit_sent is not None, 'Codex client exited before owned graceful shutdown')
                break
        require(sockets_seen == {str(path) for path in sockets}, 'integration host sockets were not both observed')
        require(not any(os.path.lexists(path) for path in sockets), 'owned integration host left socket state behind')
        return activities
    except BaseException as error:
        primary_error = error
        raise
    finally:
        try:
            if process is not None:
                cleanup = terminate_owned(process, master, lambda seconds: read_pty(seconds, validate=False))
        except BaseException as error:
            # Even an unexpected cleanup exception cannot erase the original proof failure.
            cleanup = {'outcome': 'cleanup-error', 'failure': type(error).__name__, 'stages': []}
        finally:
            for close in (selector.close, transcript.close, lambda: os.close(master)):
                try:
                    close()
                except OSError as error:
                    cleanup.setdefault('closeFailures', []).append(type(error).__name__)
            if slave >= 0:
                try:
                    os.close(slave)
                except OSError as error:
                    cleanup.setdefault('closeFailures', []).append(type(error).__name__)
            observation = {
                'bytesReceived': raw_count, 'transcriptTruncated': raw_count > MAX_TRANSCRIPT,
                'hostPid': process.pid if process else None,
                'hostExit': process.returncode if process else None,
                'integrationSocketsObserved': sorted(sockets_seen),
                'gracefulExitRequested': quit_sent is not None, 'cleanup': cleanup,
                'gracefulExitSteps': graceful_steps,
                'primaryFailure': str(primary_error)[:2048] if primary_error is not None else None,
            }
            write_failures = []
            for path, document in (
                (evidence / 'broker-activity.jsonl', ''.join(json.dumps(event) + '\n' for event in activities.events)),
                (evidence / 'client-observation.json', json.dumps(observation, indent=2) + '\n'),
            ):
                try:
                    path.write_text(document)
                except OSError as error:
                    write_failures.append(type(error).__name__)
            if primary_error is None:
                require(not write_failures, 'client cleanup evidence could not be written')
                require(cleanup.get('outcome') in ('stopped', 'not-started') and not cleanup.get('closeFailures'),
                        'owned Codex process cleanup did not complete; see client-observation.json')


def outcome(span: dict) -> str | None:
    for attribute in span.get('attributes', []):
        if attribute.get('key') == 'io.github.amichne.kast.outcome':
            return attribute.get('value', {}).get('stringValue')
    return None


def verify_semantic_evidence(spans: list[dict], rows: list[dict]) -> dict:
    counts = Counter(span['name'] for span in spans)
    expected = {'kast.symbol.discovery': 1, 'kast.topology.build': 2, 'kast.traversal.run': 2,
                'kast.topology.extraction': 1, 'kast.topology.publication': 1}
    for name, count in expected.items():
        matching = [span for span in spans if span['name'] == name]
        require(len(matching) == count, f'{name}: expected {count} terminal spans, observed {len(matching)}')
        require(all(outcome(span) == 'complete' for span in matching), f'{name}: non-complete or unknown outcome')
    require(counts['kast.workspace.refresh'] == 0, 'unchanged Codex journey refreshed workspace roots')
    require(len(rows) == 1 and rows[0].get('generation') == 1, 'cold journey did not publish exactly one topology generation')
    require(rows[0].get('file_count', 0) > 0, 'published topology omitted fixture files')
    return {'spanCounts': dict(counts), 'topologyRows': rows}


def runtime_evidence(runtime: Path, workspace: Path, evidence: Path) -> dict:
    matches = []
    for path in runtime.glob('*.endpoint.json'):
        endpoint = json.loads(path.read_text())
        if endpoint.get('canonicalRoot') == str(workspace):
            matches.append(endpoint)
    require(len(matches) == 1, 'expected exactly one owned runtime endpoint')
    endpoint = matches[0]
    state = Path(endpoint['socketPath'] + '.state')
    require(state.parent == runtime, 'runtime state escaped private namespace')
    deadline = time.monotonic() + 30
    while True:
        trace = state / 'otel/traces.jsonl'
        raw = trace.read_bytes() if trace.exists() else b''
        require(len(raw) <= MAX_TRANSCRIPT, 'trace evidence exceeded 16 MiB bound')
        spans = parse_spans(raw)
        if sum(span['name'] == 'kast.traversal.run' for span in spans) >= 2:
            break
        require(time.monotonic() < deadline, 'terminal traversal telemetry was not exported')
        time.sleep(0.2)
    (evidence / 'traces.jsonl').write_bytes(raw)
    rows = topology_rows(state / 'topology.sqlite', workspace)
    observation = verify_semantic_evidence(spans, rows)
    observation.update(endpoint=endpoint, runtimeState=str(state))
    (evidence / 'runtime-observation.json').write_text(json.dumps(observation, indent=2) + '\n')
    return observation


def execute(args: argparse.Namespace) -> dict:
    kast = args.kast.resolve(strict=True)
    host = args.kast_codex.resolve(strict=True) if args.kast_codex else kast.with_name('kast-codex').resolve(strict=True)
    fixture = args.fixture.resolve(strict=True)
    evidence = args.evidence.resolve()
    require(not evidence.exists(), 'evidence directory already exists; choose a new path')
    environment = dict(os.environ)
    codex_home = Path(environment.get('CODEX_HOME', str(Path.home() / '.codex'))).resolve(strict=True)
    sockets = require_no_host(codex_home)
    evidence.mkdir(parents=True, mode=0o700)
    workspace = evidence / 'workspace'
    shutil.copytree(fixture, workspace, ignore=shutil.ignore_patterns('.git', '.gradle', 'build', '.idea', '.kast'))
    environment.update(KAST_CACHE_ROOT=str(evidence / 'caches'), KAST_RUNTIME_DIRECTORY=str(evidence / 'endpoints'),
                       KAST_RUNTIME_STORE=str(evidence / 'store'))
    runtime = physical_runtime_directory(evidence / 'endpoints')
    require(not runtime.exists(), 'private runtime namespace already exists')
    summary = {'outcome': 'failed', 'evidence': str(evidence), 'workspace': str(workspace)}
    try:
        before = command(kast, workspace, environment, evidence, 'before')
        require(before.get('runtime') == 'stopped' and before.get('cache', {}).get('state') == 'absent',
                'fixture was not cold before the Codex request')
        activities = run_client(host, workspace, environment, evidence, args.timeout, sockets)
        observation = runtime_evidence(runtime, workspace, evidence)
        after = command(kast, workspace, environment, evidence, 'after')
        require(after.get('runtime') == 'running', 'semantic request did not leave the owned runtime running')
        require(after.get('runtimeId') == observation['endpoint'].get('runtimeId'), 'endpoint/runtime identity differs')
        bootstrap = after.get('bootstrap', {})
        require(bootstrap.get('state') == 'ready' and bool(bootstrap.get('attemptId')), 'bootstrap did not reach ready')
        attempts = set()
        for path in (evidence / 'caches').glob('*/log/startup.log'):
            for line in path.read_text().splitlines():
                if 'bootstrap-document: ' in line:
                    document = json.loads(line.split('bootstrap-document: ', 1)[1])
                    attempts.add(document['bootstrap']['attemptId'])
        require(attempts == {bootstrap['attemptId']}, 'bootstrap evidence did not contain exactly one matching attempt')
        summary.update(outcome='passed', threadId=activities.context[0], turnId=activities.context[1],
                       completedTools=[event['tool'] for event in activities.finished],
                       runtimeId=after['runtimeId'], bootstrapAttemptId=bootstrap['attemptId'],
                       topologyGeneration=observation['topologyRows'][0]['generation'])
    except (EvidenceError, OSError, ValueError, KeyError, sqlite3.Error) as error:
        summary.update(outcome='failed', reason=str(error))
    finally:
        try:
            stop_owned(kast, workspace, environment, evidence)
            summary['ownedRuntimeStopped'] = True
        except (EvidenceError, OSError, ValueError, KeyError) as error:
            summary.update(outcome='failed', cleanupFailure=str(error), ownedRuntimeStopped=False)
        (evidence / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kast', type=Path, required=True)
    parser.add_argument('--kast-codex', type=Path)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--timeout', type=int, default=900)
    args = parser.parse_args()
    try:
        require(args.timeout > 0, 'timeout must be positive')
        summary = execute(args)
    except (EvidenceError, OSError, ValueError, KeyError, sqlite3.Error) as error:
        summary = {'outcome': 'failed', 'reason': str(error)}
    print(json.dumps(summary), flush=True)
    return 0 if summary['outcome'] == 'passed' else 1


if __name__ == '__main__':
    sys.exit(main())
