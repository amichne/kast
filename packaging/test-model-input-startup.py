#!/usr/bin/env python3
"""One expensive installed gate: two Gradle workers, semantic reconnect, stop and owned reset."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
from acceptance_environment import AcceptanceEnvironment, GradleRetirement, NetworkPolicy, admitted_tools
from installed_acceptance_product import stage_versioned_product


def source_provenance(repo, isolation):
    def git(*arguments):
        return subprocess.run([str(isolation.tools['git']), '-C', str(repo), *arguments],
            env=isolation.environment, check=True, capture_output=True, text=True, timeout=10).stdout.strip()
    head = git('rev-parse', 'HEAD')
    assert len(head) == 40 and all(character in '0123456789abcdef' for character in head)
    return {'sourceHead': head, 'sourceTreeClean': not git('status', '--porcelain', '--untracked-files=normal')}


def admit_harness_classpath(path):
    selected = path.resolve(strict=True)
    assert selected.is_file() and selected.stat().st_size <= 1024 * 1024, 'routing harness classpath file rejected'
    classpath = selected.read_text().strip()
    entries = classpath.split(os.pathsep)
    assert classpath and all(Path(item).is_absolute() and Path(item).exists() for item in entries), 'routing harness classpath contains missing or relative entries'
    return classpath


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--product', type=Path, required=True)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--idea-home', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--harness-classpath-file', type=Path, required=True)
    parser.add_argument('--profile', choices=('local-8g', 'ci-small'), default='local-8g')
    args = parser.parse_args()
    heap_mib, native_mib, gradle_mib = (8192, 1024, 2048) if args.profile == 'local-8g' else (2048, 512, 1024)
    aggregate_mib = 2 * (heap_mib + native_mib + gradle_mib)
    classpath = admit_harness_classpath(args.harness_classpath_file)
    repo = Path(__file__).resolve().parent.parent
    product, runtime, idea = (p.resolve() for p in (args.product, args.runtime, args.idea_home))
    report = args.report.resolve()
    report.parent.mkdir(parents=True, exist_ok=True)
    # Dependencies may download into the private Gradle home; no credentials or live caches are imported.
    with AcceptanceEnvironment(admitted_tools(), network=NetworkPolicy.DEPENDENCY_DOWNLOADS) as isolation:
        product = stage_versioned_product(isolation, product, runtime)
        fixture = isolation.root
        first = fixture / 'workspace'
        second = fixture / 'workspace-two'
        second.mkdir()
        workspaces = [(first, 'FirstWorkspaceValue'), (second, 'SecondWorkspaceValue')]
        links = {'gradle.properties': 'shared/gradle.properties', 'gradle': 'shared/gradle',
                 'gradlew': 'shared/gradlew', 'build-logic/gradle.properties': '../shared/gradle.properties',
                 'build-logic/gradle': '../shared/gradle', 'build-logic/gradlew': '../shared/gradlew'}
        for number, (workspace, symbol) in enumerate(workspaces):
            def write(relative, text):
                path = workspace / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
            gate = '' if number else '''val release = file(".acceptance-import-release")
file(".acceptance-import-entered").writeText("waiting")
val deadline = System.nanoTime() + 150_000_000_000L
while (!release.isFile && System.nanoTime() < deadline) Thread.sleep(100)
check(release.isFile) { "acceptance import gate was not released" }
'''
            write('settings.gradle.kts', gate + f'rootProject.name = "contained-inputs-{number}"\nincludeBuild("build-logic")\n')
            write('build.gradle.kts', 'plugins { kotlin("jvm") version "2.3.10" }\nrepositories { mavenCentral() }\n')
            write('src/main/kotlin/Fixture.kt', f'class {symbol}(val value: String)\n')
            write('build-logic/settings.gradle.kts', f'rootProject.name = "fixture-build-logic-{number}"\n')
            write('build-logic/build.gradle.kts', 'plugins { `java-library` }\n')
            write('build-logic/src/main/java/Convention.java', 'public final class Convention {}\n')
            write('shared/gradle.properties', 'org.gradle.jvmargs=-Xmx1g\n')
            (workspace / 'shared/gradle/wrapper').mkdir(parents=True)
            for name in ('gradle-wrapper.jar', 'gradle-wrapper.properties'):
                shutil.copy2(repo / 'gradle/wrapper' / name, workspace / 'shared/gradle/wrapper' / name)
            shutil.copy2(repo / 'gradlew', workspace / 'shared/gradlew')
            for name, target in links.items():
                (workspace / name).symlink_to(target)
        def link_snapshot():
            return {str(workspace): {name: os.readlink(workspace / name) for name in links}
                    for workspace, _ in workspaces}
        env = dict(isolation.environment)
        env.update(KAST_RUNTIME_ARCHIVE=str(runtime), KAST_ENABLE_LAUNCHD='0', KAST_ENABLE_APP_SERVER='0',
                   KAST_INDEXER_MAX_HEAP=f'{heap_mib}m', KAST_WORKER_RESIDENT_LIMIT='2', KAST_WORKER_STARTUP_LIMIT='1',
                   KAST_WORKER_AGGREGATE_MIB=str(aggregate_mib), KAST_WORKER_NATIVE_MIB=str(native_mib), KAST_WORKER_GRADLE_MIB=str(gradle_mib))
        configuration = product / 'config/environment'
        configuration.parent.mkdir(parents=True, exist_ok=True)
        catalogue = json.loads((product / 'share/kast/configuration-schema.json').read_text())
        saved_keys = {item['key'] for item in catalogue['parameters']
                      if item['mutability'] == 'USER_SETTING' and 'SAVED_INSTALLATION' in item['sources']}
        configuration.write_text(''.join(key + '=' + env[key] + '\n' for key in sorted(saved_keys) if key in env))
        configuration.chmod(0o600)
        env['KAST_CONFIGURATION_FILE'] = str(configuration)
        command = str(product / 'bin/kast')
        evidence = {**source_provenance(repo, isolation), 'catalogueSha256': hashlib.sha256((product / 'share/kast/configuration-schema.json').read_bytes()).hexdigest(), 'schemaVersion': 1, 'fixture': str(fixture), 'installation': str(product),
                    'profile': args.profile, 'runtimeSha256': hashlib.sha256(runtime.read_bytes()).hexdigest(),
                    'dependencyInputs': {'kotlinPluginVersion': '2.3.10',
                        'gradleWrapperPropertiesSha256': hashlib.sha256((repo / 'gradle/wrapper/gradle-wrapper.properties').read_bytes()).hexdigest(),
                        'gradleWrapperJarSha256': hashlib.sha256((repo / 'gradle/wrapper/gradle-wrapper.jar').read_bytes()).hexdigest(),
                        'repositories': ['https://plugins.gradle.org/m2/', 'https://repo.maven.apache.org/maven2/'],
                        'isolation': 'empty private Gradle home; no inherited credentials or caches'},
                    'linksBefore': link_snapshot(), 'steps': [], 'reservationPolicy': {
                        'residentWorkers': 2, 'simultaneousStartups': 1, 'heapMiB': heap_mib,
                        'nativeMiB': native_mib, 'gradleMiB': gradle_mib, 'aggregateMiB': aggregate_mib}}
        success = False
        sequence = 0
        routing = None
        def run(arguments, workspace=first, *, document=None, timeout=60, required=True):
            nonlocal sequence
            sequence += 1
            result = subprocess.run([command, *arguments], cwd=workspace, env=env,
                                    input=None if document is None else json.dumps(document),
                                    capture_output=True, text=True, timeout=timeout)
            label = f'{sequence:02d}-' + (arguments[0] if arguments else 'inspect')
            report.with_suffix('.' + label + '.stdout').write_text(result.stdout)
            report.with_suffix('.' + label + '.stderr').write_text(result.stderr)
            evidence['steps'].append({'command': arguments, 'workspace': str(workspace), 'exitCode': result.returncode})
            assert not required or result.returncode == 0, f'{label} rejected; fixture retained; see {report}'
            return result
        def receipts():
            documents = [json.loads(path.read_text()) for path in (product / 'state/workers').glob('*.json')]
            return {item['workspaceRoot']: item for item in documents}
        def semantic(workspace, symbol, other):
            result = run(['symbol', 'discover'], workspace, document={
                'target': {'type': 'name', 'query': symbol, 'kind': 'symbol', 'match': 'fuzzy'}, 'limit': 10}, timeout=480)
            document = json.loads(result.stdout)
            text = json.dumps(document)
            assert symbol in text and other not in text, document
            return document
        try:
            # B is ready before A enters a controlled Gradle model-import wait.
            run(['start', '--idea-home', str(idea)], second, timeout=600)
            ready_b = receipts()[str(second)]
            with report.with_suffix('.delayed-start.stdout').open('w') as output, report.with_suffix('.delayed-start.stderr').open('w') as error:
                delayed = isolation.spawn([command, 'start', '--idea-home', str(idea)], cwd=first, env=env,
                                          stdout=output, stderr=error, text=True)
                try:
                    deadline = time.monotonic() + 420
                    while not (first / '.acceptance-import-entered').exists():
                        assert delayed.poll() is None, 'workspace A startup exited before the model-import gate'
                        assert time.monotonic() < deadline, 'workspace A never reached the bounded import gate'
                        time.sleep(0.1)
                    assert receipts()[str(first)]['phase'] == 'STARTING', receipts()
                    routing_report = report.with_suffix('.app-server-routing.json')
                    routing_ready = fixture / 'app-server-routing-ready'
                    with report.with_suffix('.app-server-routing.stdout').open('w') as routing_output, report.with_suffix('.app-server-routing.stderr').open('w') as routing_error:
                        routing = isolation.spawn([str(isolation.tools['java']), '-Xmx512m', '-cp', classpath,
                            'io.github.amichne.kast.appserver.runtime.InstalledWorkspaceRoutingAcceptanceMain',
                            str(product), str(first), str(second), str(routing_report), str(routing_ready)],
                            cwd=first, env=env, stdout=routing_output, stderr=routing_error, text=True)
                    routing_deadline = time.monotonic() + 120
                    while not routing_ready.exists():
                        assert routing.poll() is None, 'App Server routing harness exited before ready B semantic completion'
                        assert time.monotonic() < routing_deadline, 'App Server routing harness did not serve B during delayed A import'
                        time.sleep(0.1)
                    semantic(second, workspaces[1][1], workspaces[0][1])
                    assert delayed.poll() is None, 'workspace B did not complete while A remained delayed'
                    assert receipts()[str(second)] == ready_b, 'workspace A startup replaced the ready B worker'
                    evidence['readyWorkspaceServedDuringDelayedImport'] = True
                finally:
                    (first / '.acceptance-import-release').write_text('release\n')
                assert delayed.wait(timeout=600) == 0, 'delayed workspace A startup rejected; see retained fixture logs'
            assert routing is not None and routing.wait(timeout=600) == 0, 'App Server routing harness rejected; see retained evidence'
            evidence['appServerRouting'] = json.loads(report.with_suffix('.app-server-routing.json').read_text())
            initial = receipts()
            assert set(initial) == {str(first), str(second)}, initial
            assert all(item['phase'] == 'READY' for item in initial.values()), initial
            assert len({item['serviceGeneration'] for item in initial.values()}) == 1, initial
            assert len({item['endpoint']['socket'] for item in initial.values()}) == 2, initial
            assert len({item['endpoint']['bootstrapAttempt'] for item in initial.values()}) == 2, initial
            assert sum(item['heapMiB'] + item['nativeMiB'] + item['gradleMiB'] for item in initial.values()) == aggregate_mib, initial
            evidence['initialWorkers'] = initial
            evidence['semantic'] = [semantic(workspace, symbol, workspaces[1 - index][1])
                                    for index, (workspace, symbol) in enumerate(workspaces)]
            # Each semantic command disconnects its UDS client. A fresh command must reuse the exact worker receipts.
            semantic(first, workspaces[0][1], workspaces[1][1])
            assert receipts() == initial, 'reconnection replaced a ready worker or reservation'
            evidence['reconnectRetainedWorkers'] = True
            bootstrap = [json.loads(path.read_text())['bootstrap'] for path in (product / 'state/cache').rglob('bootstrap-state')]
            assert len(bootstrap) == 2 and all(item['state'] == 'ready' for item in bootstrap), bootstrap
            assert all('Selected' in item['gradleJvm']['report']['outcome']['state'] for item in bootstrap), bootstrap
            evidence['bootstrap'] = bootstrap
            heaps = [json.loads(line.partition('kast-indexer-heap: ')[2])
                     for path in (product / 'state/cache').rglob('startup.log') for line in path.read_text().splitlines()
                     if line.startswith('kast-indexer-heap: ')]
            assert len(heaps) == 2 and all(item['requestedMaxHeapMiB'] == heap_mib for item in heaps), heaps
            assert all(abs(item['observedMaxHeapBytes'] - heap_mib * 1024 * 1024) <= 64 * 1024 * 1024 for item in heaps), heaps
            evidence['heaps'] = heaps
            evidence['linksAfter'] = link_snapshot()
            assert evidence['linksAfter'] == evidence['linksBefore']
            run(['stop'], first)
            assert str(first) not in receipts() and receipts()[str(second)] == initial[str(second)], receipts()
            semantic(second, workspaces[1][1], workspaces[0][1])
            run(['stop'], second)
            assert not receipts(), receipts()
            saved = {path.relative_to(product).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                     for path in (product / 'config').rglob('*') if path.is_file()}
            old_epoch = json.loads((product / 'state/epoch.json').read_text())
            reset = subprocess.run([str(isolation.tools['python3']), str(product / 'share/kast/installation-lifecycle.py'),
                                    '--installation', str(product), 'reset', '--json'], cwd=first, env=env,
                                   capture_output=True, text=True, timeout=120)
            report.with_suffix('.reset.stdout').write_text(reset.stdout)
            report.with_suffix('.reset.stderr').write_text(reset.stderr)
            assert reset.returncode == 0, reset.stdout
            evidence['reset'] = json.loads(reset.stdout)
            assert evidence['reset']['status'] == 'reset', evidence['reset']
            assert json.loads((product / 'state/epoch.json').read_text())['epoch'] != old_epoch['epoch']
            assert saved == {path.relative_to(product).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                             for path in (product / 'config').rglob('*') if path.is_file()}
            assert not receipts()
            reset_epoch = json.loads((product / 'state/epoch.json').read_text())['epoch']
            run(['start', '--idea-home', str(idea)], first, timeout=600)
            restarted = receipts()[str(first)]
            assert restarted['stateEpoch'] == reset_epoch
            assert restarted['installationId'] == initial[str(first)]['installationId']
            assert restarted['serviceGeneration'] != initial[str(first)]['serviceGeneration']
            assert restarted['endpoint']['bootstrapAttempt'] != initial[str(first)]['endpoint']['bootstrapAttempt']
            semantic(first, workspaces[0][1], workspaces[1][1])
            evidence['restartedAfterReset'] = restarted
            success = True
        finally:
            (first / '.acceptance-import-release').write_text('release\n')
            if routing is not None and routing.poll() is None:
                routing.terminate()
                try:
                    routing.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    routing.kill()
                    routing.wait(timeout=5)
            cleanup = []
            for workspace, _ in workspaces:
                try:
                    cleanup.append(run(['stop'], workspace, required=False).returncode)
                except (OSError, subprocess.TimeoutExpired):
                    cleanup.append(-1)
            try:
                cleanup.append(run(['app-server', 'disable'], required=False, timeout=90).returncode)
            except (OSError, subprocess.TimeoutExpired):
                cleanup.append(-1)
            try:
                gradle_daemons = isolation.capture_gradle_daemons()
                evidence['gradleRetirement'] = {'observed': [{'pid': item.pid, 'started': item.started} for item in gradle_daemons], 'outcome': 'pending'}
                gradle_stop = subprocess.run([str(isolation.tools['bash']), str(first / 'gradlew'), '--stop'],
                    cwd=first, env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=60)
                retirement = isolation.await_gradle_retirement(gradle_daemons) if gradle_stop.returncode == 0 else GradleRetirement.REJECTED
                evidence['gradleRetirement']['outcome'] = retirement.value
                cleanup.append(0 if gradle_stop.returncode == 0 and retirement is GradleRetirement.RETIRED else -1)
                report.with_suffix('.gradle-stop.stdout').write_text(gradle_stop.stdout)
                report.with_suffix('.gradle-stop.stderr').write_text(gradle_stop.stderr)
            except (OSError, ValueError, subprocess.SubprocessError):
                evidence['gradleRetirement'] = {'outcome': GradleRetirement.REJECTED.value}
                cleanup.append(-1)
            evidence['cleanupExitCodes'] = cleanup
            evidence['checksPassed'] = success and all(code == 0 for code in cleanup)
            isolation.report_after_cleanup(report, evidence)
            if evidence['checksPassed']:
                isolation.mark_passed()
        assert evidence['checksPassed'], evidence
    assert evidence['passed'], evidence
    print(f'model-input-startup: two workspaces passed; {report}')


if __name__ == '__main__':
    main()
