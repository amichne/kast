#!/usr/bin/env python3
"""Opt-in real staged broker/CLI/plugin change workflow in one private native IDE."""
import argparse
from dataclasses import asdict
import json
from pathlib import Path
import shutil
import subprocess
import sys

from acceptance_environment import AcceptanceEnvironment, NetworkPolicy, admitted_tools
from acceptance_idea import digest
from hosted_acceptance_fixture import admit_hosted_idea, prepare_hosted_fixture
from hosted_change_acceptance import (AcceptanceFailure, AcceptanceRejected, source_identity,
    tree_identity, admit_harness, bounded_native_report, durable_receipt_scopes, remaining_matrix_gates, native_workflow_qualified)
from hosted_change_process import NativeProcesses, private_file
from hosted_runtime_observation import OwnedRuntimeObserver
from hosted_change_probe import stage_native_probe
from native_fixture_probe import NativeFixtureProbeError
from hosted_read_fixture import prepare_read_fixture
from hosted_read_policy import NativeReadPolicy, policy_receipt
from hosted_generated_fixture import prepare_generated_fixture, finalize_generated_fixture
from hosted_configuration_continuity import inspect_configuration_continuity
from hosted_read_regression import run_read_regression
from hosted_workspace_refresh_regression import run_workspace_refresh_regression
from released_acceptance_product import admit_release, install_release, product_executable, ReleaseAssetIdentity, ReleaseRejected
from released_session_acceptance import SessionRejected, inspect_shell_sessions
from released_upgrade_acceptance import admit_previous_release, prepare_release_upgrade
from released_tool_inventory import inspect_installed_inventory
from released_coordinator_acceptance import qualify_released_coordinator


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('idea-home', 'harness', 'schemas', 'report', 'probe'):
        parser.add_argument('--' + name, type=Path, required=True)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument('--product', type=Path)
    inputs.add_argument('--release-assets', type=Path)
    parser.add_argument('--plugin', type=Path)
    parser.add_argument('--release-version')
    parser.add_argument('--codex-executable', type=Path)
    parser.add_argument('--previous-release-assets', type=Path)
    parser.add_argument('--previous-release-version')
    parser.add_argument('--diagnostic-dirty', action='store_true')
    parser.add_argument('--workspace-refresh-only', action='store_true')
    parser.add_argument('--read-policy', type=NativeReadPolicy, choices=list(NativeReadPolicy), default=NativeReadPolicy.DEFAULT)
    parser.add_argument('--readiness-seconds', type=int, default=600)
    parser.add_argument('--run-seconds', type=int, default=900)
    args = parser.parse_args()
    if not 1 <= args.readiness_seconds <= 900 or not 1 <= args.run_seconds <= 1800:
        parser.error('time bounds rejected')
    if ((args.product is not None and (args.plugin is None or args.release_version is not None))
            or (args.release_assets is not None and (args.release_version is None or args.plugin is not None or args.diagnostic_dirty))):
        parser.error('source mode requires --product and --plugin; release mode requires --release-assets and --release-version with a clean tag')
    if ((args.previous_release_assets is None) != (args.previous_release_version is None)
            or (args.previous_release_assets is not None and args.release_assets is None)):
        parser.error('adjacent patch upgrade requires release mode and both --previous-release-assets and --previous-release-version')
    if args.release_assets is not None and args.codex_executable is None:
        parser.error('release mode requires an explicitly admitted --codex-executable')
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
        release = admit_release(repo, args.release_assets, args.release_version, idea, source) if args.release_assets else None
        previous = admit_previous_release(repo, args.previous_release_assets, args.previous_release_version, idea, release) if args.previous_release_assets else None
        archive = release.plugin if release else args.plugin
        product_identity = tree_identity(args.product) if release is None else None
        schema_identity = tree_identity(args.schemas)
        harness_digest = admit_harness(args.harness, source.commit)
        plugin_digest = digest(archive)
        if release is None and any((args.product / name).exists() for name in ('state', 'config', 'runtime-payloads')):
            raise AcceptanceRejected(AcceptanceFailure.INPUT)
        evidence['source'] = {'commit': source.commit, 'clean': source.clean, 'changesDigest': source.changes_digest}
        evidence['artifacts'] = {'product': product_identity, 'schemas': schema_identity,
            'harnessSha256': harness_digest, 'pluginSha256': plugin_digest}
        tools = admitted_tools()
        if args.codex_executable is not None:
            tools['codex'] = args.codex_executable.absolute()
        with AcceptanceEnvironment(tools, network=NetworkPolicy.DEPENDENCY_DOWNLOADS) as isolation:
            isolation.report_after_cleanup(report, evidence)
            if release:
                evidence['releaseInputs'] = asdict(ReleaseAssetIdentity.from_inputs(release))
                evidence['fixtureRoot'] = str(isolation.root)
            if previous:
                evidence['previousReleaseInputs'] = asdict(ReleaseAssetIdentity.from_inputs(previous))
                upgrade = prepare_release_upgrade(isolation, previous, release, idea)
                installed = upgrade.target
                evidence['releasedUpgrade'] = asdict(upgrade)
            else:
                installed = install_release(isolation, release, idea) if release else None
                if installed:
                    evidence['releasedSessions'] = [asdict(session) for session in inspect_shell_sessions(isolation, installed)]
            product = Path(installed.product) if installed else isolation.stage_product(args.product)
            plugins = Path(installed.pluginsDirectory) if installed else None
            if installed:
                evidence['releasedProduct'] = asdict(installed)
                inventory = inspect_installed_inventory(isolation, product)
                evidence['releasedInventory'] = asdict(inventory)
            private = isolation.root / 'native-change'
            private.mkdir(mode=0o700)
            harness, schemas = private / 'harness.jar', private / 'schemas'
            shutil.copyfile(args.harness, harness)
            shutil.copytree(args.schemas, schemas)
            fixture = prepare_hosted_fixture(isolation, repo, idea, archive, installed_plugins=plugins, read_policy=args.read_policy)
            print(json.dumps(asdict(policy_receipt(args.read_policy))), flush=True)
            evidence['artifacts']['testOnlyProbeSha256'] = stage_native_probe(args.probe, isolation.root, fixture.workspace, installed_plugins=plugins)
            read_fixture = prepare_read_fixture(fixture.workspace, repo)
            if ((release is None and (tree_identity(product) != product_identity or tree_identity(args.product) != product_identity))
                    or tree_identity(schemas) != schema_identity
                    or digest(harness) != harness_digest or digest(archive) != plugin_digest):
                raise AcceptanceRejected(AcceptanceFailure.ARTIFACT_CHANGED)
            evidence['inputs'] = json.loads(fixture.input_receipt.read_text())
            evidence['fixtureRoot'] = str(isolation.root)
            processes = NativeProcesses(isolation, fixture, product, args.readiness_seconds)
            evidence['nativeReadiness'] = processes.readiness_observations
            runtime_observer = OwnedRuntimeObserver(isolation.root, product, fixture.workspace, isolation.tools['ps'])
            native_report = private / 'report.private.json'
            try:
                evidence['configurationContinuity'] = inspect_configuration_continuity(isolation, fixture, product)
                record({'event': 'stage', 'stage': 'configuration-continuity',
                        'outcome': 'completed' if evidence['configurationContinuity']['outcome'] == 'passed' else 'rejected'})
                record({'event': 'stage', 'stage': 'generated-fixture-setup', 'outcome': 'started'})
                generated_setup = prepare_generated_fixture(read_fixture)
                with private_file(private / 'generated-setup.private.log') as output:
                    generated_task = subprocess.run(
                        [str(isolation.tools['bash']), str(fixture.workspace / 'gradlew'), '--no-daemon',
                         *generated_setup.gradle_tasks], cwd=fixture.workspace, env=fixture.environment,
                        stdout=output, stderr=subprocess.STDOUT, timeout=180)
                if generated_task.returncode != 0:
                    raise AcceptanceRejected(AcceptanceFailure.INPUT)
                generated_fixture = finalize_generated_fixture(generated_setup)
                read_fixture = generated_fixture.read_fixture
                processes.generated_fixture = generated_fixture
                evidence['generatedSetup'] = generated_fixture.evidence()
                record({'event': 'stage', 'stage': 'generated-fixture-setup', 'outcome': 'completed'})
                enrollment = subprocess.run([str(product / 'share/kast/libexec/kast-service'), 'enroll-trust'],
                    cwd=fixture.workspace, env=fixture.environment, capture_output=True, timeout=30)
                with private_file(private / 'enrollment.private.log') as output:
                    output.write(enrollment.stdout + enrollment.stderr)
                if enrollment.returncode != 0:
                    raise AcceptanceRejected(AcceptanceFailure.INPUT)
                record({'event': 'stage', 'stage': 'native-readiness', 'outcome': 'started'})
                evidence['initialLive'] = processes.start_ide()
                record({'event': 'stage', 'stage': 'native-readiness', 'outcome': 'completed'})
                if not args.workspace_refresh_only:
                    evidence['readRegression'] = run_read_regression(isolation, fixture, product, idea.java, harness, repo, read_fixture, evidence['initialLive'], args.read_policy)
                write()
                record({'event': 'stage', 'stage': 'workspace-refresh', 'outcome': 'started'})
                evidence['workspaceRefresh'] = run_workspace_refresh_regression(
                    isolation, fixture, product, idea.java, harness, evidence['initialLive'], idea.home)
                record({'event': 'stage', 'stage': 'workspace-refresh',
                        'outcome': 'completed' if evidence['workspaceRefresh']['outcome'] == 'passed' else 'rejected'})
                if installed:
                    evidence['releasedCoordinator'] = asdict(qualify_released_coordinator(isolation, installed, inventory, fixture))
                    write()
                if not args.workspace_refresh_only:
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
            if native_workflow_qualified(evidence):
                isolation.mark_passed()
    except SessionRejected as error:
        evidence['status'], evidence['failure'] = 'rejected', error.failure.value
        evidence['releasedSessionFailure'] = asdict(error.invocation)
    except ReleaseRejected as error:
        evidence['status'], evidence['failure'] = 'rejected', error.failure.value
    except AcceptanceRejected as error:
        evidence['status'], evidence['failure'] = 'rejected', error.failure.value
        if error.inventory is not None:
            evidence['inventoryAdmission'] = asdict(error.inventory)
    except (OSError, ValueError, TypeError, KeyError, subprocess.SubprocessError, NativeFixtureProbeError):
        evidence['status'], evidence['failure'] = 'rejected', AcceptanceFailure.INPUT.value
    finally:
        evidence['remainingMatrix'] = remaining_matrix_gates(evidence.get('native'), evidence.get('readRegression'), evidence['events'])
        qualified = native_workflow_qualified(evidence)
        evidence['passed'] = qualified
        evidence['releaseQualified'] = qualified
        if qualified:
            evidence['status'] = 'qualified'
        write()
    print(json.dumps({'event': 'terminal', 'status': evidence['status'], 'releaseQualified': evidence['releaseQualified']}), flush=True)
    return 0 if evidence['releaseQualified'] else (1 if evidence['status'] == 'rejected' else 2)


if __name__ == '__main__':
    sys.exit(main())
