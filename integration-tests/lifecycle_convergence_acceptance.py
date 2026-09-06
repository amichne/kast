#!/usr/bin/env python3
"""Prove installed lifecycle convergence in a private copy of the identity fixture.

python3 integration-tests/lifecycle_convergence_acceptance.py \
    --kast /path/to/bin/kast --fixture fixtures/topology-identity-workspace \
    --evidence /tmp/kast-lifecycle-acceptance

The evidence directory must be new. --reuse repeats the journey in this driver's
existing workspace and reports that cold startup was not exercised. The driver
never stops a sidecar. Raw command output, OTLP spans, SQLite row projections,
and the summary remain under evidence/runs/<run>. Source movement is a disk edit;
no claim about VFS synchronization or Gradle import follows from a source hash.
"""
from __future__ import annotations

import argparse
import concurrent.futures
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
import uuid
from collections import Counter


class EvidenceError(RuntimeError):
    """Installed behavior or its required durable evidence did not satisfy a gate."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise EvidenceError(message)


def physical_runtime_directory(logical: Path) -> Path:
    # RuntimeSocketDirectory.from maps the logical namespace using 12 SHA-256 bytes.
    namespace = hashlib.sha256(str(logical).encode()).hexdigest()[:24]
    return Path('/tmp') / ('kast-runtime-' + namespace)


def parse_spans(raw: bytes) -> list[dict]:
    """Read complete OTLP records only; the exporter can be appending its last line."""
    spans = []
    seen = set()
    for line in raw.splitlines(keepends=True):
        if not line.endswith(b'\n'):
            continue
        document = json.loads(line)
        for resource in document['resourceSpans']:
            for scope in resource['scopeSpans']:
                for span in scope['spans']:
                    identity = (span['traceId'], span['spanId'])
                    require(identity not in seen, 'duplicate exported span identity')
                    seen.add(identity)
                    spans.append(span)
    return spans


def topology_rows(database: Path, root: Path) -> list[dict]:
    if not database.exists():
        return []
    with closing(sqlite3.connect(database.as_uri() + '?mode=ro', uri=True, timeout=10)) as connection:
        connection.row_factory = sqlite3.Row
        tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        require('topology_snapshot_v3' in tables, 'topology database has an unsupported schema')
        return [dict(row) for row in connection.execute(
            'SELECT snapshot_id, workspace_root, generation, source_state, digest, '
            'file_count, symbol_count, edge_count FROM topology_snapshot_v3 '
            'WHERE workspace_root = ? ORDER BY snapshot_id', (str(root),))]


def candidate(result: dict, name: str) -> str:
    require(result.get('status') == 'complete', f'{name}: discovery did not complete')
    matches = [item['candidateSelector'] for item in result.get('items', [])
               if item.get('type') == 'declaration' and item.get('name') == name]
    require(len(matches) == 1, f'{name}: expected exactly one declaration, observed {len(matches)}')
    return matches[0]


def generation(result: dict) -> int:
    require(result.get('status') == 'complete', 'traversal did not complete')
    value = result.get('graph', {}).get('snapshot', {}).get('generation')
    require(type(value) is int and value > 0, 'traversal omitted its generation')
    return value


class Acceptance:
    def __init__(self, args: argparse.Namespace):
        self.kast = args.kast.resolve(strict=True)
        self.fixture = args.fixture.resolve(strict=True)
        self.base = args.evidence.resolve()
        self.reuse = args.reuse
        self.timeout = args.timeout
        marker = self.base / 'lifecycle-acceptance.json'
        if args.reuse:
            require(marker.is_file(), '--reuse requires a workspace created by this driver')
            require(json.loads(marker.read_text()).get('fixture') == str(self.fixture),
                    '--reuse fixture differs from the recorded fixture')
        else:
            require(not self.base.exists() or not any(self.base.iterdir()),
                    'evidence directory is not empty; choose a new directory or --reuse')
            self.base.mkdir(parents=True, exist_ok=True)
            shutil.copytree(self.fixture, self.base / 'workspace',
                            ignore=shutil.ignore_patterns('.git', '.gradle', 'build', '.idea', '.kast'))
            marker.write_text(json.dumps({'schema': 1, 'fixture': str(self.fixture)}) + '\n')
        self.workspace = (self.base / 'workspace').resolve(strict=True)
        self.run = self.base / 'runs' / uuid.uuid4().hex
        self.run.mkdir(parents=True)
        self.cache = self.base / 'caches'
        logical = self.base / 'endpoints'
        self.runtime = physical_runtime_directory(logical)
        self.environment = dict(os.environ, KAST_CACHE_ROOT=str(self.cache),
                                KAST_RUNTIME_DIRECTORY=str(logical), KAST_RUNTIME_STORE=str(self.base / 'store'),
                                CODEX_EXECUTABLE=str(self.base / 'codex-absent'),
                                CODEX_HOME=str(self.base / 'codex-home-absent'))
        self.lock = threading.Lock()
        self.inspection_args: tuple[str, ...] = ()
        self.checks: list[str] = []
        self.state: Path | None = None

    def call(self, name: str, *args: str, complete: bool = True) -> dict:
        started = time.monotonic()
        try:
            process = subprocess.run([str(self.kast), *args], cwd=self.workspace,
                                     env=self.environment, capture_output=True, timeout=self.timeout)
        except subprocess.TimeoutExpired as error:
            (self.run / (name + '.stdout')).write_bytes(error.stdout or b'')
            (self.run / (name + '.stderr')).write_bytes(error.stderr or b'')
            raise EvidenceError(f'{name}: timed out after {self.timeout}s') from error
        (self.run / (name + '.stdout')).write_bytes(process.stdout)
        (self.run / (name + '.stderr')).write_bytes(process.stderr)
        try:
            document = json.loads(process.stdout)
        except (ValueError, UnicodeDecodeError) as error:
            raise EvidenceError(f'{name}: stdout is not JSON; see {self.run}') from error
        require(isinstance(document, dict), f'{name}: output is not an object')
        with self.lock:
            with (self.run / 'commands.jsonl').open('a') as stream:
                stream.write(json.dumps({'case': name, 'exit': process.returncode,
                                         'elapsedSeconds': time.monotonic() - started,
                                         'status': document.get('status')}) + '\n')
        require(process.returncode == 0, f'{name}: exit {process.returncode}; see {self.run}')
        if complete:
            require(document.get('status') == 'complete', f'{name}: {document}')
        return document

    def inspect(self, name: str, initial: bool = False) -> dict:
        return self.call(name, *self.inspection_args)

    def bind_state(self, inspection: dict) -> dict:
        matches = []
        for path in self.runtime.glob('*.endpoint.json'):
            doc = json.loads(path.read_text())
            if doc.get('canonicalRoot') == str(self.workspace):
                matches.append(doc)
        require(len(matches) == 1, 'expected exactly one endpoint for the private workspace')
        endpoint = matches[0]
        require(endpoint.get('runtimeId') == inspection.get('runtimeId'), 'endpoint/runtime identity differs')
        self.state = Path(endpoint['socketPath'] + '.state')
        require(self.state.parent == self.runtime, 'endpoint escaped the private runtime namespace')
        return endpoint

    def observe(self, name: str, minimum: dict[str, int]) -> tuple[Counter, list[dict]]:
        require(self.state is not None, 'runtime state is not bound')
        trace = self.state / 'otel/traces.jsonl'
        deadline = time.monotonic() + 30
        while True:
            raw = trace.read_bytes() if trace.exists() else b''
            counts = Counter(span['name'] for span in parse_spans(raw))
            if all(counts[key] >= value for key, value in minimum.items()):
                break
            require(time.monotonic() < deadline, f'{name}: required terminal telemetry was not exported: {counts}')
            time.sleep(0.2)
        rows = topology_rows(self.state / 'topology.sqlite', self.workspace)
        (self.run / (name + '.traces.jsonl')).write_bytes(raw)
        (self.run / (name + '.observation.json')).write_text(json.dumps(
            {'spanCounts': dict(counts), 'topologyRows': rows}, indent=2) + '\n')
        return counts, rows

    def passed(self, name: str) -> None:
        self.checks.append(name)
        print(json.dumps({'gate': name, 'outcome': 'passed', 'evidence': str(self.run)}), flush=True)

    def discover(self, case: str, name: str) -> dict:
        return self.call(case, 'symbol', 'discover', '--query', name, '--match', 'exact-name', '--limit', '10')

    def exact(self, case: str, selector: str) -> str:
        return self.call(case, 'symbol', 'inspect', '--candidate', selector)['symbol']['selector']

    def traverse(self, case: str, selector: str, complete: bool = True) -> dict:
        return self.call(case, 'traversal', 'run', '--selector', selector, '--relation', 'callees',
                         '--maximum-depth', '2', '--maximum-results', '20', complete=complete)

    def execute(self) -> dict:
        before = self.inspect('before', initial=True)
        if not self.reuse:
            require(before.get('runtime') == 'stopped' and before.get('cache', {}).get('state') == 'absent',
                    'fresh workspace was not cold before semantic requests')
            require(not list(self.runtime.glob('*.state')), 'fresh runtime namespace already has state')
        barrier = threading.Barrier(2)
        def cold(index: int) -> dict:
            barrier.wait(timeout=10)
            return self.discover(f'concurrent-semantic-{index}', 'genericRead')
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            requests = [pool.submit(cold, index) for index in range(2)]
            results = [request.result() for request in requests]
        old_candidate = candidate(results[0], 'genericRead')
        require(candidate(results[1], 'genericRead') == old_candidate, 'concurrent requests returned different candidates')
        running = self.inspect('running')
        require(running.get('runtime') == 'running', 'semantic startup did not leave a running runtime')
        endpoint = self.bind_state(running)
        bootstrap = running.get('bootstrap', {})
        require(bootstrap.get('state') == 'ready' and bool(bootstrap.get('attemptId')), 'bootstrap not ready')
        baseline, rows = self.observe('after-semantic-startup', {'kast.symbol.discovery': 2})
        if not self.reuse:
            require(not rows and baseline['kast.topology.extraction'] == 0, 'cold semantic request eagerly built topology')
        self.passed('concurrent-semantic-reuse' if self.reuse else 'cold-concurrent-semantic-startup')
        old_exact = self.exact('inspect-candidate', old_candidate)
        self.call('source', 'source', 'read', '--anchor', old_exact)
        self.call('diagnostic', 'diagnostic', 'check', '--scope', 'src/main/kotlin/IdentityFixture.kt', '--limit', '100')
        self.call('relation', 'relation', 'read', '--selector', old_exact, '--relation', 'callees', '--limit', '20')
        require(candidate(self.discover('reused-symbol', 'genericRead'), 'genericRead') == old_candidate,
                'unchanged source changed candidate identity')
        counts, after_rows = self.observe('after-nontraversal', {
            'kast.symbol.discovery': baseline['kast.symbol.discovery'] + 1,
            'kast.relation.read': baseline['kast.relation.read'] + 1})
        require(after_rows == rows and counts['kast.topology.extraction'] == baseline['kast.topology.extraction']
                and counts['kast.topology.publication'] == baseline['kast.topology.publication'],
                'symbol/source/diagnostic/relation acquired topology')
        require(counts['kast.workspace.refresh'] == baseline['kast.workspace.refresh'],
                'unchanged semantic commands refreshed workspace roots')
        self.passed('nontraversal-does-not-build-topology')
        barrier = threading.Barrier(2)
        def first_traversal(index: int) -> dict:
            barrier.wait(timeout=10)
            return self.traverse(f'concurrent-traversal-{index}', old_exact)
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            requests = [pool.submit(first_traversal, index) for index in range(2)]
            traversals = [request.result() for request in requests]
        old_generation = generation(traversals[0])
        require(generation(traversals[1]) == old_generation, 'concurrent traversal generations differ')
        built, built_rows = self.observe('after-first-traversal', {'kast.traversal.run': counts['kast.traversal.run'] + 2})
        expected_builds = 0 if any(row['generation'] == old_generation for row in rows) else 1
        require(built['kast.topology.extraction'] - counts['kast.topology.extraction'] == expected_builds,
                'concurrent traversal did not share one extraction')
        require(built['kast.topology.publication'] - counts['kast.topology.publication'] == expected_builds,
                'concurrent traversal did not share one publication')
        require(len(built_rows) == len(rows) + expected_builds, 'unexpected number of published topology rows')
        self.passed('concurrent-traversal-shares-lazy-topology')
        require(generation(self.traverse('reused-traversal', old_exact)) == old_generation, 'reused traversal generation moved')
        reused, reused_rows = self.observe('after-reused-traversal', {'kast.traversal.run': built['kast.traversal.run'] + 1})
        require(reused_rows == built_rows and reused['kast.topology.extraction'] == built['kast.topology.extraction']
                and reused['kast.topology.publication'] == built['kast.topology.publication'], 'reused traversal rebuilt topology')
        self.passed('traversal-reuses-published-topology')
        source = self.workspace / 'src/main/kotlin/IdentityFixture.kt'
        successor_name = 'lifecycleSuccessor' + uuid.uuid4().hex
        with source.open('a') as stream:
            stream.write(f'\nfun {successor_name}() = 42\n')
        fresh_candidate = candidate(self.discover('successor-discovery', successor_name), successor_name)
        fresh_exact = self.exact('successor-inspection', fresh_candidate)
        rejected = self.call('old-candidate', 'symbol', 'inspect', '--candidate', old_candidate, complete=False)
        require(rejected.get('status') == 'rejected' and rejected.get('reason') == 'candidate-stale', 'old candidate was not rejected as stale')
        rejected = self.traverse('old-exact-traversal', old_exact, complete=False)
        require(rejected.get('status') == 'rejected' and rejected.get('reason') == 'selector-stale', 'old exact selector was not rejected as stale')
        fresh_generation = generation(self.traverse('successor-traversal', fresh_exact))
        require(fresh_generation == old_generation + 1, 'source movement did not publish exactly one successor generation')
        final_counts, final_rows = self.observe('after-source-movement', {'kast.traversal.run': reused['kast.traversal.run'] + 2})
        require(len(final_rows) == len(reused_rows) + 1 and final_rows[-1]['generation'] == fresh_generation,
                'successor topology row is absent or duplicated')
        require(final_rows[-1]['source_state'] != reused_rows[-1]['source_state']
                and final_rows[-1]['digest'] != reused_rows[-1]['digest'], 'successor topology retained old content identity')
        require(final_counts['kast.topology.extraction'] - reused['kast.topology.extraction'] == 1
                and final_counts['kast.topology.publication'] - reused['kast.topology.publication'] == 1,
                'source movement did not build exactly one successor topology')
        self.passed('source-movement-rejects-stale-selectors-and-publishes-successor-topology')
        after = self.inspect('after')
        require(after.get('runtimeId') == running.get('runtimeId') and after.get('runtime') == 'running'
                and after.get('bootstrap', {}).get('attemptId') == bootstrap['attemptId'], 'semantic reuse changed runtime/bootstrap identity')
        require(self.bind_state(after) == endpoint, 'semantic reuse changed endpoint identity')
        attempts = set()
        for path in self.cache.glob('*/log/startup.log'):
            for line in path.read_text(errors='replace').splitlines():
                if 'bootstrap-document: ' in line:
                    doc = json.loads(line.split('bootstrap-document: ', 1)[1])
                    attempts.add(doc['bootstrap']['attemptId'])
        require(attempts == {bootstrap['attemptId']}, f'expected one logged bootstrap attempt, observed {attempts}')
        self.passed('one-runtime-endpoint-and-bootstrap-attempt')
        return {'outcome': 'passed', 'coldStartupExercised': not self.reuse, 'checks': self.checks,
                'runtimeId': after['runtimeId'], 'bootstrapAttemptId': bootstrap['attemptId'],
                'generations': [old_generation, fresh_generation], 'evidence': str(self.run),
                'runtimeState': str(self.state), 'workspace': str(self.workspace)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--kast', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--reuse', action='store_true', help='reuse this driver\'s existing evidence/workspace; skips cold startup proof')
    parser.add_argument('--timeout', type=int, default=600, help='timeout in seconds for each installed command')
    args = parser.parse_args()
    acceptance = None
    try:
        require(args.timeout > 0, '--timeout must be positive')
        acceptance = Acceptance(args)
        summary = acceptance.execute()
    except (EvidenceError, OSError, ValueError, KeyError, sqlite3.Error) as error:
        summary = {'outcome': 'failed', 'reason': str(error)}
        if acceptance is not None:
            summary.update(evidence=str(acceptance.run), checks=acceptance.checks)
    if acceptance is not None:
        (acceptance.run / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary), flush=True)
    return 0 if summary['outcome'] == 'passed' else 1


if __name__ == '__main__':
    sys.exit(main())
