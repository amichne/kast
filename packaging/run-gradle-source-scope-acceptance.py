#!/usr/bin/env python3
"""Native composite-build scope proof in an exclusively owned IDEA profile."""
import argparse
import base64
from dataclasses import asdict, dataclass, field
import hashlib
import json
from pathlib import Path
import socket
import struct
import subprocess
import time
from typing import Literal

import jsonschema

from acceptance_environment import AcceptanceEnvironment, NetworkPolicy, admitted_tools
from hosted_acceptance_fixture import admit_hosted_idea, prepare_hosted_fixture
from hosted_change_process import NativeProcesses, private_file


@dataclass(frozen=True)
class DiscoveryTarget:
    type: Literal['name'] = 'name'
    query: str = 'NativeChangeTarget'
    kind: Literal['class'] = 'class'
    match: Literal['exact-name'] = 'exact-name'


@dataclass(frozen=True)
class DiscoveryRequest:
    target: DiscoveryTarget = field(default_factory=DiscoveryTarget)
    limit: int = 10


@dataclass(frozen=True)
class DiscoveryBody:
    type: Literal['request'] = 'request'
    value: DiscoveryRequest = field(default_factory=DiscoveryRequest)


@dataclass(frozen=True)
class DiscoveryEnvelope:
    schema: Literal['kast.symbol.discover.v3'] = 'kast.symbol.discover.v3'
    operation: Literal['symbol.discover'] = 'symbol.discover'
    body: DiscoveryBody = field(default_factory=DiscoveryBody)


@dataclass(frozen=True)
class HostedDiscoveryRequest:
    root: str
    document: str
    type: Literal['SYMBOL_DISCOVER'] = 'SYMBOL_DISCOVER'


@dataclass(frozen=True)
class SuccessfulCase:
    case: Literal['selected-module-search', 'foreign-unavailable-root-search']
    passed: Literal[True] = True


@dataclass(frozen=True)
class RejectedCase:
    # Opaque hosted response union, validated against the authoritative schema before recording.
    failure: dict
    case: Literal['selected-unavailable-root-rejects'] = 'selected-unavailable-root-rejects'
    passed: Literal[True] = True


@dataclass
class ScopeEvidence:
    cases: list[SuccessfulCase | RejectedCase] = field(default_factory=list)
    schemaVersion: Literal[1] = 1
    scope: Literal['native-single-gradle-build-source-admission'] = 'native-single-gradle-build-source-admission'


def exchange(workspace, home):
    key = hashlib.sha256(str(workspace).encode()).hexdigest()[:32]
    descriptor = home / '.kast/ide-hosted' / key / 'endpoint.json'
    if not descriptor.is_file():
        return None
    metadata = json.loads(descriptor.read_text())
    envelope = json.dumps(asdict(DiscoveryEnvelope()))
    data = json.dumps(asdict(HostedDiscoveryRequest(str(workspace), envelope))).encode()
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(10)
        client.connect(metadata['socket'])
        client.sendall(struct.pack('>I', len(data)) + data)
        def receive(size):
            result = bytearray()
            while len(result) < size:
                part = client.recv(size - len(result))
                if not part:
                    raise EOFError('host closed frame')
                result.extend(part)
            return result
        size = struct.unpack('>I', receive(4))[0]
        if not 0 < size <= 65536:
            raise ValueError('response bound')
        return json.loads(receive(size))


def complete(result):
    body = result.get('body', {}) if isinstance(result, dict) else {}
    items = body.get('result', {}).get('items', [])
    return body.get('type') == 'complete' and len(items) == 1 and items[0].get('name') == 'NativeChangeTarget'


def await_read(fixture, isolation, process, timeout):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError('native IDE exited')
        try:
            last = exchange(fixture.workspace, isolation.root / 'home')
        except (OSError, EOFError):
            last = None
        if complete(last):
            return last
        time.sleep(0.5)
    raise RuntimeError('native discovery did not become complete: ' + str(last))


def fixture_phase(fixture, isolation, repo, phase):
    request = isolation.root / (phase + '.input')
    request.write_text(str(fixture.workspace) + '\n' + phase + '\n')
    template = (repo / 'experiments/host-observation/gradle-source-scope-fixture.kts.template').read_text()
    script = request.with_suffix('.kts')
    script.write_text(template.replace('@INPUT_BASE64@', base64.b64encode(str(request).encode()).decode()))
    with private_file(request.with_suffix('.log')) as output:
        subprocess.run([fixture.command[0], 'ideScript', str(script)], env=fixture.environment,
                       cwd=fixture.workspace, stdout=output, stderr=subprocess.STDOUT, timeout=45, check=True)
    result = request.with_name(request.name + '.result')
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if result.exists():
            if result.read_text() != 'VERIFIED':
                raise ValueError('fixture proof rejected')
            return
        time.sleep(0.1)
    raise RuntimeError('fixture phase did not complete: ' + phase)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--idea-home', required=True, type=Path)
    parser.add_argument('--plugin', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    report = args.report.absolute()
    if report.exists():
        parser.error('report already exists')
    report.parent.mkdir(parents=True, exist_ok=True)
    evidence = ScopeEvidence()
    idea = admit_hosted_idea(args.idea_home, repo / 'gradle/libs.versions.toml')
    with AcceptanceEnvironment(admitted_tools(), network=NetworkPolicy.DEPENDENCY_DOWNLOADS) as isolation:
        isolation.report_after_cleanup(report, asdict(evidence))
        fixture = prepare_hosted_fixture(isolation, repo, idea, args.plugin.absolute())
        workspace = fixture.workspace
        fixture.source.unlink()
        (workspace / 'settings.gradle.kts').write_text('rootProject.name = "scope-proof"\ninclude(":app")\nincludeBuild("build-logic")\n')
        (workspace / 'build.gradle.kts').write_text('plugins { kotlin("jvm") version "2.3.10" apply false }\n')
        for relative, source in {
            'app/build.gradle.kts': 'plugins { kotlin("jvm") }\nrepositories { mavenCentral() }\n',
            'app/src/main/kotlin/Fixture.kt': 'package fixture\nclass NativeChangeTarget\n',
            'build-logic/settings.gradle.kts': 'rootProject.name = "scope-foreign"\ninclude(":plugins")\n',
            'build-logic/plugins/build.gradle.kts': 'plugins { java }\n',
            'build-logic/plugins/src/main/java/Foreign.java': 'class Foreign {}\n',
        }.items():
            path = workspace / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
        print('Native fixture: ' + str(isolation.root), flush=True)
        with private_file(isolation.root / 'ide/native.log') as output:
            process = isolation.spawn(fixture.command, cwd=workspace, env=fixture.environment, stdout=output, stderr=subprocess.STDOUT)
        try:
            await_read(fixture, isolation, process, 600)
            fixture_phase(fixture, isolation, repo, 'OBSERVE')
            evidence.cases.append(SuccessfulCase('selected-module-search'))
            print('Selected-build declaration found; native Gradle ownership verified.', flush=True)
            fixture_phase(fixture, isolation, repo, 'FOREIGN_BAD_ROOT')
            await_read(fixture, isolation, process, 30)
            evidence.cases.append(SuccessfulCase('foreign-unavailable-root-search'))
            print('Foreign unavailable source root did not poison search.', flush=True)
            fixture_phase(fixture, isolation, repo, 'SELECTED_BAD_ROOT')
            deadline = time.monotonic() + 30
            rejected = None
            while time.monotonic() < deadline:
                rejected = exchange(workspace, isolation.root / 'home')
                if isinstance(rejected, dict) and rejected.get('failure') == 'NAMED_SOURCE_SCOPE_REJECTED':
                    break
                time.sleep(0.1)
            detail = rejected.get('detail', {}) if isinstance(rejected, dict) else {}
            if (detail.get('cause') != 'IDE_ROOT_UNMAPPED' or detail.get('reason') != 'SOURCE_FOLDER_UNAVAILABLE'
                    or not detail.get('module', {}).get('value') or not detail.get('root', {}).get('value', '').endswith('/kast-unavailable-source-root')):
                raise RuntimeError('selected bad root did not retain rejection identity: ' + str(rejected))
            schema = json.loads((repo / 'protocol/contract/src/main/resources/ide-hosted/hosted-query.schema.json').read_text())
            jsonschema.Draft202012Validator(schema).validate(rejected)
            evidence.cases.append(RejectedCase(rejected))
            print('Selected unavailable source root rejected with module/root evidence.', flush=True)
            isolation.mark_passed()
        finally:
            isolation.report_after_cleanup(report, asdict(evidence))
            NativeProcesses.stop(process)
            subprocess.run([str(isolation.tools['bash']), str(workspace / 'gradlew'), '--stop'], cwd=workspace,
                           env=fixture.environment, capture_output=True, timeout=60, check=True)


if __name__ == '__main__':
    main()
