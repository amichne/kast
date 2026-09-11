#!/usr/bin/env python3
"""Opt-in real staged broker/CLI/plugin change workflow in one private native IDE."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys

from acceptance_environment import AcceptanceEnvironment, NetworkPolicy, admitted_tools
from acceptance_idea import digest
from hosted_acceptance_fixture import admit_hosted_idea, prepare_hosted_fixture
from hosted_change_acceptance import (AcceptanceFailure, AcceptanceRejected, source_identity,
    tree_identity, admit_harness, bounded_native_report, durable_receipt_scopes, remaining_matrix_gates)
from hosted_change_process import NativeProcesses, private_file
from hosted_runtime_observation import OwnedRuntimeObserver
from hosted_change_probe import stage_native_probe
from native_fixture_probe import NativeFixtureProbeError
from hosted_read_fixture import prepare_read_fixture
from hosted_read_regression import run_read_regression


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('idea-home', 'plugin', 'product', 'harness', 'schemas', 'report', 'probe'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--diagnostic-dirty', action='store_true')
    parser.add_argument('--readiness-seconds', type=int, default=600)
    parser.add_argument('--run-seconds', type=int, default=900)
    args = parser.parse_args()
    if not 1 <= args.readiness_seconds <= 900 or not 1 <= args.run_seconds <= 1800:
        parser.error('time bounds rejected')
    report = args.report.absolute()
    if report.exists() or report.is_symlink():
        parser.error('report already exists')
    report.parent.mkdir(parents=True, exist_ok=True)
    repo = Path(__file__).resolve().parent.parent
    evidence = {'schemaVersion': 1, 'scope': 'hosted-change-native-workflow', 'releaseQualified': False,
        'passed': False, 'status': 'started', 'events': [], 'remainingMatrix': remaining_matrix_gates()}
    def write():
        report.write_text(json.dumps(evidence, indent=2) + '\n')
    processes = None
    runtime_observer = None
    def record(event):
        if runtime_observer is not None and processes is not None and processes.ide is not None:
            pids = tuple(process.pid for process in (processes.ide, processes.native) if process is not None and process.poll() is None)
            evidence.setdefault("runtimeObservations", []).append(runtime_observer.capture(
                generation=processes.generation, owned_pids=pids))
        evidence['events'].append(event)
        write()
        print(json.dumps(event), flush=True)
    write()
    isolation = None
    try:
        source = source_identity(repo, args.diagnostic_dirty)
        idea = admit_hosted_idea(args.idea_home, repo / 'gradle/libs.versions.toml')
        product_identity, schema_identity = tree_identity(args.product), tree_identity(args.schemas)
        harness_digest = admit_harness(args.harness, source.commit)
        plugin_digest = digest(args.plugin)
        if any((args.product / name).exists() for name in ('state', 'config', 'runtime-payloads')):
            raise AcceptanceRejected(AcceptanceFailure.INPUT)
        evidence['source'] = {'commit': source.commit, 'clean': source.clean, 'changesDigest': source.changes_digest}
        evidence['artifacts'] = {'product': product_identity, 'schemas': schema_identity,
            'harnessSha256': harness_digest, 'pluginSha256': plugin_digest}
        with AcceptanceEnvironment(admitted_tools(), network=NetworkPolicy.DEPENDENCY_DOWNLOADS) as isolation:
            isolation.report_after_cleanup(report, evidence)
            product = isolation.stage_product(args.product)
            private = isolation.root / 'native-change'
            private.mkdir(mode=0o700)
            harness, schemas = private / 'harness.jar', private / 'schemas'
            shutil.copyfile(args.harness, harness)
            shutil.copytree(args.schemas, schemas)
            fixture = prepare_hosted_fixture(isolation, repo, idea, args.plugin)
            evidence['artifacts']['testOnlyProbeSha256'] = stage_native_probe(args.probe, isolation.root, fixture.workspace)
            read_fixture = prepare_read_fixture(fixture.workspace, repo)
            if (tree_identity(product) != product_identity or tree_identity(schemas) != schema_identity
                    or digest(harness) != harness_digest or digest(args.plugin) != plugin_digest
                    or tree_identity(args.product) != product_identity):
                raise AcceptanceRejected(AcceptanceFailure.ARTIFACT_CHANGED)
            evidence['inputs'] = json.loads(fixture.input_receipt.read_text())
            evidence['fixtureRoot'] = str(isolation.root)
            processes = NativeProcesses(isolation, fixture, product, args.readiness_seconds)
            evidence['nativeReadiness'] = processes.readiness_observations
            runtime_observer = OwnedRuntimeObserver(isolation.root, product, fixture.workspace, isolation.tools['ps'])
            native_report = private / 'report.private.json'
            try:
                enrollment = subprocess.run([str(product / 'bin/kast'), 'ide', 'trust-broker'],
                    cwd=fixture.workspace, env=fixture.environment, capture_output=True, timeout=30)
                with private_file(private / 'enrollment.private.log') as output:
                    output.write(enrollment.stdout + enrollment.stderr)
                if enrollment.returncode != 0:
                    raise AcceptanceRejected(AcceptanceFailure.INPUT)
                record({'event': 'stage', 'stage': 'native-readiness', 'outcome': 'started'})
                evidence['initialLive'] = processes.start_ide()
                record({'event': 'stage', 'stage': 'native-readiness', 'outcome': 'completed'})
                evidence['readRegression'] = run_read_regression(isolation, fixture, product, idea.java, harness, repo, read_fixture, evidence['initialLive'])
                write()
                processes.run(idea.java, harness, schemas, private, native_report, args.run_seconds, record)
                evidence['native'] = bounded_native_report(native_report, fixture.workspace)
                evidence['durableReceipts'] = durable_receipt_scopes(isolation.root / 'home', fixture.workspace)
                evidence['status'] = 'observed-with-unqualified-matrix'
            finally:
                processes.retire()
                if native_report.is_file() and 'native' not in evidence:
                    try:
                        evidence['native'] = bounded_native_report(native_report, fixture.workspace)
                    except AcceptanceRejected as error:
                        evidence['nativeReportFailure'] = error.failure.value
            isolation.mark_passed()
    except AcceptanceRejected as error:
        evidence['status'], evidence['failure'] = 'rejected', error.failure.value
    except (OSError, ValueError, TypeError, KeyError, subprocess.SubprocessError, NativeFixtureProbeError):
        evidence['status'], evidence['failure'] = 'rejected', AcceptanceFailure.INPUT.value
    finally:
        evidence['remainingMatrix'] = remaining_matrix_gates(evidence.get('native'), evidence.get('readRegression'), evidence['events'])
        qualified = (evidence['status'] != 'rejected' and evidence.get('source', {}).get('clean') is True
                     and evidence.get('native', {}).get('metadata', {}).get('status') == 'observed'
                     and not evidence['remainingMatrix'])
        evidence['passed'] = qualified
        evidence['releaseQualified'] = qualified
        if qualified:
            evidence['status'] = 'qualified'
        write()
    print(json.dumps({'event': 'terminal', 'status': evidence['status'], 'releaseQualified': evidence['releaseQualified']}), flush=True)
    return 0 if evidence['releaseQualified'] else (1 if evidence['status'] == 'rejected' else 2)


if __name__ == '__main__':
    sys.exit(main())
