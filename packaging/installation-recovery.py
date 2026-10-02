"""Offline, ownership-only detachment. Never executes an unverified installed payload.

The receipt and this executable live outside bin/lib/share. Quarantined evidence
is never deleted by recovery. An unresolved process is never signalled by PID.
"""
from dataclasses import asdict, dataclass, fields, replace
from enum import Enum
from pathlib import Path
import argparse
import hashlib
import plistlib
import fcntl
import json
import importlib.util
import os
import shutil
import stat
import sys
import typing
import uuid


class Status(str, Enum):
    PREPARED = 'Prepared'
    PLUGIN_PREPARED = 'PluginPrepared'
    PLANNED = 'Planned'
    ACTIVE = 'Active'
    FINALIZING = 'UpgradeFinalizing'
    SEALED = 'UpgradeFinalized'
    CLEAN = 'CleanBaselineRestored'
    UNRESOLVED = 'DetachedWithUnresolvedState'
    BLOCKED = 'RecoveryBlocked'


class Failure(str, Enum):
    OWNERSHIP = 'ANCHOR_OWNERSHIP_UNPROVEN'
    RECEIPT = 'RECEIPT_REJECTED'
    FILESYSTEM = 'FILESYSTEM_REJECTED'
    RETIREMENT = 'RETIREMENT_UNPROVEN'
    EVIDENCE = 'UNRESOLVED_STATE_PRESERVED'
    RESTART = 'IDE_RESTART_REQUIRED'
    PLUGIN = 'PLUGIN_OWNERSHIP_UNPROVEN'


class Rejected(Exception):
    def __init__(self, failure):
        self.failure = failure


@dataclass(frozen=True)
class Identity:
    device: int
    inode: int
    owner: int

    @staticmethod
    def observe(path):
        value = path.lstat()
        if value.st_uid != os.getuid():
            raise Rejected(Failure.OWNERSHIP)
        return Identity(value.st_dev, value.st_ino, value.st_uid)


@dataclass(frozen=True)
class Link:
    path: str
    target: str
    priorTarget: typing.Optional[str]


@dataclass(frozen=True)
class Plugin:
    destination: str
    candidate: str
    candidateIdentity: Identity
    backup: str
    priorIdentity: typing.Optional[Identity]
    quarantine: str


@dataclass(frozen=True)
class LegacyReceipt:
    schemaVersion: int
    installation: str
    installationIdentity: Identity
    links: list[Link]
    priorInstallation: typing.Optional[str]
    plugin: typing.Optional[Plugin]
    pluginRoot: typing.Optional[str]
    stage: Status


@dataclass(frozen=True)
class Receipt:
    schemaVersion: int
    installation: str
    installationIdentity: Identity
    plugin: typing.Optional[Plugin]
    pluginRoot: typing.Optional[str]
    stage: Status


class ReplacementStage(str, Enum):
    PREPARED = 'PREPARED'
    COMMITTED = 'PAYLOAD_COMMITTED'
    FINALIZING = 'FINALIZING'
    RECOVERY_REQUIRED = 'RECOVERY_REQUIRED'


@dataclass(frozen=True)
class NoPrevious:
    type: str


@dataclass(frozen=True)
class PhysicalPrevious:
    type: str
    installation: str
    identity: Identity
    payload: str
    recovery: str


@dataclass(frozen=True)
class LegacyPrevious:
    type: str
    installation: str
    identity: Identity
    selector: str
    target: str
    recovery: str


@dataclass(frozen=True)
class Replacement:
    schemaVersion: int
    stage: ReplacementStage
    installation: str
    installationIdentity: Identity
    previous: typing.Union[NoPrevious, PhysicalPrevious, LegacyPrevious]


@dataclass(frozen=True)
class Report:
    status: Status
    unresolved: list[Failure]
    recoveryExecutable: str


def encode(document):
    return json.dumps(asdict(document), separators=(',', ':'))


def decode(kind, value):
    """Strict dataclass boundary: unknown fields and absent fields are rejected."""
    origin = typing.get_origin(kind)
    if origin is typing.Union:
        if kind == typing.Union[NoPrevious, PhysicalPrevious, LegacyPrevious]:
            if not isinstance(value, dict) or value.get('type') not in {'NONE', 'PHYSICAL', 'LEGACY'}:
                raise Rejected(Failure.RECEIPT)
            return decode({'NONE': NoPrevious, 'PHYSICAL': PhysicalPrevious, 'LEGACY': LegacyPrevious}[value['type']], value)
        if value is None and type(None) in typing.get_args(kind):
            return None
        return decode(next(item for item in typing.get_args(kind) if item is not type(None)), value)
    if origin is list:
        if not isinstance(value, list) or len(value) > 16:
            raise Rejected(Failure.RECEIPT)
        return [decode(typing.get_args(kind)[0], item) for item in value]
    if kind in (str, int):
        if type(value) is not kind or (kind is str and len(value) > 4096):
            raise Rejected(Failure.RECEIPT)
        return value
    if issubclass(kind, Enum):
        try:
            return kind(value)
        except ValueError:
            raise Rejected(Failure.RECEIPT) from None
    if not isinstance(value, dict) or set(value) != {field.name for field in fields(kind)}:
        raise Rejected(Failure.RECEIPT)
    return kind(**{field.name: decode(field.type, value[field.name]) for field in fields(kind)})


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Rejected(Failure.RECEIPT)
        result[key] = value
    return result


def physical(path):
    if not path.is_absolute() or path.resolve(strict=True) != path or not path.is_dir():
        raise Rejected(Failure.OWNERSHIP)
    Identity.observe(path)
    if path.stat().st_mode & 0o022:
        raise Rejected(Failure.OWNERSHIP)
    return path


def sync(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def save(path, document):
    temporary = path.with_name('.receipt-' + uuid.uuid4().hex)
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(descriptor, 'w') as output:
            output.write(encode(document))
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        sync(path.parent)
    finally:
        if temporary.exists():
            temporary.unlink()


def load(path, kind=Receipt):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'rb') as source:
        observed = os.fstat(source.fileno())
        if not stat.S_ISREG(observed.st_mode) or observed.st_uid != os.getuid() or observed.st_mode & 0o077:
            raise Rejected(Failure.RECEIPT)
        raw = source.read(65537)
    if len(raw) > 65536:
        raise Rejected(Failure.RECEIPT)
    try:
        document = json.loads(raw, object_pairs_hook=unique)
        selected = LegacyReceipt if kind is Receipt and isinstance(document, dict) and document.get('schemaVersion') in (1, 2) else kind
        return decode(selected, document)
    except (ValueError, UnicodeDecodeError):
        raise Rejected(Failure.RECEIPT) from None


def location(root):
    physical(root)
    if root.name == 'installation':
        outer = physical(root.parent)
        return outer, outer / 'recovery' / 'installation'
    # Explicit legacy ownership boundary; no new versioned payloads are written.
    if root.parent.name != 'versions':
        raise Rejected(Failure.OWNERSHIP)
    physical(root.parent)
    outer = physical(root.parent.parent)
    return outer, outer / 'recovery' / root.name


def retained_script(bundle):
    return bundle / 'installation-recovery.py'


def prepare(root, bin_directory, plugin_root=None):
    outer, bundle = location(root)
    physical(bin_directory)
    if bundle.exists() and (bundle / 'receipt.json').exists():
        validate(root, load(bundle / 'receipt.json'))
        return Report(Status.PREPARED, [], str(retained_script(bundle)))
    (outer / 'recovery').mkdir(mode=0o700, exist_ok=True)
    physical(outer / 'recovery')
    bundle.mkdir(mode=0o700, exist_ok=True)
    physical(bundle)
    if root.name == 'installation':
        selected_plugin_root = str(physical(plugin_root)) if plugin_root is not None else None
        receipt = Receipt(3, str(root), Identity.observe(root), None, selected_plugin_root, Status.PREPARED)
        copy_recovery_scripts(bundle)
        save(bundle / 'receipt.json', receipt)
        sync(bundle.parent)
        return Report(Status.PREPARED, [], str(retained_script(bundle)))
    current = outer / 'current'
    previous = None
    if os.path.lexists(current):
        if not current.is_symlink():
            raise Rejected(Failure.OWNERSHIP)
        previous = os.readlink(current)
        prior = outer / previous
        if Path(previous).is_absolute() or len(Path(previous).parts) != 2 or prior.parent != root.parent:
            raise Rejected(Failure.OWNERSHIP)
        physical(prior)
    links = [Link(str(current), 'versions/' + root.name, previous)]
    for name in ('kast', 'kast-codex'):
        path = bin_directory / name
        target = str(current / 'bin' / (name + '-complete'))
        prior = None
        if os.path.lexists(path):
            if not path.is_symlink() or os.readlink(path) != target:
                raise Rejected(Failure.OWNERSHIP)
            prior = target
        links.append(Link(str(path), target, prior))
    selected_plugin_root = str(physical(plugin_root)) if plugin_root is not None else None
    receipt = LegacyReceipt(1, str(root), Identity.observe(root), links,
                      str(outer / previous) if previous else None, None, selected_plugin_root, Status.PREPARED)
    copy_recovery_scripts(bundle)
    save(bundle / 'receipt.json', receipt)
    sync(bundle.parent)
    return Report(Status.PREPARED, [], str(retained_script(bundle)))


def copy_recovery_scripts(bundle):
    # Copy the trusted executing bundle, not a file from the damaged installation.
    shutil.copyfile(Path(__file__).resolve(), retained_script(bundle))
    os.chmod(retained_script(bundle), 0o600)
    sync(retained_script(bundle))
    lifecycle = Path(__file__).resolve().with_name('installation-lifecycle.py')
    if lifecycle.is_file():
        shutil.copyfile(lifecycle, bundle / lifecycle.name)
        os.chmod(bundle / lifecycle.name, 0o600)
        sync(bundle / lifecycle.name)


def validate(root, receipt):
    if receipt.schemaVersion not in (1, 2, 3) or receipt.installation != str(root) or receipt.installationIdentity != Identity.observe(root):
        raise Rejected(Failure.RECEIPT)
    outer, bundle = location(root)
    physical(bundle.parent)
    physical(bundle)
    if isinstance(receipt, Receipt):
        if receipt.schemaVersion != 3 or root != outer / 'installation':
            raise Rejected(Failure.RECEIPT)
    else:
        validate_legacy_links(root, outer, receipt)
    if receipt.plugin is not None:
        plugin = receipt.plugin
        destination = Path(plugin.destination)
        if destination.name != 'kast-ide-hosted' or receipt.pluginRoot != str(destination.parent):
            raise Rejected(Failure.RECEIPT)
        physical(destination.parent)
        plugin_layout(plugin)
    return bundle


def validate_legacy_links(root, outer, receipt):
    if receipt.schemaVersion not in (1, 2) or root.parent != outer / 'versions':
        raise Rejected(Failure.RECEIPT)
    if len(receipt.links) != (3 if receipt.schemaVersion == 1 else 1):
        raise Rejected(Failure.RECEIPT)
    current = receipt.links[0]
    if current.path != str(outer / 'current') or current.target != 'versions/' + root.name:
        raise Rejected(Failure.RECEIPT)
    if receipt.schemaVersion == 1:
        command, codex = receipt.links[1:]
        for link, name in ((command, 'kast'), (codex, 'kast-codex')):
            path = Path(link.path)
            physical(path.parent)
            if path.name != name or link.target != str(outer / 'current/bin' / (name + '-complete')):
                raise Rejected(Failure.RECEIPT)
        if Path(command.path).parent != Path(codex.path).parent:
            raise Rejected(Failure.RECEIPT)


class PluginLayout(Enum):
    LEGACY = 'legacy-discovery'
    RETAINED = 'outside-discovery'


PLUGIN_PREFIXES = ('.kast-ide-hosted.install-', '.kast-ide-hosted.baseline-', '.kast-ide-hosted.detached-')
MAXIMUM_PRIOR_INSTALLATIONS = 128


def retention_root(plugins):
    return plugins.parent / '.kast-plugin-recovery'


def plugin_layout(plugin):
    plugins = Path(plugin.destination).parent
    paths = tuple(map(Path, (plugin.candidate, plugin.backup, plugin.quarantine)))
    token = paths[0].name.removeprefix(PLUGIN_PREFIXES[0])
    if len(token) != 32 or any(character not in '0123456789abcdef' for character in token):
        raise Rejected(Failure.RECEIPT)
    if any(path.name != prefix + token for path, prefix in zip(paths, PLUGIN_PREFIXES)):
        raise Rejected(Failure.RECEIPT)
    if all(path.parent == plugins for path in paths):
        return PluginLayout.LEGACY
    if all(path.parent == retention_root(plugins) for path in paths):
        return PluginLayout.RETAINED
    raise Rejected(Failure.RECEIPT)


def prepare_retention(plugins):
    retained = retention_root(plugins)
    physical(plugins.parent)
    retained.mkdir(mode=0o700, exist_ok=True)
    physical(retained)
    if retained.stat().st_dev != plugins.stat().st_dev:
        raise Rejected(Failure.FILESYSTEM)
    return retained


def refresh_recovery_script(bundle):
    # This mutable recovery bundle is outside every immutable version payload.
    temporary = bundle / ('.recovery-script-' + uuid.uuid4().hex)
    try:
        with temporary.open('xb') as target:
            target.write(Path(__file__).resolve().read_bytes())
        temporary.chmod(0o600)
        sync(temporary)
        os.replace(temporary, retained_script(bundle))
        sync(bundle)
    finally:
        if temporary.exists():
            temporary.unlink()


def relocate_inactive(source, destination, identity):
    old, new = os.path.lexists(source), os.path.lexists(destination)
    if not old and not new:
        return
    if identity is None or (old and new):
        raise Rejected(Failure.OWNERSHIP)
    observed = source if old else destination
    if Identity.observe(physical(observed)) != identity:
        raise Rejected(Failure.OWNERSHIP)
    if old:
        source.rename(destination)
        sync(source.parent)
        sync(destination.parent)


def migrate_plugin_receipt(root, receipt):
    bundle = validate(root, receipt)
    if receipt.plugin is None:
        return receipt
    plugin = receipt.plugin
    plugins = Path(plugin.destination).parent
    retained = prepare_retention(plugins)
    layout = plugin_layout(plugin)
    if layout is PluginLayout.LEGACY:
        plugin = replace(plugin, candidate=str(retained / Path(plugin.candidate).name),
                         backup=str(retained / Path(plugin.backup).name),
                         quarantine=str(retained / Path(plugin.quarantine).name))
        receipt = replace(receipt, plugin=plugin)
        # Persist the trusted resumer before the new location intent. The exact legacy
        # counterparts remain derivable if interrupted between the receipt and rename.
        refresh_recovery_script(bundle)
        save(bundle / 'receipt.json', receipt)
    for raw, identity in ((plugin.candidate, plugin.candidateIdentity),
                          (plugin.backup, plugin.priorIdentity),
                          (plugin.quarantine, plugin.candidateIdentity)):
        destination = Path(raw)
        relocate_inactive(plugins / destination.name, destination, identity)
    return receipt


def migrate_plugin_chain(root):
    current = root
    seen = set()
    chain = []
    while current is not None:
        if current in seen or len(chain) >= MAXIMUM_PRIOR_INSTALLATIONS or current.parent != root.parent:
            raise Rejected(Failure.RECEIPT)
        seen.add(current)
        _, bundle = location(current)
        receipt = load(bundle / 'receipt.json')
        validate(current, receipt)
        chain.append((current, receipt))
        current = Path(receipt.priorInstallation) if isinstance(receipt, LegacyReceipt) and receipt.priorInstallation is not None else None
    for current, receipt in reversed(chain):
        migrate_plugin_receipt(current, receipt)
    return load(location(root)[1] / 'receipt.json')


def replace_stage(receipt, stage, plugin=None):
    return replace(receipt, stage=stage, plugin=receipt.plugin if plugin is None else plugin,
                   pluginRoot=receipt.pluginRoot if plugin is None else str(Path(plugin.destination).parent))


def activate_plugin(root, staged, plugin_root, *, force=False):
    outer, bundle = location(root)
    receipt = load(bundle / 'receipt.json')
    validate(root, receipt)
    physical(staged)
    plugin_root.mkdir(parents=True, exist_ok=True)
    physical(plugin_root)
    receipt = migrate_plugin_chain(root)
    retained = prepare_retention(plugin_root)
    destination = plugin_root / 'kast-ide-hosted'
    new_candidate = receipt.plugin is None or (isinstance(receipt, Receipt) and receipt.stage is Status.PREPARED)
    if new_candidate:
        if receipt.plugin is not None:
            previous = receipt.plugin
            if (previous.destination != str(destination) or
                    Identity.observe(physical(destination)) != previous.candidateIdentity):
                raise Rejected(Failure.OWNERSHIP)
        if force and receipt.plugin is None and os.path.lexists(destination):
            if destination.lstat().st_uid != os.getuid():
                raise Rejected(Failure.OWNERSHIP)
            # Move only the named entry; a symlink's target is never traversed.
            destination.rename(retained / ('.replaced-kast-ide-hosted-' + uuid.uuid4().hex))
            sync(retained)
            sync(plugin_root)
        prior = Identity.observe(physical(destination)) if os.path.lexists(destination) else None
        token = uuid.uuid4().hex
        candidate = retained / ('.kast-ide-hosted.install-' + token)
        shutil.copytree(physical(staged / 'kast-ide-hosted'), candidate, symlinks=True)
        # Source archive was admitted by the bootstrap; reject unexpected links on copy as well.
        for current, directories, files in os.walk(candidate, followlinks=False):
            for name in directories + files:
                path = Path(current) / name
                if path.is_symlink():
                    raise Rejected(Failure.OWNERSHIP)
                if path.is_file():
                    sync(path)
            sync(Path(current))
        plugin = Plugin(str(destination), str(candidate), Identity.observe(candidate),
                        str(retained / ('.kast-ide-hosted.baseline-' + token)), prior,
                        str(retained / ('.kast-ide-hosted.detached-' + token)))
        receipt = replace_stage(receipt, Status.PLUGIN_PREPARED if isinstance(receipt, Receipt) else Status.PREPARED, plugin)
        save(bundle / 'receipt.json', receipt)
    plugin = receipt.plugin
    candidate, backup = Path(plugin.candidate), Path(plugin.backup)
    if plugin.destination != str(destination):
        raise Rejected(Failure.OWNERSHIP)
    if destination.exists() and Identity.observe(destination) == plugin.candidateIdentity:
        if receipt.stage is not Status.FINALIZING:
            save(bundle / 'receipt.json', replace_stage(receipt, Status.ACTIVE))
        return Report(Status.ACTIVE, [Failure.RESTART], str(retained_script(bundle)))
    if plugin.priorIdentity is not None and not backup.exists():
        if Identity.observe(physical(destination)) != plugin.priorIdentity:
            raise Rejected(Failure.OWNERSHIP)
        destination.rename(backup)
        sync(backup.parent)
        sync(plugin_root)
    if os.path.lexists(destination) or Identity.observe(physical(candidate)) != plugin.candidateIdentity:
        raise Rejected(Failure.OWNERSHIP)
    candidate.rename(destination)
    sync(candidate.parent)
    sync(plugin_root)
    save(bundle / 'receipt.json', replace_stage(receipt, Status.ACTIVE))
    return Report(Status.ACTIVE, [Failure.RESTART], str(retained_script(bundle)))


def seal_upgrade(root):
    outer, bundle = location(root)
    receipt = load(bundle / 'receipt.json')
    validate(root, receipt)
    if isinstance(receipt, LegacyReceipt):
        selector = outer / 'current'
        if (not selector.is_symlink() or os.readlink(selector) != receipt.links[0].target
                or Identity.observe(selector).owner != os.getuid()):
            raise Rejected(Failure.OWNERSHIP)
    if receipt.plugin is None and receipt.pluginRoot is None and isinstance(receipt, Receipt):
        if receipt.stage not in (Status.PREPARED, Status.ACTIVE, Status.FINALIZING):
            raise Rejected(Failure.RECEIPT)
        prepare_finalization(root)
        finalize_replacement(root)
        save(bundle / 'receipt.json', replace(receipt, stage=Status.ACTIVE))
        return Report(Status.SEALED, [], str(retained_script(bundle)))
    if receipt.stage not in (Status.ACTIVE, Status.FINALIZING) or receipt.plugin is None:
        raise Rejected(Failure.RECEIPT)
    plugin = receipt.plugin
    if (plugin_layout(plugin) is not PluginLayout.RETAINED
            or Identity.observe(physical(Path(plugin.destination))) != plugin.candidateIdentity):
        raise Rejected(Failure.PLUGIN)
    # Activation has already copied legacy plugin evidence into the retained layout.
    # Revalidate the entire prior chain before removing its dependency from this receipt.
    receipt = migrate_plugin_chain(root)
    if isinstance(receipt, LegacyReceipt):
        selected_link = replace(receipt.links[0], priorTarget=None)
        save(bundle / 'receipt.json', replace(receipt, priorInstallation=None, links=[selected_link, *receipt.links[1:]]))
    else:
        prepare_finalization(root)
        if plugin.priorIdentity is not None:
            backup = Path(plugin.backup)
            if os.path.lexists(backup):
                if Identity.observe(physical(backup)) != plugin.priorIdentity:
                    raise Rejected(Failure.PLUGIN)
            elif receipt.stage is not Status.FINALIZING:
                raise Rejected(Failure.PLUGIN)
            if receipt.stage is not Status.FINALIZING:
                receipt = replace(receipt, stage=Status.FINALIZING)
                save(bundle / 'receipt.json', receipt)
            if os.path.lexists(backup):
                shutil.rmtree(backup)
                sync(backup.parent)
            save(bundle / 'receipt.json', replace(receipt, stage=Status.ACTIVE, plugin=replace(plugin, priorIdentity=None)))
        finalize_replacement(root)
    return Report(Status.SEALED, [], str(retained_script(bundle)))


@dataclass(frozen=True)
class RecoveryFence:
    schemaVersion: int
    installation: str
    operation: str


def detach_login(root, bundle):
    home = physical(Path(os.environ['HOME']))
    service_label = 'io.github.amichne.kast.broker.' + hashlib.sha256(str(root).encode()).hexdigest()[:32]
    path = home / 'Library/LaunchAgents' / (service_label + '.login.plist')
    if not os.path.lexists(path):
        return []
    try:
        physical(path.parent)
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(descriptor, 'rb') as source:
            observed = os.fstat(source.fileno())
            if not stat.S_ISREG(observed.st_mode) or observed.st_uid != os.getuid() or observed.st_mode & 0o077:
                return [Failure.OWNERSHIP]
            raw = source.read(65537)
        if len(raw) > 65536:
            return [Failure.OWNERSHIP]
        document = plistlib.loads(raw)
        if not isinstance(document, dict):
            return [Failure.OWNERSHIP]
        arguments = document.get('ProgramArguments')
        legacy = (document.get('Label') == service_label + '.login'
                  and set(document) == {'Label', 'ProgramArguments', 'RunAtLoad', 'EnvironmentVariables'}
                  and arguments == [str(root / 'bin/kast'), 'app-server', 'bootstrap']
                  and document.get('RunAtLoad') is True
                  and isinstance(document.get('EnvironmentVariables'), dict)
                  and b'<!-- Kast App Server login bootstrap v1 -->' in raw)
        direct = document.get('Label') == service_label and exact_service_login(root, raw, document)
        if not (legacy or direct):
            return [Failure.OWNERSHIP]
        if Identity.observe(path) != Identity(observed.st_dev, observed.st_ino, observed.st_uid):
            return [Failure.OWNERSHIP]
        destination = bundle / 'login.plist'
        if destination.exists():
            return [Failure.OWNERSHIP]
        # Cross-device quarantine is not guessed; retain the entry if rename is unavailable.
        path.rename(destination)
        sync(path.parent)
        sync(bundle)
        return []
    except (OSError, ValueError, TypeError, plistlib.InvalidFileException):
        return [Failure.OWNERSHIP]


def exact_service_login(root, raw, document):
    if (set(document) != {'Label', 'ProgramArguments', 'RunAtLoad', 'KeepAlive', 'ThrottleInterval',
                          'StandardOutPath', 'StandardErrorPath'}
            or document.get('RunAtLoad') is not True
            or document.get('KeepAlive') != {'SuccessfulExit': False}
            or document.get('ThrottleInterval') != 10):
        return False
    arguments = document.get('ProgramArguments')
    if (not isinstance(arguments, list) or len(arguments) < 5 or len(arguments) > 64
            or arguments[:2] != ['/usr/bin/env', '-i']
            or arguments[-2:] != [str(root / 'share/kast/libexec/kast-daemon'), '--login']
            or any(not isinstance(value, str) for value in arguments)):
        return False
    assignments = arguments[2:-2]
    if any('=' not in value for value in assignments):
        return False
    environment = dict(value.split('=', 1) for value in assignments)
    if len(environment) != len(assignments):
        return False
    codex_home = environment.get('CODEX_HOME')
    if not codex_home or not Path(codex_home).is_absolute() or os.path.normpath(codex_home) != codex_home:
        return False
    profile = hashlib.sha256(codex_home.encode()).hexdigest()[:16]
    receipt = root / 'state/broker' / profile / 'service.plist'
    try:
        physical(receipt.parent)
        descriptor = os.open(receipt, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(descriptor, 'rb') as source:
            observed = os.fstat(source.fileno())
            if not stat.S_ISREG(observed.st_mode) or observed.st_uid != os.getuid() or observed.st_mode & 0o077:
                return False
            published = source.read(65537)
        login_argument = b'<string>--login</string>'
        return (len(published) <= 65536 and raw.count(login_argument) == 1
                and raw.replace(login_argument, b'', 1) == published)
    except (OSError, ValueError):
        return False


@dataclass(frozen=True)
class StateQuarantine:
    installationIdentity: Identity
    stateIdentity: Identity
    quarantine: str


def retire_and_preserve(root, bundle):
    """Only a fully verified payload may execute retirement; recovery still detaches on rejection."""
    record = bundle / 'retirement.json'
    if record.exists():
        proof = load(record, StateQuarantine)
        destination = Path(proof.quarantine)
        if proof.installationIdentity != Identity.observe(root) or destination.parent != root or not destination.name.startswith('.recovered-state-'):
            raise Rejected(Failure.RECEIPT)
        if destination.exists() and Identity.observe(destination) == proof.stateIdentity and not os.path.lexists(root / 'state'):
            return []
        if destination.exists() or Identity.observe(root / 'state') != proof.stateIdentity:
            raise Rejected(Failure.OWNERSHIP)
        (root / 'state').rename(destination)
        sync(root)
        return []
    lifecycle_path = bundle / 'installation-lifecycle.py'
    if not lifecycle_path.is_file() or lifecycle_path.is_symlink():
        return [Failure.RETIREMENT]
    spec = importlib.util.spec_from_file_location('kast_recovery_lifecycle', lifecycle_path)
    lifecycle = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = lifecycle
    spec.loader.exec_module(lifecycle)
    try:
        installation = lifecycle.Installation.admit(str(root))
        roots = lifecycle.workspaces(installation)
        lifecycle.validate_owned_configuration(installation)
        lifecycle.retire(installation, roots)
        installation.revalidate()
        state_identity = lifecycle.inspect_state(installation)
        workers = root / 'state/workers'
        if workers.exists() and any(workers.iterdir()):
            return [Failure.RETIREMENT]
        # External aliases and login entries must already be absent after retirement.
        if lifecycle.owned_aliases(installation) or lifecycle.owned_upstream_directories(installation):
            return [Failure.OWNERSHIP]
        for anchor in installation.manifest['externalAnchors']:
            if anchor.get('kind') == 'login' and os.path.lexists(anchor['path']):
                return [Failure.OWNERSHIP]
        if state_identity is not None:
            state = root / 'state'
            proof = StateQuarantine(Identity.observe(root), Identity.observe(state), str(root / ('.recovered-state-' + uuid.uuid4().hex)))
            save(record, proof)
            state.rename(Path(proof.quarantine))
            sync(root)
        return []
    except lifecycle.Rejected as rejected:
        return [Failure.EVIDENCE if rejected.failure == lifecycle.Failure.UNRESOLVED_INVOCATION else Failure.RETIREMENT]
    except (OSError, ValueError, TypeError, KeyError):
        return [Failure.RETIREMENT]


def detach(root, dry_run, control_only=True):
    outer, bundle = location(root)
    receipt = load(bundle / 'receipt.json')
    validate(root, receipt)
    if dry_run:
        return Report(Status.PLANNED, [], str(retained_script(bundle)))
    if not control_only:
        receipt = migrate_plugin_chain(root)
    # Fence is independent of payload admission and remains until explicit reinstall.
    fence = root / '.recovery-detached'
    if not os.path.lexists(fence):
        descriptor = os.open(fence, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        os.fsync(descriptor)
        os.close(descriptor)
        sync(root)
    elif not stat.S_ISREG(fence.lstat().st_mode):
        raise Rejected(Failure.OWNERSHIP)
    # Historical runtimes understand this presence fence even without .recovery-detached support.
    transition = root / '.lifecycle-transition.json'
    if not os.path.lexists(transition):
        save(transition, RecoveryFence(1, str(root), 'detach'))
    save(bundle / 'receipt.json', replace_stage(receipt, Status.UNRESOLVED))
    unresolved = retire_and_preserve(root, bundle)
    unresolved.extend(detach_login(root, bundle))
    if isinstance(receipt, LegacyReceipt):
        detach_legacy_links(receipt, unresolved)
    if control_only:
        # Historical plugin evidence remains provenance; control detachment has no host effects.
        pass
    elif receipt.plugin is None:
        if receipt.pluginRoot is not None and os.path.lexists(physical(Path(receipt.pluginRoot)) / 'kast-ide-hosted'):
            unresolved.append(Failure.PLUGIN)
    else:
        plugin = receipt.plugin
        destination, quarantine = Path(plugin.destination), Path(plugin.quarantine)
        physical(destination.parent)
        if plugin_layout(plugin) is not PluginLayout.RETAINED:
            raise Rejected(Failure.RECEIPT)
        if os.path.lexists(destination):
            if destination.is_dir() and not destination.is_symlink() and Identity.observe(destination) == plugin.candidateIdentity and not os.path.lexists(quarantine):
                destination.rename(quarantine)
                sync(quarantine.parent)
                sync(destination.parent)
            else:
                unresolved.append(Failure.OWNERSHIP)
        unresolved.append(Failure.RESTART)
    # A missing/broken executable cannot establish retirement. Preserve state in place;
    # it may still be held open by an old process. No deletion or recursive state scan.
    if os.path.lexists(root / 'state'):
        unresolved.append(Failure.EVIDENCE)
    status = Status.UNRESOLVED if unresolved else Status.CLEAN
    save(bundle / 'receipt.json', replace_stage(receipt, status))
    return Report(status, sorted(set(unresolved), key=lambda value: value.value), str(retained_script(bundle)))


def detach_legacy_links(receipt, unresolved):
    selector = Path(receipt.links[0].path)
    matches = selector.is_symlink() and os.readlink(selector) == receipt.links[0].target
    absent = not os.path.lexists(selector)
    for link in reversed(receipt.links):
        path = Path(link.path)
        if not os.path.lexists(path):
            continue
        if (matches or absent) and path.is_symlink() and os.readlink(path) == link.target:
            Identity.observe(path)
            path.unlink()
            sync(path.parent)
        else:
            unresolved.append(Failure.OWNERSHIP)


def replacement_to_finalize(root):
    outer, _ = location(root)
    transaction = outer / 'recovery/replacement'
    if not os.path.lexists(transaction):
        return None
    physical(transaction)
    receipt = load(transaction / 'receipt.json', Replacement)
    if (receipt.schemaVersion != 1 or receipt.stage not in (ReplacementStage.COMMITTED, ReplacementStage.FINALIZING) or
            receipt.installation != str(root) or receipt.installationIdentity != Identity.observe(root)):
        raise Rejected(Failure.RECEIPT)
    if not shutil.rmtree.avoids_symlink_attacks:
        raise Rejected(Failure.FILESYSTEM)
    previous = receipt.previous
    finalizing = receipt.stage is ReplacementStage.FINALIZING
    if isinstance(previous, PhysicalPrevious):
        payload = Path(previous.payload)
        if (previous.installation != str(root) or previous.payload != str(transaction / 'payload') or
                previous.recovery != str(transaction / 'recovery')):
            raise Rejected(Failure.OWNERSHIP)
        if os.path.lexists(payload):
            if Identity.observe(physical(payload)) != previous.identity:
                raise Rejected(Failure.OWNERSHIP)
        elif not finalizing:
            raise Rejected(Failure.OWNERSHIP)
    elif isinstance(previous, LegacyPrevious):
        prior = Path(previous.installation)
        selector = outer / 'current'
        if (prior.parent != outer / 'versions' or previous.selector != str(selector) or
                previous.target != 'versions/' + prior.name or previous.recovery != str(transaction / 'recovery')):
            raise Rejected(Failure.OWNERSHIP)
        if os.path.lexists(prior):
            if Identity.observe(physical(prior)) != previous.identity:
                raise Rejected(Failure.OWNERSHIP)
        elif not finalizing:
            raise Rejected(Failure.OWNERSHIP)
        if os.path.lexists(selector):
            if not selector.is_symlink() or os.readlink(selector) != previous.target:
                raise Rejected(Failure.OWNERSHIP)
            Identity.observe(selector)
        elif not finalizing:
            raise Rejected(Failure.OWNERSHIP)
    elif isinstance(previous, NoPrevious):
        if os.path.lexists(transaction / 'payload') or os.path.lexists(transaction / 'recovery'):
            raise Rejected(Failure.OWNERSHIP)
    else:
        raise Rejected(Failure.RECEIPT)
    expected = {'receipt.json'} if isinstance(previous, NoPrevious) else {'receipt.json', 'payload', 'recovery'}
    if any(path.name not in expected for path in transaction.iterdir()):
        raise Rejected(Failure.OWNERSHIP)
    recovery = transaction / 'recovery'
    if os.path.lexists(recovery):
        physical(recovery)
    elif not isinstance(previous, NoPrevious) and not finalizing:
        raise Rejected(Failure.OWNERSHIP)
    return transaction, receipt


def prepare_finalization(root):
    admitted = replacement_to_finalize(root)
    if admitted is not None:
        transaction, receipt = admitted
        if receipt.stage is ReplacementStage.COMMITTED:
            save(transaction / 'receipt.json', replace(receipt, stage=ReplacementStage.FINALIZING))


def finalize_replacement(root):
    admitted = replacement_to_finalize(root)
    if admitted is None:
        return
    transaction, receipt = admitted
    if receipt.stage is not ReplacementStage.FINALIZING:
        raise Rejected(Failure.RECEIPT)
    previous = receipt.previous
    if isinstance(previous, PhysicalPrevious):
        payload = Path(previous.payload)
        if os.path.lexists(payload):
            shutil.rmtree(payload)
            sync(transaction)
    elif isinstance(previous, LegacyPrevious):
        prior = Path(previous.installation)
        if os.path.lexists(prior):
            spec = importlib.util.spec_from_file_location('kast_finalize_legacy_lifecycle', Path(__file__).with_name('installation-lifecycle.py'))
            lifecycle = importlib.util.module_from_spec(spec)
            sys.modules[spec.name] = lifecycle
            spec.loader.exec_module(lifecycle)
            try:
                admitted = lifecycle.Installation.admit(str(prior))
                lifecycle.execute(admitted, 'remove', False)
            except lifecycle.Rejected:
                raise Rejected(Failure.RETIREMENT) from None
        selector = Path(previous.selector)
        if os.path.lexists(selector):
            if not selector.is_symlink() or os.readlink(selector) != previous.target:
                raise Rejected(Failure.OWNERSHIP)
            selector.unlink()
            sync(selector.parent)
        if prior.parent.exists() and not any(prior.parent.iterdir()):
            prior.parent.rmdir()
    recovery = transaction / 'recovery'
    if os.path.lexists(recovery):
        shutil.rmtree(recovery)
        sync(transaction)
    (transaction / 'receipt.json').unlink()
    transaction.rmdir()
    sync(transaction.parent)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('prepare', 'activate-plugin', 'seal-upgrade', 'detach', 'detach-legacy-pair'))
    parser.add_argument('--installation', required=True, type=Path)
    parser.add_argument('--bin-directory', type=Path)
    parser.add_argument('--plugin-root', type=Path)
    parser.add_argument('--staged-plugin', type=Path)
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--control-only', action='store_true', help='Detach control while preserving all host plugin files and historical plugin evidence.')
    parser.add_argument('--force', action='store_true', help='Replace the exact Kast plugin entry without following symlinks.')
    arguments = parser.parse_args()
    try:
        if (arguments.operation == 'detach' and not arguments.control_only
                or arguments.control_only and arguments.operation != 'detach'):
            raise Rejected(Failure.RECEIPT)
        if arguments.force and arguments.operation != 'activate-plugin':
            raise Rejected(Failure.RECEIPT)
        outer, bundle = location(arguments.installation)
        if arguments.dry_run:
            if arguments.operation not in ('detach', 'detach-legacy-pair'):
                raise Rejected(Failure.RECEIPT)
            report = detach(arguments.installation, True, control_only=arguments.operation == 'detach')
        else:
            descriptor = os.open(outer / 'activation.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
            try:
                if not stat.S_ISREG(os.fstat(descriptor).st_mode):
                    raise Rejected(Failure.OWNERSHIP)
                # Coordinate with JVM FileChannel locks as well as Python lifecycle callers.
                fcntl.lockf(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                if arguments.operation == 'prepare' and arguments.bin_directory is not None:
                    report = prepare(arguments.installation, arguments.bin_directory, arguments.plugin_root)
                elif arguments.operation == 'activate-plugin' and arguments.staged_plugin is not None and arguments.plugin_root is not None:
                    report = activate_plugin(arguments.installation, arguments.staged_plugin, arguments.plugin_root, force=arguments.force)
                elif arguments.operation == 'seal-upgrade':
                    report = seal_upgrade(arguments.installation)
                elif arguments.operation in ('detach', 'detach-legacy-pair'):
                    report = detach(arguments.installation, False, control_only=arguments.operation == 'detach')
                else:
                    raise Rejected(Failure.RECEIPT)
            finally:
                os.close(descriptor)
    except Rejected as rejected:
        report = Report(Status.BLOCKED, [rejected.failure], '')
    except (OSError, ValueError, TypeError, KeyError):
        report = Report(Status.BLOCKED, [Failure.FILESYSTEM], '')
    print(encode(report))
    return 0 if report.status in (Status.PREPARED, Status.PLANNED, Status.ACTIVE, Status.SEALED, Status.CLEAN) else 1


if __name__ == '__main__':
    raise SystemExit(main())
