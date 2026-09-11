#!/usr/bin/env python3
"""Open one private native fixture; expose no installed worker or mutation command.

The report is safe to share. Raw search references remain only inside the private
fixture. A caller-created sibling <report>.stop file ends the bounded hold period.
This runner qualifies fixture readiness, not the hosted mutation workflow.
"""
import argparse
from enum import Enum
import json
import hashlib
from pathlib import Path
import subprocess
import time

from acceptance_environment import AcceptanceEnvironment, GradleRetirement, NetworkPolicy, admitted_tools
from acceptance_idea import digest
from hosted_acceptance_fixture import admit_hosted_idea, prepare_hosted_fixture


class Stage(Enum):
    INPUTS = 'inputs'
    NATIVE_START = 'native-start'
    SEMANTIC_READINESS = 'semantic-readiness'
    HOLD = 'hold'
    RETIREMENT = 'retirement'


class Outcome(Enum):
    STARTED = 'started'
    COMPLETED = 'completed'
    REJECTED = 'rejected'
    TIMED_OUT = 'timed-out'


def pending_readiness(document):
    """Only named transient admission states may be retried during native import."""
    return (document.get('failure') == 'PROJECT_ADMISSION_REJECTED'
            and document.get('detail') in ('GRADLE_MODEL_UNAVAILABLE', 'DUMB_MODE')) or document.get('failure') == 'INDEXING'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--idea-home', type=Path, required=True)
    parser.add_argument('--plugin', type=Path, required=True)
    parser.add_argument('--product', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--readiness-seconds', type=int, default=300)
    parser.add_argument('--hold-seconds', type=int, default=1800)
    args = parser.parse_args()
    if not 1 <= args.readiness_seconds <= 900 or not 1 <= args.hold_seconds <= 3600:
        parser.error('time bounds rejected')
    repo = Path(__file__).resolve().parent.parent
    report = args.report.resolve()
    if report.exists() or report.with_suffix('.stop').exists():
        parser.error('report or stop file already exists')
    report.parent.mkdir(parents=True, exist_ok=True)
    evidence = {'schemaVersion': 1, 'scope': 'dedicated-fixture-readiness',
                'nativeMutationQualified': False, 'stages': []}
    def record(stage, outcome):
        evidence['stages'].append({'stage': stage.value, 'outcome': outcome.value})
        report.write_text(json.dumps(evidence, indent=2) + '\n')
        print(json.dumps({'stage': stage.value, 'outcome': outcome.value}), flush=True)
    record(Stage.INPUTS, Outcome.STARTED)
    idea = admit_hosted_idea(args.idea_home, repo / 'gradle/libs.versions.toml')
    with AcceptanceEnvironment(admitted_tools(), network=NetworkPolicy.DEPENDENCY_DOWNLOADS) as isolation:
        isolation.report_after_cleanup(report, evidence)
        if any((args.product / name).exists() for name in ('state', 'config', 'runtime-payloads')):
            raise RuntimeError('product-must-be-a-pristine-staged-control-artifact')
        product = isolation.stage_product(args.product)
        fixture = prepare_hosted_fixture(isolation, repo, idea, args.plugin)
        evidence['inputs'] = json.loads(fixture.input_receipt.read_text())
        evidence['fixtureRoot'] = str(isolation.root)
        evidence['stopFile'] = str(report.with_suffix('.stop'))
        record(Stage.INPUTS, Outcome.COMPLETED)
        with (isolation.root / 'ide/stdout.log').open('w') as output, \
                (isolation.root / 'ide/stderr.log').open('w') as error:
            process = isolation.spawn(fixture.command, cwd=fixture.workspace, env=fixture.environment,
                                      stdout=output, stderr=error)
            evidence['idePid'] = process.pid
            record(Stage.NATIVE_START, Outcome.STARTED)
            try:
                record(Stage.SEMANTIC_READINESS, Outcome.STARTED)
                deadline = time.monotonic() + args.readiness_seconds
                root_digest = hashlib.sha256(str(fixture.workspace).encode()).hexdigest()[:32]
                endpoint = isolation.root / 'home/.kast/ide-hosted' / root_digest / 'endpoint.json'
                while True:
                    if process.poll() is not None:
                        record(Stage.NATIVE_START, Outcome.REJECTED)
                        raise RuntimeError('native-process-exited')
                    if not endpoint.is_file():
                        if time.monotonic() >= deadline:
                            record(Stage.SEMANTIC_READINESS, Outcome.TIMED_OUT)
                            raise RuntimeError('native-endpoint-timeout')
                        time.sleep(0.5)
                        continue
                    result = subprocess.run([str(product / 'bin/kast'), 'tool', 'search_classes'],
                        cwd=fixture.workspace, env=fixture.environment,
                        input=json.dumps({'class_name': 'NativeChangeTarget', 'name_match': None, 'scope': None}),
                        capture_output=True, text=True, timeout=15)
                    try:
                        document = json.loads(result.stdout) if result.stdout else {}
                    except json.JSONDecodeError:
                        record(Stage.SEMANTIC_READINESS, Outcome.REJECTED)
                        raise RuntimeError('non-json-read-result') from None
                    if result.returncode == 0 and document.get('status') == 'complete' and len(document.get('items', [])) == 1:
                        private = isolation.root / 'ready-search.private.json'
                        private.write_text(result.stdout)
                        private.chmod(0o600)
                        evidence['live'] = document['live']
                        evidence['sourceUnchanged'] = digest(fixture.source) == evidence['inputs']['sourcePreimageSha256']
                        if not evidence['sourceUnchanged']:
                            record(Stage.SEMANTIC_READINESS, Outcome.REJECTED)
                            raise RuntimeError('fixture-source-changed')
                        record(Stage.NATIVE_START, Outcome.COMPLETED)
                        record(Stage.SEMANTIC_READINESS, Outcome.COMPLETED)
                        break
                    evidence['lastReadinessObservation'] = {key: document[key] for key in
                        ('outcome', 'failure', 'detail', 'stage', 'status') if key in document}
                    if not pending_readiness(document):
                        record(Stage.SEMANTIC_READINESS, Outcome.REJECTED)
                        raise RuntimeError('semantic-readiness-rejected')
                    # Incomplete model/index readiness is expected during import.
                    # Preserve the last bounded stage/reason without retaining executable references.
                    if time.monotonic() >= deadline:
                        record(Stage.SEMANTIC_READINESS, Outcome.TIMED_OUT)
                        raise RuntimeError('semantic-readiness-timeout')
                    time.sleep(1)
                record(Stage.HOLD, Outcome.STARTED)
                deadline = time.monotonic() + args.hold_seconds
                while not report.with_suffix('.stop').exists():
                    if process.poll() is not None or time.monotonic() >= deadline:
                        record(Stage.HOLD, Outcome.TIMED_OUT)
                        raise RuntimeError('native-hold-ended-without-stop')
                    time.sleep(0.5)
                record(Stage.HOLD, Outcome.COMPLETED)
            finally:
                record(Stage.RETIREMENT, Outcome.STARTED)
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=15)
                daemons = isolation.capture_gradle_daemons()
                stop = subprocess.run([str(isolation.tools['bash']), str(fixture.workspace / 'gradlew'), '--stop'],
                    cwd=fixture.workspace, env=fixture.environment, capture_output=True, timeout=60)
                if stop.returncode != 0 or isolation.await_gradle_retirement(daemons) is not GradleRetirement.RETIRED:
                    record(Stage.RETIREMENT, Outcome.REJECTED)
                    raise RuntimeError('gradle-retirement-rejected')
                record(Stage.RETIREMENT, Outcome.COMPLETED)
            isolation.mark_passed()


if __name__ == '__main__':
    main()
