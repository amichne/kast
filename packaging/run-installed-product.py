"""Check assembled artifacts and stage the sole installation in an owned HOME."""
from dataclasses import asdict, dataclass
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import sqlite3
import stat
import subprocess
import tarfile
import zipfile
import xml.etree.ElementTree as ET
from typing import Literal

from installer_fixture import InstallerFixture, FixtureFailure, FixtureRejected, admitted_tools
import managed_release as release


@dataclass(frozen=True)
class IdeaLaunch:
    os: str = "macOS"
    arch: str = "aarch64"
    launcherPath: str = "../MacOS/idea"


@dataclass(frozen=True)
class IdeaProductInfo:
    buildNumber: str
    dataDirectoryName: str
    version: str
    launch: tuple[IdeaLaunch, ...] = (IdeaLaunch(),)


@dataclass(frozen=True)
class AdmittedArtifactPair:
    control_version: str
    host_version: str
    control: Path
    host: Path
    host_release_record: Path
    host_installer: Path
    target: release.SdkTarget
    identity: release.ArchiveBuildIdentity
    target_profile_sha256: str


@dataclass(frozen=True)
class ReportArtifact:
    fileName: str
    sha256: str
    bytes: int


@dataclass(frozen=True)
class InstalledProductReport:
    intellijReleaseLine: str
    ideBuild: str
    kotlinPluginBuild: str
    targetProfileSha256: str
    controlVersion: str
    hostedPluginVersion: str
    sourceRevision: str
    sourceTree: str
    controlArtifact: ReportArtifact
    hostArtifact: ReportArtifact
    hostReleaseRecord: ReportArtifact
    schemaVersion: Literal[1] = 1
    scope: Literal['INSTALLATION_CONTRACT_METADATA_FIXTURE'] = 'INSTALLATION_CONTRACT_METADATA_FIXTURE'
    status: Literal['PASSED'] = 'PASSED'
    runtimeIdeLaunched: Literal[False] = False
    completedChecks: tuple[str, ...] = (
        'ARTIFACT_IDENTITY', 'NATIVE_LAUNCHER', 'CONTROL_STAGING',
        'INSTALLED_QUERY_DISPATCH', 'NATIVE_HOST_AND_STATE_CLEANUP',
    )


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise AssertionError("installed-product: " + detail)


def verify_control_archive_matches_product(product: Path, control: Path) -> None:
    """Bind the exercised private tree to every reported archive file and mode."""
    names: set[str] = set()
    files: set[str] = set()
    total = 0
    with tarfile.open(control, 'r:gz') as archive:
        for member in archive:
            name = member.name.rstrip('/') if member.isdir() else member.name
            parts = name.split('/')
            total += member.size
            require(len(names) < release.MAXIMUM_ENTRIES and 0 <= total <= release.MAXIMUM_PAYLOAD_BYTES,
                    'Control archive bounds')
            require(name not in names and member.size >= 0 and len(name) <= 4096
                    and len(parts) <= 32 and '\\' not in name
                    and all(part not in ('', '.', '..') for part in parts)
                    and parts[0] in ('bin', 'lib', 'share')
                    and (member.isdir() or member.isfile()), 'Control archive path or type')
            names.add(name)
            path = product.joinpath(*parts)
            require(path.resolve(strict=True) == path and not path.is_symlink(),
                    'Control product path')
            require(member.mode & ~0o777 == 0 and stat.S_IMODE(path.stat().st_mode) == member.mode,
                    'Control product mode ' + name)
            if member.isdir():
                require(path.is_dir(), 'Control product directory ' + name)
                continue
            require(path.is_file() and path.stat().st_size == member.size,
                    'Control product file size ' + name)
            opened = archive.extractfile(member)
            require(opened is not None, 'Control archive file ' + name)
            digest = hashlib.sha256()
            with opened:
                while block := opened.read(65536):
                    digest.update(block)
            require(release.file_digest(path) == digest.hexdigest(),
                    'Control product bytes ' + name)
            files.add(name)
    actual: set[str] = set()
    entries = 0
    for path in product.rglob('*'):
        entries += 1
        require(entries <= release.MAXIMUM_ENTRIES and not path.is_symlink()
                and (path.is_file() or path.is_dir()), 'Control product entry')
        if path.is_file():
            actual.add(path.relative_to(product).as_posix())
    require(bool(files) and files == actual, 'Control product inventory')


def verify_artifacts(product: Path, control: Path, plugin: Path, host_record: Path,
                     target_line: str) -> AdmittedArtifactPair:
    verify_control_archive_matches_product(product, control)
    raw_profile = (product / 'share/kast/intellij-targets.json').read_bytes()
    profile = release.parse_supported_ide_targets(raw_profile)
    require(isinstance(profile, release.SupportedIdeTargets), 'target profile')
    target = next((item for item in profile.targets if item.intellijReleaseLine == target_line), None)
    require(target is not None, 'undeclared target')
    metadata = json.loads((product / "share/kast/ide-host.json").read_text())
    require(set(metadata) == {"schemaVersion", "productVersion", "execution", "ideaBuild",
                             "kotlinPluginBuild", "requiredHostedContract"}, "metadata fields")
    require(metadata["schemaVersion"] == 2 and metadata["execution"] == "existing_ide", "execution metadata")
    require(metadata["kotlinPluginBuild"] == metadata["ideaBuild"] + "-IJ", "plugin build")
    default = profile.targets[0]
    require((metadata['ideaBuild'], metadata['kotlinPluginBuild']) ==
            (default.ideBuild, default.kotlinPluginBuild), 'common Control default profile')
    control_match = re.fullmatch(r"kast-control-v(\d+\.\d+\.\d+)-macos-aarch64\.tar\.gz", control.name)
    require(control_match is not None and metadata["productVersion"] == control_match.group(1), "control version")
    record = json.loads(host_record.read_text())
    require(set(record) == {"type", "hostedPluginVersion", "artifact", "providedHostedContract",
                            "supportedIntellijReleaseLine", "sourceRevision"}, "host release fields")
    require(record["type"] == "HOST_RELEASE", "host release discriminator")
    host_match = re.fullmatch(r"kast-ide-hosted-v(\d+\.\d+\.\d+)-idea-(\d{3})\.zip", plugin.name)
    require(host_match is not None and record["hostedPluginVersion"] == host_match.group(1)
            and record["supportedIntellijReleaseLine"] == host_match.group(2) == target_line,
            "host version and explicit target")
    expected_record = (f"kast-host-release-v{record['hostedPluginVersion']}.json" if target_line == '262'
                       else f"kast-host-release-v{record['hostedPluginVersion']}-idea-263.json")
    require(host_record.name == expected_record, 'host record identity')
    admitted_record = release.parse_component(host_record.read_bytes(), record['hostedPluginVersion'], plugin.name, target_line)
    require(isinstance(admitted_record, release.ComponentRecord), 'strict Host record')
    build = json.loads((product / 'share/kast/build-identity.json').read_text())
    require(set(build) == {'version', 'sourceRevision', 'sourceTree'}
            and build['version'] == metadata['productVersion']
            and release.revision(build['sourceRevision']) and release.revision(build['sourceTree']),
            'Control source identity')
    identity = release.ArchiveBuildIdentity(build['version'], build['sourceRevision'], build['sourceTree'])
    require(record['sourceRevision'] == identity.buildRevision, 'Host and Control source identity')
    artifact = record["artifact"]
    require(set(artifact) == {"fileName", "sha256", "bytes"}
            and artifact["fileName"] == plugin.name and artifact["bytes"] == plugin.stat().st_size, "plugin identity")
    require(artifact["sha256"] == "sha256:" + hashlib.sha256(plugin.read_bytes()).hexdigest(), "plugin digest")
    require(record["providedHostedContract"] == metadata["requiredHostedContract"], "fresh pair contract")
    for name in ("operation-registry.json", "wire-schema.json", "ide-host.json", "knowledge/manifest.json"):
        require((product / "share/kast" / name).is_file(), "missing resource " + name)
    require(record['hostedPluginVersion'] == identity.version, 'qualified pair version')
    verify_plugin_archive(plugin, record["hostedPluginVersion"], record["providedHostedContract"], target, identity)
    with tarfile.open(control) as archive:
        for launcher in ("bin/kast", "bin/kast-codex", "share/kast/libexec/kast-daemon",
                         "share/kast/libexec/kast-service", "share/kast/libexec/kast-management"):
            member = archive.getmember(launcher)
            require(member.isfile() and member.mode & 0o111 == 0o111, "launcher " + launcher)
        require(archive.getmember("share/kast/knowledge/manifest.json").isfile(), "knowledge manifest")
        require(archive.getmember("share/kast/reset-fence-v1").isfile(), "reset lifecycle marker")
        require(archive.extractfile("share/kast/public-reset-v1").read() == b"1\n", "public reset capability")
    helper = product / "share/kast/host-installation.py"
    require(helper.is_file() and not helper.is_symlink(), "host installer")
    return AdmittedArtifactPair(control_match.group(1), host_match.group(1), control, plugin, host_record, helper,
                                target, identity, hashlib.sha256(raw_profile).hexdigest())


def verify_plugin_archive(plugin: Path, host_version: str, provided_contract: dict,
                          target: release.SdkTarget | None = None,
                          identity: release.ArchiveBuildIdentity | None = None) -> None:
    with zipfile.ZipFile(plugin) as archive:
        names = archive.namelist()
        require(bool(names) and all(name.startswith("kast-ide-hosted/") for name in names), "plugin layout")
        require(len(names) == len(set(names)), "duplicate plugin entries")
        plugin_jar = "kast-ide-hosted/lib/kast-ide-hosted.jar"
        require(plugin_jar in names, "plugin jar")
        with zipfile.ZipFile(io.BytesIO(archive.read(plugin_jar))) as jar:
            require(ET.fromstring(jar.read("META-INF/plugin.xml")).findtext("version")
                    == host_version, "embedded host version")
            require(json.loads(jar.read("hosted-contract.json")) == provided_contract,
                    "embedded host contract")
    if target is not None or identity is not None:
        require(target is not None and identity is not None, 'target/source proof required together')
        contract = release.parse_contract(provided_contract)
        require(isinstance(contract, release.HostedContract), 'current Host contract')
        require(release.inspect_host(plugin, identity, contract, target) is release.Admission.ADMITTED,
                'target descriptor, SDK/Kotlin and source properties')


def fixture_idea_info(target: release.SdkTarget) -> IdeaProductInfo:
    """Synthetic vendor metadata for installation contracts; never a running SDK witness."""
    version = '2026.2' if target.intellijReleaseLine == '262' else '2026.3'
    return IdeaProductInfo(target.ideBuild, 'IntelliJIdea' + version, version)


def verify_launcher(fixture: InstallerFixture, product: Path, pair: AdmittedArtifactPair) -> None:
    launcher = product / "bin/kast"
    require(launcher.is_file() and os.access(launcher, os.X_OK), "launcher absent")
    environment = dict(fixture.environment)
    environment.pop("JAVA_TOOL_OPTIONS", None)
    environment.pop("_JAVA_OPTIONS", None)
    environment["JAVA_OPTS"] = f'-Duser.home="{fixture.root / "home"}"'
    version = subprocess.run([str(launcher), "--version"], cwd=fixture.root / "workspace",
                             env=environment, capture_output=True, text=True, timeout=60)
    require(version.returncode == 0 and version.stdout.startswith(f"kast {pair.control_version} ")
            and version.stdout.strip().endswith("(IntelliJ plugin)"), "launcher version")
    rejected = subprocess.run([str(launcher), "tool"], cwd=fixture.root / "workspace",
                              env=environment, capture_output=True, text=True, timeout=60)
    require(rejected.returncode != 0, "unsupported command accepted")
    require(json.loads(rejected.stderr.splitlines()[-1]) == {
        "status": "rejected", "boundary": "usage", "reason": "unsupported-private-installer-command"
    }, "unsupported command result")


def verify_assembled_installer(fixture: InstallerFixture, pair: AdmittedArtifactPair, product: Path) -> None:
    if "java" not in fixture.tools:
        raise FixtureRejected(FixtureFailure.INVALID_INPUT)
    assets = fixture.root / "release-assets"
    assets.mkdir()
    for source in (pair.control, pair.host, pair.host_release_record, pair.host_installer):
        destination = assets / source.name
        shutil.copyfile(source, destination)
        digest = hashlib.sha256(destination.read_bytes()).hexdigest()
        (assets / (source.name + ".sha256")).write_text(f"{digest}  {source.name}\n")

    metadata = fixture_idea_info(pair.target)
    idea = fixture.root / "IntelliJ IDEA.app/Contents"
    for relative in ("Resources", "MacOS", "plugins/Kotlin", "jbr/Contents/Home/bin"):
        (idea / relative).mkdir(parents=True)
    (idea / "Resources/build.txt").write_text("IU-" + metadata.buildNumber + "\n")
    (idea / "Resources/product-info.json").write_text(json.dumps(asdict(metadata)))
    kotlin = idea / 'plugins/Kotlin/lib/kotlin-plugin.jar'
    kotlin.parent.mkdir(parents=True)
    with zipfile.ZipFile(kotlin, 'w') as archive:
        archive.writestr('META-INF/plugin.xml', '<idea-plugin><id>org.jetbrains.kotlin</id>'
                         f'<version>{pair.target.kotlinPluginBuild}</version></idea-plugin>')
    (idea / "jbr/Contents/Home/release").write_text('JAVA_VERSION="25.0.2"\nOS_ARCH="aarch64"\n')
    java = idea / "jbr/Contents/Home/bin/java"
    java.write_text("#!/bin/sh\nexec " + shlex.quote(str(fixture.tools["java"])) + ' "$@"\n')
    java.chmod(0o755)
    launcher = idea / "MacOS/idea"
    launcher.write_text("#!/bin/sh\nexit 1\n")
    launcher.chmod(0o755)

    environment = dict(fixture.environment)
    installation = Path(environment["HOME"]) / ".local/share/kast"
    environment.update({
        "KAST_VERSION": pair.control_version,
        "KAST_HOST_VERSION": pair.host_version,
        "KAST_INSTALL_ASSETS_DIRECTORY": str(assets),
        "KAST_INSTALL_PROFILE": "persistent",
        "NO_COLOR": "1",
    })
    result = subprocess.run(
        [str(fixture.tools["bash"]), str(Path(__file__).resolve().parent.parent / "install.sh"),
         "--idea-home", str(idea), "--stage-only", "--skip-codex-mcp", "--verbose"],
        env=environment, cwd=fixture.root / "workspace", capture_output=True, text=True, timeout=120,
    )
    reports = [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
    receipt_file = installation / "management.json"
    require(receipt_file.is_file() and not receipt_file.is_symlink(), "management receipt absent:\n" + result.stderr[-4096:])
    receipt = json.loads(receipt_file.read_text())
    publication = Path(environment["XDG_CONFIG_HOME"]) / "kast"
    require(result.returncode == 0 and (installation / "installation").is_dir()
            and not (installation / "installation").is_symlink()
            and not (installation / "current").exists()
            and not (installation / "versions").exists()
            and not (installation / "recovery/replacement").exists()
            and publication.is_file() and not publication.is_symlink()
            and receipt.get("schemaVersion") == 2
            and receipt.get("installationRoot") == str(installation)
            and receipt.get("executable") == str(publication)
            and receipt.get("executableSha256") == hashlib.sha256(publication.read_bytes()).hexdigest()
            and not (Path(environment["HOME"]) / 'Library/Application Support/JetBrains' / metadata.dataDirectoryName / 'plugins/kast-ide-hosted').exists()
            and any(report.get("operation") == "installation.install"
                    and report.get("semanticVersion") == pair.control_version
                    and report.get("installation") == str(installation / "installation")
                    and report.get("activation") == {"type": "not-requested"}
                    and report.get("status") == "installed" for report in reports),
            "assembled installer rejected release:\n" + result.stderr[-4096:] + "\n" + result.stdout[-4096:])
    verify_installed_query(fixture, publication, environment)
    verify_native_uninstall(fixture, pair, product, installation, publication, environment, metadata)


def verify_installed_query(fixture: InstallerFixture, publication: Path, environment: dict[str, str]) -> None:
    """The installed public route must admit its real query launcher before rejecting an empty workspace."""
    result = subprocess.run([str(publication), "query", "--name", "OrderService"],
                            cwd=fixture.root / "workspace", env={**environment, "KAST_OUTPUT_TOON": "0"},
                            capture_output=True, text=True, timeout=60)
    require(result.returncode == 3 and json.loads(result.stdout) == {
        "type": "rejected", "failure": "OUT_OF_SCOPE"
    }, "installed query startup failed:\n" + result.stderr[-4096:] + "\n" + result.stdout[-4096:])


def verify_native_uninstall(fixture, pair, product, installation, publication, environment, metadata):
    """Real native cleanup; only filesystem and staged Host artifacts inside the owned fixture are used."""
    plugin_root = Path(environment["HOME"]) / 'Library/Application Support/JetBrains' / metadata.dataDirectoryName / 'plugins'
    helper = product / "share/kast/host-installation.py"
    host = subprocess.run([
        str(fixture.tools["python3"]), "-I", str(helper), "--archive", str(pair.host),
        "--sha256", hashlib.sha256(pair.host.read_bytes()).hexdigest(), "--version", pair.host_version,
        "--idea-build", metadata.buildNumber, "--plugin-root", str(plugin_root),
        "--release-record", str(pair.host_release_record),
        "--required-control", str(product / "share/kast/ide-host.json"),
    ], env=environment, capture_output=True, text=True, timeout=60)
    require(host.returncode == 0 and (plugin_root / "kast-ide-hosted").is_dir(),
            "owned Host staging failed:\n" + host.stderr[-4096:])
    namespace = hashlib.sha256(str(fixture.root / "workspace").encode()).hexdigest()
    state = Path(environment["HOME"]) / ".kast/state/workspaces" / namespace
    state.mkdir(parents=True, mode=0o700)
    repository = Path(__file__).resolve().parent.parent
    database = state / "mutation.sqlite"
    # Use the owning schema, without manufacturing mutation, receipt, or recovery success.
    source_names = ("SqliteLiveChangePlanStore.kt", "SqliteMutationRecoveryDatabase.kt", "SqliteLiveChangeReceiptStore.kt")
    owner = repository / "evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite"
    statements = [sql for name in source_names
                  for sql in re.findall(r'"""(CREATE TABLE IF NOT EXISTS .*?)"""', (owner / name).read_text(), re.S)]
    require(len(statements) == 6, "mutation schema owner changed; fixture requires explicit reconciliation")
    with sqlite3.connect(database) as connection:
        for statement in statements:
            connection.execute(statement)
    database.chmod(0o600)
    sentinel = fixture.root / "protected-native-uninstall.txt"
    sentinel.write_text("protected")
    uninstall = subprocess.run([str(publication), "uninstall"], cwd=fixture.root / "workspace",
                               env=environment, capture_output=True, text=True, timeout=90)
    events = [json.loads(line) for line in uninstall.stderr.splitlines() if line.startswith("{")]
    require(uninstall.returncode == 0 and not installation.exists() and not publication.exists()
            and not (plugin_root / "kast-ide-hosted").exists()
            and not plugin_root.parent.joinpath(".kast-plugin-recovery").exists()
            and not Path(environment["HOME"]).joinpath(".kast").exists()
            and sentinel.read_text() == "protected"
            and {"type": "COMPLETED", "artifact": "USER_STATE"} in events
            and {"type": "COMPLETED", "artifact": "HOST_PLUGIN"} in events,
            "native full uninstall failed:\n" + uninstall.stderr[-4096:] + "\n" + uninstall.stdout[-4096:])


def admitted_report_path(value: str | None) -> Path:
    if value is None or not Path(value).is_absolute():
        raise FixtureRejected(FixtureFailure.INVALID_INPUT)
    path = Path(value)
    if os.path.lexists(path):
        raise FixtureRejected(FixtureFailure.INVALID_INPUT)
    parent = path.parent
    missing = []
    while not os.path.lexists(parent):
        missing.append(parent)
        parent = parent.parent
    if not parent.is_dir() or parent.is_symlink() or parent.stat().st_uid != os.getuid():
        raise FixtureRejected(FixtureFailure.INVALID_INPUT)
    for directory in reversed(missing):
        directory.mkdir(mode=0o700)
    if path.parent.resolve() != path.parent:
        raise FixtureRejected(FixtureFailure.INVALID_INPUT)
    return path


def report_artifact(path: Path) -> ReportArtifact:
    raw = path.read_bytes()
    require(bool(raw), 'empty report artifact')
    return ReportArtifact(path.name, hashlib.sha256(raw).hexdigest(), len(raw))


def completed_report(pair: AdmittedArtifactPair) -> InstalledProductReport:
    return InstalledProductReport(
        pair.target.intellijReleaseLine, pair.target.ideBuild, pair.target.kotlinPluginBuild,
        pair.target_profile_sha256, pair.control_version, pair.host_version,
        pair.identity.buildRevision, pair.identity.sourceTree,
        report_artifact(pair.control), report_artifact(pair.host),
        report_artifact(pair.host_release_record),
    )


def main() -> None:
    target_line = os.environ.get('KAST_HOSTED_IDE_RELEASE_LINE')
    if target_line not in ('262', '263'):
        raise FixtureRejected(FixtureFailure.INVALID_INPUT)
    paths = {}
    for key in ("KAST_INSTALLED_PRODUCT", "KAST_CONTROL_ARCHIVE", "KAST_HOSTED_PLUGIN_ARCHIVE", "KAST_HOST_RELEASE_RECORD"):
        value = os.environ.get(key)
        if value is None or not Path(value).is_absolute():
            raise FixtureRejected(FixtureFailure.INVALID_INPUT)
        paths[key] = Path(value).resolve()
    if not paths["KAST_INSTALLED_PRODUCT"].is_dir() or not all(
            paths[key].is_file() for key in ("KAST_CONTROL_ARCHIVE", "KAST_HOSTED_PLUGIN_ARCHIVE", "KAST_HOST_RELEASE_RECORD")):
        raise FixtureRejected(FixtureFailure.INVALID_INPUT)
    report_path = admitted_report_path(os.environ.get('KAST_INSTALLED_PRODUCT_REPORT'))
    with InstallerFixture(admitted_tools()) as fixture:
        product = fixture.stage_product(paths["KAST_INSTALLED_PRODUCT"])
        pair = verify_artifacts(product, paths["KAST_CONTROL_ARCHIVE"], paths["KAST_HOSTED_PLUGIN_ARCHIVE"],
                                paths["KAST_HOST_RELEASE_RECORD"], target_line)
        verify_launcher(fixture, product, pair)
        verify_assembled_installer(fixture, pair, product)
        report = completed_report(pair)
        fixture.mark_passed()
    with report_path.open('x') as output:
        json.dump(asdict(report), output, indent=2)
        output.write('\n')
    print(f'installed-product: target {target_line} installation contracts passed; no runtime IDE was launched')


if __name__ == "__main__":
    main()
