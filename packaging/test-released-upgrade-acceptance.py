#!/usr/bin/env python3
"""Owned fake-installer subprocess evidence; never a released/native qualification claim."""
from dataclasses import asdict, dataclass, replace
import hashlib
import importlib.util
import json
from pathlib import Path
import shlex
import shutil
import sys
import unittest

from acceptance_idea import digest
from released_acceptance_product import ReleaseFailure, ReleaseRejected
from released_session_acceptance import SessionRejected
from released_upgrade_acceptance import prepare_release_upgrade

_spec = importlib.util.spec_from_file_location('release_test_fixture', Path(__file__).with_name('test-released-acceptance-product.py'))
_fixture = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = _fixture
_spec.loader.exec_module(_fixture)


@dataclass(frozen=True)
class Assignment:
    value: str
    key: str = 'KAST_RUNTIME_DIRECTORY'
    source: str = 'SAVED_INSTALLATION'


@dataclass(frozen=True)
class Configuration:
    resolvedNextLaunch: tuple[Assignment, ...]
    operation: str = 'config-show'
    status: str = 'complete'
    desiredSavedConfiguration: str = 'LOADED'


@dataclass(frozen=True)
class Inspection:
    installation: str
    state: str
    operation: str = 'installation.inspect'
    status: str = 'inspected'


@dataclass(frozen=True)
class Registry:
    roots: tuple[str, ...]
    schemaVersion: int = 2
    revision: int = 1


@dataclass(frozen=True)
class Registration:
    workspaceId: str
    root: str
    revision: int = 1
    operation: str = 'app-server.register'


@dataclass(frozen=True)
class Observation:
    stage: str
    event: str = 'kast_installation'
    outcome: str = 'COMPLETED'


class ReleasedUpgradeTest(unittest.TestCase):
    def setUp(self):
        self.fixture = _fixture.ReleasedProductTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)
        f = self.fixture
        self.prior_template = f.base / 'prior-template'
        shutil.copytree(f.template, self.prior_template)
        prior_assets = f.base / 'prior-assets'
        prior_assets.mkdir()
        originals = []
        for asset in (f.control, f.plugin):
            prior = prior_assets / asset.name.replace('1.2.3', '1.2.2')
            shutil.copyfile(asset, prior)
            prior.with_name(prior.name + '.sha256').write_text(f'{digest(prior)}  {prior.name}\n')
            originals.append(prior)
        self.prior_product = f.product.with_name(f.product.name.replace('1.2.3', '1.2.2'))
        self.configure_template(self.prior_template, self.prior_product, '1.2.2')
        self.configure_template(f.template, f.product, '1.2.3')
        q = lambda value: shlex.quote(str(value))
        observations = '\n'.join(json.dumps(asdict(Observation(stage))) for stage in
            ('PRIOR_ADMISSION', 'PRIOR_RETIREMENT', 'CONFIGURATION_VALIDATION', 'COMMAND_QUALIFICATION'))
        self.observations = f.base / 'observations'
        self.observations.write_text(observations + '\n')
        f.installer.write_text('#!/bin/bash\nset -eu\n'
            f'[[ "$HOME" == {q(f.root / "home")} && "$KAST_ENABLE_APP_SERVER" == 0 && "$KAST_ENABLE_LAUNCHD" == 0 ]]\n'
            '[[ "$1" == --version && "$3" == --idea-home ]]\n'
            f'case "$2" in 1.2.2) template={q(self.prior_template)}; product={q(self.prior_product)};; '
            f'1.2.3) template={q(f.template)}; product={q(f.product)};; *) exit 21;; esac\n'
            f'/bin/mkdir -p {q(f.product.parent)} {q(f.root / "bin")} {q(f.plugins / "kast-ide-hosted/lib")}\n'
            '/bin/cp -R "$template" "$product"\n'
            'if [[ "$2" == 1.2.3 ]]; then\n'
            f' /bin/cp {q(self.prior_product / "config/workspaces.json")} "$product/config/workspaces.json"\n'
            f' /bin/cat {q(self.observations)}\nfi\n'
            f'/bin/rm -f {q(f.root / "installation/current")} {q(f.root / "bin/kast")}\n'
            f'/bin/ln -s "$product" {q(f.root / "installation/current")}\n'
            f'/bin/ln -s "$product/bin/kast-complete" {q(f.root / "bin/kast")}\n'
            f'/bin/echo -n "original plugin fixture" > {q(f.plugins / "kast-ide-hosted/lib/released.jar")}\n')
        self.target = replace(f.release, installerSha256=digest(f.installer))
        self.previous = replace(self.target, version='1.2.2', commit='b' * 40, control=originals[0], plugin=originals[1])

    def configure_template(self, template, product, version):
        f = self.fixture
        workspace = str(f.root / 'workspace')
        documents = (
            ('configuration.json', Configuration((Assignment(str(product / 'state/run')),))),
            ('inspection.json', Inspection(str(product), str(product / 'state'))),
            ('registration.json', Registration(hashlib.sha256(workspace.encode()).hexdigest(), workspace)),
            ('registry.json', Registry((workspace,))))
        for name, document in documents:
            (template / 'config' / name).write_text(json.dumps(asdict(document)))
        (template / 'config/environment').write_text('KAST_RUNTIME_DIRECTORY=' + str(product / 'state/run') + '\n')
        q = lambda value: shlex.quote(str(value))
        wrapper = template / 'bin/kast-complete'
        wrapper.write_text('#!/bin/bash\nset -eu\n'
            '[[ -z "${KAST_RUNTIME_DIRECTORY+x}" && -z "${KAST_CONFIGURATION_FILE+x}" ]]\n'
            'case "$*" in\n'
            f' --version) /bin/echo "kast {version} (IntelliJ plugin)";;\n'
            f' "config show --json") /bin/cat {q(product / "config/configuration.json")};;\n'
            f' "installation inspect --json") /bin/cat {q(product / "config/inspection.json")};;\n'
            f' "app-server register") /bin/cp {q(product / "config/registry.json")} {q(product / "config/workspaces.json")}; '
            f'/bin/cat {q(product / "config/registration.json")};;\n *) exit 23;;\nesac\n')
        inventory = tuple(_fixture.PayloadFile(path.relative_to(template).as_posix(), 'sha256:' + digest(path),
                          path.stat().st_mode & 0o777) for path in sorted((template / 'bin').iterdir()))
        manifest = replace(f.manifest, semanticVersion=version, installationRoot=str(product),
            configuration=str(product / 'config/environment'), workspaceRegistry=str(product / 'config/workspaces.json'),
            stateRoot=str(product / 'state'), payloadFiles=inventory)
        (template / 'installation.json').write_text(json.dumps(asdict(manifest)))

    def test_upgrade_registers_owned_workspace_and_preserves_identity_in_fresh_shells(self):
        f = self.fixture
        receipt = prepare_release_upgrade(f.isolation, self.previous, self.target, f.idea)
        self.assertEqual(receipt.previous.version, '1.2.2')
        self.assertEqual(receipt.target.version, '1.2.3')
        self.assertEqual(receipt.installerSourceCommit, self.target.commit)
        self.assertEqual([len(session.invocations) for session in (*receipt.previousSessions, *receipt.targetSessions)], [4] * 4)
        self.assertEqual(json.loads((f.product / 'config/workspaces.json').read_text())['roots'], [str(f.root / 'workspace')])
        self.assertEqual((f.root / 'bin/kast').resolve(), f.product / 'bin/kast-complete')
        self.assertEqual(list((f.root / 'workspace').iterdir()), [])
        self.assertEqual(digest(self.target.control), self.target.controlSha256)
        self.assertEqual(digest(self.previous.control), self.previous.controlSha256)

    def test_missing_retirement_observation_rejects_upgrade(self):
        self.observations.write_text(json.dumps(asdict(Observation('PRIOR_ADMISSION'))) + '\n')
        with self.assertRaises(ReleaseRejected) as result:
            prepare_release_upgrade(self.fixture.isolation, self.previous, self.target, self.fixture.idea)
        self.assertEqual(result.exception.failure, ReleaseFailure.UPGRADE)

    def test_wrong_saved_runtime_rejects_with_bounded_invocation_identity(self):
        path = self.fixture.template / 'config/configuration.json'
        path.write_text(json.dumps(asdict(Configuration((Assignment('/wrong/runtime'),)))))
        with self.assertRaises(SessionRejected) as result:
            prepare_release_upgrade(self.fixture.isolation, self.previous, self.target, self.fixture.idea)
        self.assertEqual(result.exception.invocation.command.value, 'saved-configuration')
        self.assertEqual(result.exception.invocation.productSha256, self.target.controlSha256)
        self.assertNotIn('/wrong/runtime', json.dumps(asdict(result.exception.invocation)))


if __name__ == '__main__':
    unittest.main()
