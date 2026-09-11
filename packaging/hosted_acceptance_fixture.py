"""Dedicated native hosted fixture inputs; never installs into a daily IDE profile.

The external application is a read-only executable input. AcceptanceEnvironment
owns every writable path and process. Preparation proves input identity only;
it does not prove import, indexing, provider routing, or mutation success.
"""
from dataclasses import dataclass
from enum import Enum
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import tomllib
import zipfile
from xml.sax.saxutils import quoteattr

from acceptance_environment import AcceptanceEnvironment
from acceptance_idea import digest


class FixtureFailure(Enum):
    IDEA_HOME = 'idea-home-rejected'
    IDEA_METADATA = 'idea-metadata-rejected'
    IDEA_BUILD = 'idea-build-rejected'
    IDEA_LAUNCHER = 'idea-launcher-rejected'
    PLUGIN_ARCHIVE = 'plugin-archive-rejected'
    PLUGIN_IDENTITY = 'plugin-identity-rejected'
    FIXTURE_OWNERSHIP = 'fixture-ownership-rejected'


class FixtureRejected(ValueError):
    def __init__(self, condition: FixtureFailure):
        self.condition = condition
        super().__init__(condition.value)


@dataclass(frozen=True)
class HostedIdea:
    home: Path
    build: str
    kotlin_build: str
    java: Path
    launcher: Path
    metadata_digest: str


@dataclass(frozen=True)
class PreparedHostedFixture:
    workspace: Path
    environment: dict[str, str]
    command: tuple[str, ...]
    input_receipt: Path
    source: Path


def admit_hosted_idea(home: Path, catalog: Path) -> HostedIdea:
    if not home.is_absolute() or home.is_symlink() or not home.is_dir():
        raise FixtureRejected(FixtureFailure.IDEA_HOME)
    home = home.resolve(strict=True)
    metadata = home / 'Resources/product-info.json'
    if not metadata.is_file() or metadata.is_symlink() or metadata.stat().st_size > 2 * 1024 * 1024:
        raise FixtureRejected(FixtureFailure.IDEA_METADATA)
    try:
        document = json.loads(metadata.read_text())
        versions = tomllib.loads(catalog.read_text())['versions']
        build, kotlin_build = versions['ide-host-build'], versions['ide-kotlin-plugin-build']
    except (KeyError, TypeError, ValueError):
        raise FixtureRejected(FixtureFailure.IDEA_METADATA) from None
    if document.get('buildNumber') != build:
        raise FixtureRejected(FixtureFailure.IDEA_BUILD)
    launches = [item for item in document.get('launch', [])
                if item.get('os') == 'macOS' and item.get('arch') == 'aarch64']
    java = home / 'jbr/Contents/Home/bin/java'
    launcher = home / 'MacOS/idea'
    options = home / 'bin/idea.vmoptions'
    if (len(launches) != 1 or any(not path.is_file() or path.is_symlink()
            or not os.access(path, os.X_OK) for path in (java, launcher))
            or not options.is_file() or options.is_symlink()):
        raise FixtureRejected(FixtureFailure.IDEA_LAUNCHER)
    return HostedIdea(home, build, kotlin_build, java, launcher, digest(metadata))


def stage_hosted_plugin(archive: Path, destination: Path, idea: HostedIdea) -> str:
    if not archive.is_absolute() or archive.is_symlink() or not archive.is_file():
        raise FixtureRejected(FixtureFailure.PLUGIN_ARCHIVE)
    if destination.exists() or destination.is_symlink():
        raise FixtureRejected(FixtureFailure.FIXTURE_OWNERSHIP)
    try:
        with zipfile.ZipFile(archive) as bundle:
            members = bundle.infolist()
            if not members or sum(item.file_size for item in members) > 256 * 1024 * 1024:
                raise FixtureRejected(FixtureFailure.PLUGIN_ARCHIVE)
            names = set()
            for item in members:
                path = PurePosixPath(item.filename)
                if (path.is_absolute() or '..' in path.parts or '\\' in item.filename
                        or path.parts[0] != 'kast-ide-hosted' or item.filename in names
                        or stat.S_ISLNK(item.external_attr >> 16)):
                    raise FixtureRejected(FixtureFailure.PLUGIN_ARCHIVE)
                names.add(item.filename)
            metadata = []
            for item in members:
                if not item.filename.endswith('.jar'):
                    continue
                with zipfile.ZipFile(io.BytesIO(bundle.read(item))) as jar:
                    if 'kast-hosted-query.properties' in jar.namelist():
                        raw = jar.read('kast-hosted-query.properties').decode()
                        metadata.append(dict(line.split('=', 1) for line in raw.splitlines()
                                             if line and not line.startswith('#')))
            if (len(metadata) != 1 or metadata[0].get('ideBuild') != idea.build
                    or metadata[0].get('kotlinBuild') != idea.kotlin_build):
                raise FixtureRejected(FixtureFailure.PLUGIN_IDENTITY)
            destination.mkdir(mode=0o700)
            bundle.extractall(destination)
    except (zipfile.BadZipFile, UnicodeError, ValueError) as error:
        if isinstance(error, FixtureRejected):
            raise
        raise FixtureRejected(FixtureFailure.PLUGIN_ARCHIVE) from None
    return digest(archive)


def prepare_hosted_fixture(isolation: AcceptanceEnvironment, repo: Path,
                           idea: HostedIdea, archive: Path) -> PreparedHostedFixture:
    root = isolation.root
    workspace = root / 'workspace'
    if workspace.resolve() != workspace or any(workspace.iterdir()):
        raise FixtureRejected(FixtureFailure.FIXTURE_OWNERSHIP)
    ide = root / 'ide'
    ide.mkdir(mode=0o700)
    for name in ('config', 'system', 'log'):
        (ide / name).mkdir(mode=0o700)
    plugin_digest = stage_hosted_plugin(archive, ide / 'plugins', idea)
    (workspace / 'settings.gradle.kts').write_text('rootProject.name = "hosted-change-acceptance"\n')
    (workspace / 'build.gradle.kts').write_text(
        'plugins { kotlin("jvm") version "2.3.10" }\nrepositories { mavenCentral() }\n')
    (workspace / 'gradle.properties').write_text('org.gradle.jvmargs=-Xmx1g\n')
    source = workspace / 'src/main/kotlin/Fixture.kt'
    source.parent.mkdir(parents=True)
    source.write_text('package fixture\n\nclass NativeChangeTarget(val value: String)\n')
    (workspace / 'gradle/wrapper').mkdir(parents=True)
    for name in ('gradle-wrapper.jar', 'gradle-wrapper.properties'):
        shutil.copyfile(repo / 'gradle/wrapper' / name, workspace / 'gradle/wrapper' / name)
    shutil.copyfile(repo / 'gradlew', workspace / 'gradlew')
    (workspace / 'gradlew').chmod(0o700)
    # Trust only the fixture authored above, in this profile. The project trust
    # mechanism remains enabled for every other path.
    (ide / 'config/options').mkdir()
    (ide / 'config/options/trusted-paths.xml').write_text(
        '<application><component name="Trusted.Paths"><option name="TRUSTED_PROJECT_PATHS">'
        '<map><entry key=' + quoteattr(str(workspace)) + ' value="true" />'
        '</map></option></component></application>\n')
    # Link a Gradle project, then let IDEA perform the real model import. No
    # module, source-root, library, or semantic ownership model is synthesized.
    (workspace / '.idea').mkdir()
    (workspace / '.idea/gradle.xml').write_text(
        '<project version="4"><component name="GradleSettings">'
        '<option name="linkedExternalProjectsSettings"><GradleProjectSettings>'
        '<option name="externalProjectPath" value="$PROJECT_DIR$" />'
        '<option name="distributionType" value="DEFAULT_WRAPPED" />'
        '<option name="gradleJvm" value="#JAVA_HOME" />'
        '</GradleProjectSettings></option></component></project>\n')
    options = ide / 'idea.vmoptions'
    lines = (idea.home / 'bin/idea.vmoptions').read_text().splitlines()
    # Native launcher receives explicit private paths; it cannot select the daily profile.
    lines.extend(f'-Didea.{name}.path={ide / name}' for name in ('config', 'system', 'plugins', 'log'))
    lines.extend((f'-Duser.home={root / "home"}', f'-Djava.io.tmpdir={root / "tmp"}',
                  '-Didea.initially.ask.config=never', '-Djb.consents.confirmation.enabled=false',
                  '-Dide.experimental.ui.onboarding=false'))
    options.write_text('\n'.join(lines) + '\n')
    environment = dict(isolation.environment)
    environment.update(IDEA_VM_OPTIONS=str(options), JAVA_HOME=str(idea.java.parent.parent))
    receipt = root / 'hosted-inputs.json'
    receipt.write_text(json.dumps({
        'schemaVersion': 1, 'status': 'prepared', 'nativeAcceptance': 'not-run',
        'fixtureRoot': str(root), 'workspaceRoot': str(workspace),
        'ideaBuild': idea.build, 'kotlinBuild': idea.kotlin_build,
        'ideaHome': str(idea.home), 'ideaMetadataSha256': idea.metadata_digest,
        'ideaJavaSha256': digest(idea.java), 'pluginArchiveSha256': plugin_digest,
        'sourcePreimageSha256': digest(source),
        'gradleWrapperJarSha256': digest(workspace / 'gradle/wrapper/gradle-wrapper.jar'),
        'gradleWrapperPropertiesSha256': digest(workspace / 'gradle/wrapper/gradle-wrapper.properties'),
        'writableIdePaths': {name: str(ide / name) for name in ('config', 'system', 'plugins', 'log')},
        'isolation': 'private process environment; external read-only application; no copied user caches',
    }, indent=2) + '\n')
    receipt.chmod(0o600)
    return PreparedHostedFixture(workspace, environment, (str(idea.launcher), str(workspace)), receipt, source)
