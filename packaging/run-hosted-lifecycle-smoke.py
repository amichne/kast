#!/usr/bin/env python3
"""Bounded lifecycle smoke using the existing disposable graphical IDEA fixture."""
from dataclasses import asdict, dataclass
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import time
from xml.sax.saxutils import quoteattr

from acceptance_environment import AcceptanceEnvironment, NetworkPolicy, admitted_tools
from hosted_acceptance_fixture import admit_hosted_idea, prepare_hosted_fixture
from hosted_change_process import private_file
from released_acceptance_product import product_executable

@dataclass(frozen=True)
class Inspect:
    type: str = 'inspect'

@dataclass(frozen=True)
class Open:
    root: str
    requestId: str
    type: str = 'open'

@dataclass(frozen=True)
class Target:
    host: str
    project: str
    root: str

@dataclass(frozen=True)
class Control:
    target: Target
    requestId: str
    type: str

@dataclass(frozen=True)
class Sync:
    target: Target
    requestId: str
    effect: str = 'GRADLE_MODEL_RELOAD'
    type: str = 'sync'

@dataclass(frozen=True)
class Status:
    host: str
    requestId: str
    type: str = 'status'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('idea-home', 'product', 'plugin', 'report'):
        parser.add_argument('--' + name, type=lambda raw: Path(raw).resolve(), required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    report = {'scope': 'native-project-lifecycle', 'passed': False, 'events': []}
    def record(stage, result):
        report['events'].append({'stage': stage, 'result': result})
        args.report.write_text(json.dumps(report, indent=2) + '\n')
        print(stage, result.get('type'), result.get('reason', ''), flush=True)
    idea = admit_hosted_idea(args.idea_home, repo / 'gradle/libs.versions.toml')
    with AcceptanceEnvironment(admitted_tools(), network=NetworkPolicy.DEPENDENCY_DOWNLOADS) as isolation:
        report['fixtureRoot'] = str(isolation.root)
        isolation.report_after_cleanup(args.report, report)
        product = isolation.stage_product(args.product)
        fixture = prepare_hosted_fixture(isolation, repo, idea, args.plugin)
        sibling = isolation.root / 'sibling'
        shutil.copytree(fixture.workspace, sibling, ignore=shutil.ignore_patterns('.idea'))
        (isolation.root / 'ide/config/options/trusted-paths.xml').write_text(
            '<application><component name="Trusted.Paths"><option name="TRUSTED_PROJECT_PATHS"><map>' +
            ''.join('<entry key=' + quoteattr(str(root)) + ' value="true" />' for root in (fixture.workspace, sibling)) +
            '</map></option></component></application>\n')
        environment = dict(fixture.environment, KAST_INSTALL_IDEA_HOME=str(idea.home))
        cli_environment = dict(environment)
        cli_environment['JAVA_OPTS'] = cli_environment.pop('_JAVA_OPTIONS')
        cli_environment.pop('JAVA_TOOL_OPTIONS')
        executable = product_executable(product, isolation.root)
        def call(request):
            result = subprocess.run([str(executable), 'workspace', 'lifecycle'], cwd=fixture.workspace,
                env=cli_environment, input=json.dumps(asdict(request)), text=True, capture_output=True, timeout=15)
            output = result.stdout if result.returncode == 0 else result.stderr
            if not output.strip():
                raise RuntimeError('CLI produced no lifecycle document: ' + result.stderr[-1000:])
            return json.loads(output)
        with private_file(isolation.root / 'ide/stdout.log') as out, private_file(isolation.root / 'ide/stderr.log') as err:
            process = isolation.spawn([str(idea.launcher), 'nosplash', 'dontReopenProjects'], cwd=fixture.workspace,
                env=environment, stdout=out, stderr=err)
        deadline = time.monotonic() + 90
        observed = call(Inspect())
        while observed.get('type') == 'blocked' and time.monotonic() < deadline and process.poll() is None:
            time.sleep(.5)
            observed = call(Inspect())
        record('zero-project-inspect', observed)
        assert observed['type'] == 'inspected' and observed['projects'] == [], observed
        host = observed['host']
        def finish(request, expected):
            result = call(request)
            deadline = time.monotonic() + 180
            while result.get('type') == 'pending' and time.monotonic() < deadline:
                time.sleep(.5)
                result = call(Status(host, request.requestId))
            record(request.type + '-' + request.requestId, result)
            assert result['type'] == expected, result
            return result
        first = finish(Open(str(fixture.workspace), 'existing-settings'), 'opened')
        second = finish(Open(str(sibling), 'first-link'), 'opened')
        assert first['target']['project'] != second['target']['project']
        reused = finish(Open(str(sibling), 'reuse'), 'opened')
        assert reused['target'] == second['target']
        finish(Sync(Target(**second['target']), 'reload'), 'synced')
        finish(Control(Target(**second['target']), 'release', 'release'), 'released')
        closed = finish(Control(Target(**second['target']), 'close', 'close'), 'closed')
        assert call(Status(host, 'close')) == closed
        finish(Control(Target(**first['target']), 'close-first', 'close'), 'closed')
        final = call(Inspect())
        record('last-project-closed', final)
        assert final['type'] == 'inspected' and final['host'] == host and final['projects'] == []
        report['passed'] = True
        # Only fixture-owned processes are retired by the existing isolation owner.
        isolation.mark_passed()

if __name__ == '__main__':
    main()
