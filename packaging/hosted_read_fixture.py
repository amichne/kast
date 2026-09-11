"""The existing authored semantic oracle, installed only during private fixture setup."""
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat


class ReadFixtureRejected(ValueError):
    pass


@dataclass(frozen=True)
class ReadFixture:
    workspace: Path
    files: tuple[tuple[str, str], ...]
    oracle: dict

    def unchanged(self):
        return all(_digest(self.workspace / name) == expected for name, expected in self.files)

    def evidence(self):
        encoded = json.dumps(self.files, separators=(',', ':')).encode()
        return {'fileCount': len(self.files), 'sha256': hashlib.sha256(encoded).hexdigest(),
                'oracle': 'checked-in-semantic-fixture-expected-v1',
                'oracleSha256': hashlib.sha256(json.dumps(self.oracle, sort_keys=True).encode()).hexdigest()}


def _digest(path):
    info = path.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
            or info.st_size > 1024 * 1024):
        raise ReadFixtureRejected('READ_FIXTURE_FILE_REJECTED')
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare_read_fixture(workspace: Path, repo: Path) -> ReadFixture:
    """Call after prepare_hosted_fixture and before its first IDE launch/import."""
    workspace, repo = Path(workspace), Path(repo)
    target = workspace / 'src/main/kotlin/Fixture.kt'
    if (not workspace.is_absolute() or workspace.resolve() != workspace
            or workspace.name != 'workspace' or not target.is_file()
            or any((workspace / name).exists() for name in ('core', 'logging', 'noise0'))):
        raise ReadFixtureRejected('READ_FIXTURE_OWNERSHIP_REJECTED')
    _admit_prepared_fixture(workspace, target)
    template = repo / 'experiments/host-observation/semantic-fixture'
    source_files = sorted(template.rglob('*'))
    if (len(source_files) > 128 or any(path.is_symlink() for path in source_files)
            or any(path.is_file() and path.suffix not in ('.kt', '.kts', '.json') for path in source_files)):
        raise ReadFixtureRejected('READ_FIXTURE_TEMPLATE_REJECTED')
    oracle = json.loads((template / 'expected.json').read_text())
    if oracle.get('schemaVersion') != 1:
        raise ReadFixtureRejected('READ_FIXTURE_ORACLE_REJECTED')
    for name in ('core', 'logging'):
        shutil.copytree(template / name, workspace / name)
    (workspace / 'noise0').mkdir(mode=0o700)
    for name in ('build.gradle.kts', 'settings.gradle.kts'):
        _digest(workspace / name)
    (workspace / 'settings.gradle.kts').write_text(
        'rootProject.name = "hosted-change-acceptance"\ninclude(":core", ":logging", ":noise0")\n')
    build = workspace / 'build.gradle.kts'
    build.write_text(build.read_text() + '\nsubprojects {\n'
        '    apply(plugin = "org.jetbrains.kotlin.jvm")\n'
        '    repositories { mavenCentral() }\n}\n')
    files = [target, build, workspace / 'settings.gradle.kts']
    for name in ('core', 'logging'):
        files.extend(path for path in (workspace / name).rglob('*') if path.is_file())
    inventory = tuple(sorted((str(path.relative_to(workspace)), _digest(path)) for path in files))
    return ReadFixture(workspace, inventory, oracle)


def _admit_prepared_fixture(workspace, target):
    root = workspace.parent
    info = root.lstat()
    if stat.S_IMODE(info.st_mode) != 0o700 or info.st_uid != os.getuid():
        raise ReadFixtureRejected('READ_FIXTURE_OWNERSHIP_REJECTED')
    receipt = root / 'hosted-inputs.json'
    _digest(receipt)
    inputs = json.loads(receipt.read_text())
    if (inputs.get('fixtureRoot') != str(root) or inputs.get('workspaceRoot') != str(workspace)
            or inputs.get('status') != 'prepared' or inputs.get('nativeAcceptance') != 'not-run'
            or inputs.get('sourcePreimageSha256') != _digest(target)):
        raise ReadFixtureRejected('READ_FIXTURE_OWNERSHIP_REJECTED')
