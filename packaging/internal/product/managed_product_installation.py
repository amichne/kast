"""Complete-product publication and prior-Control retirement through the replacement journal."""

from __future__ import annotations

import errno
import hashlib
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import uuid
import zipfile
from collections.abc import Iterator
from contextlib import ExitStack
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Literal

import immutable_store
import managed_installation as install
import managed_product as product
import managed_release as release
import managed_update_contract as contract
import managed_update_journal as journal
import product_state_removal
import runtime_base
from composition_platform import HostPlatform
from managed_headless import AssemblyRecord
from private_interpreter import PrivateInterpreter


@dataclass(frozen=True)
class PayloadFile:
    path: str
    sha256: str
    mode: int


@dataclass(frozen=True)
class InstallationManifest:
    installationRoot: str
    stateRoot: str
    configuration: str
    workspaceRegistry: str
    codexHome: str
    payloadFiles: tuple[PayloadFile, ...]
    controlSha256: str
    payloadIdentity: str
    semanticVersion: str
    schemaVersion: int = 3
    externalAnchors: tuple[str, ...] = ()


@dataclass(frozen=True)
class ProductManagement:
    installationRoot: str
    executable: str
    executableSha256: str
    product: product.ProductReference
    schemaVersion: int = 3


@dataclass(frozen=True)
class ProductInstalled:
    product: product.ProductReference
    executable: str
    type: Literal["PRODUCT_INSTALLED"] = "PRODUCT_INSTALLED"
    hostState: Literal["NOT_STARTED"] = "NOT_STARTED"
    notices: tuple[journal.ManagedNotice, ...] = ()


@dataclass(frozen=True)
class ProductPlanned:
    product: product.ProductReference
    type: Literal["PRODUCT_PLANNED"] = "PRODUCT_PLANNED"


def private_directory(path: Path) -> None:
    if path.resolve() != path:
        raise ValueError("linked installation ancestor")
    missing: list[Path] = []
    parent = path
    while not parent.exists():
        missing.append(parent)
        parent = parent.parent
    for directory in reversed(missing):
        directory.mkdir(mode=0o700)
    if not path.is_dir() or path.stat().st_uid != os.getuid():
        raise ValueError("unowned installation directory")


def runtime_metadata(
    reference: product.ProductReference, manifest: product.RuntimeManifest
) -> bytes:
    prefix = "host" if manifest.platform.is_linux else "host/Kast.app/Contents"
    return install.encoded(
        AssemblyRecord(
            1,
            str(Path(reference.runtimeRoot) / prefix),
            manifest.distributionSha256,
            manifest.bootstrapSha256,
            manifest.platformBuild,
            manifest.platformBuild + "-IJ",
        )
    )


def read_runtime(reference: product.ProductReference) -> None:
    root = Path(reference.runtimeRoot)
    _ = immutable_store.reusable(root, "RUNTIME", reference.runtimeArchiveSha256)
    raw = install.read_record(root / "manifest.json", product.MAXIMUM_MANIFEST_BYTES)
    if hashlib.sha256(raw).hexdigest() != reference.runtimeManifestSha256:
        raise ValueError("runtime manifest changed")
    manifest = product.runtime_manifest(raw, reference.version, HostPlatform(reference.target))
    receipt = product.RuntimeReceipt(
        reference.runtimeArchiveSha256, reference.runtimeManifestSha256
    )
    if install.read_record(root / "runtime.json") != install.encoded(receipt):
        raise ValueError("runtime receipt changed")
    prefix = "host" if manifest.platform.is_linux else "host/Kast.app/Contents"
    assembly = root / prefix / "kast-headless.json"
    if install.read_record(assembly) != runtime_metadata(reference, manifest):
        raise ValueError("runtime assembly changed")
    product.verify_runtime(root, manifest, assembly=prefix + "/kast-headless.json")


def copy_runtime_file(source: str, target: str) -> object:
    # copytree aggregates file failures into a shutil.Error without errno.
    # Translate storage exhaustion first, then retain every other copy failure.
    try:
        return shutil.copy2(source, target)
    except OSError as problem:
        if problem.errno != errno.ENOSPC:
            raise
        raise install.StageRejected(
            contract.Rejected(contract.Stage.PREPARATION, contract.Failure.STORAGE_EXHAUSTED)
        ) from None


def prepare_runtime(
    admitted: product.AdmittedProduct,
    reference: product.ProductReference,
    policy: immutable_store.RepairPolicy,
) -> tuple[immutable_store.DamagedEntry, ...]:
    destination = Path(reference.runtimeRoot)
    archive = admitted.runtime.parent / admitted.target.runtime.name
    if release.file_digest(archive) != reference.runtimeArchiveSha256:
        raise ValueError("runtime archive changed")
    with zipfile.ZipFile(archive) as incoming:
        if incoming.read("manifest.json") != (admitted.runtime / "manifest.json").read_bytes():
            raise ValueError("runtime manifest changed after admission")
    # The admitted extraction is read only to copy it. Publication verifies the copy's exact
    # inventory and every digest, so a changed source file still rejects before the runtime
    # becomes digest-addressed, and a reused runtime never reads the extraction at all.
    damaged: tuple[immutable_store.DamagedEntry, ...] = ()
    if admitted.base is not None:
        damaged = prepare_base(
            admitted, destination.parent.parent.with_name("kast-runtime-bases"), policy
        )
    private_directory(destination.parent)
    immutable_store.recover_publications(
        destination.parent, "RUNTIME", source=archive, target=admitted.target.target
    )
    if os.path.lexists(destination):
        pending = immutable_store.deferred_reuse(
            destination, "RUNTIME", reference.runtimeArchiveSha256, policy, source=archive
        )
        if not pending:
            read_runtime(reference)
        return damaged + pending
    # Only a fully populated private directory may become the digest-addressed runtime.
    with immutable_store.publication(destination.parent, admitted, "RUNTIME") as staged:
        _ = shutil.copytree(
            admitted.runtime, staged, symlinks=True, copy_function=copy_runtime_file
        )
        product.verify_runtime(staged, admitted.manifest)
        prefix = "host" if admitted.manifest.platform.is_linux else "host/Kast.app/Contents"
        assembly = staged / prefix / "kast-headless.json"
        with assembly.open("xb") as output:
            _ = output.write(runtime_metadata(reference, admitted.manifest))
        with (staged / "runtime.json").open("xb") as output:
            _ = output.write(
                install.encoded(
                    product.RuntimeReceipt(
                        reference.runtimeArchiveSha256,
                        reference.runtimeManifestSha256,
                    )
                )
            )
        immutable_store.publish(staged, admitted, "RUNTIME")
        for path in sorted(staged.rglob("*"), reverse=True):
            if not path.is_symlink():
                path.chmod(0o555 if path.is_dir() or path.stat().st_mode & 0o111 else 0o444)
                install.RECOVERY.sync(path)
        # Match the native runtime store: the owned publication root remains private
        # and removable; its payload files and descendant directories are sealed.
        staged.chmod(0o700)
        install.RECOVERY.sync(staged)
        _ = immutable_store.admit(
            staged, "RUNTIME", reference.runtimeArchiveSha256, destination=destination
        )
        # Never replace a concurrent digest destination, even an empty directory.
        install.rename_file_no_replace(staged, destination)
        install.RECOVERY.sync(destination.parent)
    read_runtime(reference)
    return damaged


def prepare_base(
    admitted: product.AdmittedProduct, parent: Path, policy: immutable_store.RepairPolicy
) -> tuple[immutable_store.DamagedEntry, ...]:
    assert admitted.base is not None
    source, metadata, composition = admitted.base
    private_directory(parent)
    destination = parent / composition.base.sha256
    immutable_store.recover_publications(
        parent,
        "BASE",
        source=admitted.runtime.parent / composition.base.name,
        target=admitted.target.target,
    )
    if os.path.lexists(destination):
        pending = immutable_store.deferred_reuse(
            destination,
            "BASE",
            composition.base.sha256,
            policy,
            source=admitted.runtime.parent / composition.base.name,
        )
        retained = immutable_store.original_entry(
            destination,
            "BASE",
            composition.base.sha256,
            source=admitted.runtime.parent / composition.base.name,
        )
        if retained.manifest != (source / runtime_base.BASE_MANIFEST).read_bytes():
            raise ValueError("base changed after admission")
        return pending
    with immutable_store.publication(parent, admitted, "BASE") as staged:
        verified = runtime_base.extract_base(
            admitted.runtime.parent / composition.base.name, composition, staged
        )
        if verified != metadata:
            raise ValueError("base changed after admission")
        immutable_store.publish(staged, admitted, "BASE")
        for path in sorted(staged.rglob("*"), reverse=True):
            if not path.is_symlink():
                path.chmod(0o555 if path.is_dir() or path.stat().st_mode & 0o111 else 0o444)
                install.RECOVERY.sync(path)
        staged.chmod(0o700)
        install.RECOVERY.sync(staged)
        _ = immutable_store.admit(staged, "BASE", composition.base.sha256)
        install.rename_file_no_replace(staged, destination)
        install.RECOVERY.sync(parent)
    _ = immutable_store.admit(destination, "BASE", composition.base.sha256)
    return ()


def reference_at(control: Path, root: Path) -> product.ProductReference:
    raw = install.read_record(control / "share/kast/product-selection.json")
    value = release.document(raw)
    reference = install.RECOVERY.decode(product.ProductReference, value)
    if not isinstance(reference, product.ProductReference):
        raise ValueError("product reference")
    record = install.read_record(control / "share/kast/build-record.json")
    parsed = release.decode_installed_product_record(record)
    if isinstance(parsed, release.Failure):
        raise ValueError("product record")
    selected = release.ProductSelection(
        reference.releaseTag,
        parsed,
        reference.buildRecordSha256,
        reference.policy,
        reference.publicVersion,
        parsed.sourceRevision if reference.releaseTag.startswith("developer-") else None,
    )
    if isinstance(release.decode_selection(install.encoded(selected)), release.Rejected):
        raise ValueError("product selection")
    target = next((item for item in parsed.targets if item.target == reference.target), None)
    if (
        target is None
        or reference.schemaVersion != 1
        or hashlib.sha256(record).hexdigest() != reference.buildRecordSha256
        or (reference.version, reference.sourceRevision, reference.sourceTree)
        != (parsed.version, parsed.sourceRevision, parsed.sourceTree)
        or (reference.bundleSha256, reference.runtimeArchiveSha256, reference.qualificationSha256)
        != (target.bundle.sha256, target.runtime.sha256, target.qualification.receipt.sha256)
        or reference.runtimeRoot
        != str(root.parent / "kast-workspaces/runtimes" / target.runtime.sha256)
    ):
        raise ValueError("product reference binding")
    # The reference proves recorded identity, not present runtime bytes. A prior Control
    # can retire an empty workspace store even when its runtime needs repair.
    return reference


def publish_runtime(admitted: product.AdmittedProduct, reference: product.ProductReference) -> None:
    _ = prepare_runtime(admitted, reference, immutable_store.RepairPolicy.REJECT)


def publish_base(admitted: product.AdmittedProduct, parent: Path) -> None:
    _ = prepare_base(admitted, parent, immutable_store.RepairPolicy.REJECT)


INSTALLATION_ENTRIES = frozenset({"bin", "lib", "share", "config", "state", "installation.json"})


def verify_layout(control: Path) -> None:
    """A Control payload holds only its own top-level entries."""
    if unowned := {path.name for path in control.iterdir()} - INSTALLATION_ENTRIES:
        raise install.reject_state(
            contract.StateComponent.INSTALLATION_LAYOUT,
            contract.Failure.UNOWNED_STATE_ENTRY,
            unowned,
        )


def admit_prior(root: Path) -> journal.NoPrevious | journal.PhysicalPrevious:
    active = root / "installation"
    if not os.path.lexists(active):
        if os.path.lexists(root / "management.json"):
            raise ValueError("management without installation")
        require_empty_workspaces(root)
        return journal.NoPrevious("NONE")
    _ = install.LIFECYCLE.Installation.admit(str(active))
    _ = reference_at(active, root)
    # A legacy runtime/state requires its lifecycle owner's retirement proof, not a guess.
    verify_layout(active)
    verify_replacement_state(active, live_coordinator=True)
    slot = root / "recovery/replacement"
    return journal.PhysicalPrevious(
        "PHYSICAL",
        str(active),
        journal.Identity.observe(active),
        str(slot / "payload"),
        str(slot / "recovery"),
    )


def workspace_overlays(configuration: Path) -> Iterator[tuple[Path, bytes | None]]:
    """Admit only Kast's canonical per-root directory and its bounded literal environment file."""
    workspaces = configuration / "workspaces"
    if not os.path.lexists(workspaces):
        return
    immutable_store.physical(workspaces)
    component = contract.StateComponent.WORKSPACE_CONFIGURATION
    children = list(workspaces.iterdir())
    if unknown := [
        "config/workspaces/" + workspace.name
        for workspace in children
        if re.fullmatch(r"[0-9a-f]{64}", workspace.name) is None
    ]:
        raise install.reject_state(component, contract.Failure.UNOWNED_STATE_ENTRY, unknown)
    for workspace in children:
        immutable_store.physical(workspace)
        names = {entry.name for entry in workspace.iterdir()}
        if unowned := names - {"environment"}:
            raise install.reject_state(
                component,
                contract.Failure.UNOWNED_STATE_ENTRY,
                (f"config/workspaces/{workspace.name}/{name}" for name in unowned),
            )
        # A failed first save or interrupted removal can leave the owned directory empty.
        yield workspace, install.read_record(workspace / "environment", 65_536) if names else None


def verify_client_configuration(control: Path, *, partial: bool = False) -> set[Path]:
    configuration = control / "config"
    directories = {Path("config")}
    if not os.path.lexists(configuration):
        return directories
    immutable_store.physical(configuration)
    entries = {path.name for path in configuration.iterdir()}
    component = contract.StateComponent.CLIENT_CONFIGURATION
    if unowned := entries - {"environment", "workspaces"}:
        raise install.reject_state(
            component, contract.Failure.UNOWNED_STATE_ENTRY, ("config/" + name for name in unowned)
        )
    if not partial and "environment" not in entries:
        raise install.reject_state(
            component, contract.Failure.MISSING_STATE_ENTRY, ("config/environment",)
        )
    if "environment" in entries:
        _ = install.read_record(configuration / "environment", 262_144)
    if "workspaces" in entries:
        directories.add(Path("config/workspaces"))
        directories.update(
            workspace.relative_to(control) for workspace, _ in workspace_overlays(configuration)
        )
    return directories


# Until the prior Control settles retirement, its semantic coordinator may still own state.
PRE_RETIREMENT_PHASES = frozenset({journal.ManagedPhase.PREPARED, journal.ManagedPhase.FENCED})
SEMANTIC_PROFILE = re.compile(r"[0-9a-f]{16}")
MAXIMUM_SEMANTIC_PROFILES = 16
SEMANTIC_PROFILE_FILES = frozenset(
    {
        "semantic-service.log",
        "semantic-service.plist",
        "service-start.lock",
        "service-readiness.json",
    }
)


def admit_live_semantic_broker(broker: Path) -> None:
    """Admit only the files a semantic coordinator publishes; its retirement removes them."""
    component = contract.StateComponent.SEMANTIC_COORDINATOR_STATE
    immutable_store.physical(broker)
    profiles = list(broker.iterdir())
    if len(profiles) > MAXIMUM_SEMANTIC_PROFILES:
        raise install.reject_state(
            component, contract.Failure.STATE_ENTRY_LIMIT_EXCEEDED, ("state/broker",)
        )
    unknown = [
        "state/broker/" + profile.name
        for profile in profiles
        if not SEMANTIC_PROFILE.fullmatch(profile.name)
    ]
    if unknown:
        raise install.reject_state(component, contract.Failure.UNOWNED_STATE_ENTRY, unknown)
    for profile in profiles:
        immutable_store.physical(profile)
        unowned: list[str] = []
        for entry in profile.iterdir():
            observed = entry.lstat()
            if (
                entry.name not in SEMANTIC_PROFILE_FILES
                or not stat.S_ISREG(observed.st_mode)
                or observed.st_uid != os.getuid()
            ):
                unowned.append(f"state/broker/{profile.name}/{entry.name}")
        if unowned:
            raise install.reject_state(component, contract.Failure.UNOWNED_STATE_ENTRY, unowned)


def verify_replacement_state(control: Path, *, live_coordinator: bool = False) -> None:
    """Before retirement a running coordinator may own state; afterwards none may remain."""
    _ = verify_client_configuration(control)
    _ = product_state_removal.empty_invocation_directories(
        control, live_coordinator=live_coordinator
    )
    state = control / "state"
    if not os.path.lexists(state):
        return
    admitted = {"run", "broker"} if live_coordinator else {"run"}
    if unowned := {child.name for child in state.iterdir()} - admitted:
        # After retirement a coordinator's state/broker is no longer admitted, and names itself.
        raise install.reject_state(
            contract.StateComponent.INSTALLATION_STATE,
            contract.Failure.UNOWNED_STATE_ENTRY,
            ("state/" + name for name in unowned),
        )
    if live_coordinator and os.path.lexists(state / "broker"):
        admit_live_semantic_broker(state / "broker")


def materialize_client_entries(stage: Path, active: Path) -> None:
    """Complete the installed facade contract before recording immutable payload bytes."""
    configuration = stage / "config/environment"
    configuration.parent.mkdir(mode=0o700)
    prior = active / "config/environment"
    content = (
        install.read_record(prior, 262_144)
        if os.path.lexists(prior)
        else b"# Kast runtime configuration. Values are literal; shell syntax is not evaluated.\n"
    )
    with configuration.open("xb") as output:
        _ = output.write(content)
    configuration.chmod(0o600)
    for workspace, overlay in workspace_overlays(prior.parent):
        if overlay is None:
            continue
        directory = configuration.parent / "workspaces" / workspace.name
        private_directory(directory)
        target = directory / "environment"
        with target.open("xb") as output:
            _ = output.write(overlay)
        target.chmod(0o600)
    # These are host-interface adapters, not a second semantic dispatch owner.
    # Raw launchers retain their internal entrypoint and native-image arguments.
    for executable in ("kast", "kast-query", "kast-mcp", "kast-codex"):
        launcher = stage / "bin" / (executable + "-complete")
        with launcher.open("x") as output:
            _ = output.write(
                "#!/bin/sh\nset -eu\n"
                '[ ! -L "$0" ] || exit 1\n'
                'script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)\n'
                'installation_root=$(CDPATH= cd -- "$script_dir/.." && pwd -P)\n'
                'if [ -z "${KAST_CONFIGURATION_FILE+x}" ]; then\n'
                '  export KAST_CONFIGURATION_FILE="$installation_root/config/environment"\n'
                "fi\nunset KAST_SESSION_ROOT\n"
                f'exec "$script_dir/{executable}" "$@"\n'
            )
        launcher.chmod(0o755)


def require_empty_workspaces(root: Path) -> None:
    store = root.parent / "kast-workspaces"
    if not os.path.lexists(store):
        return
    if store.resolve() != store or not store.is_dir() or store.stat().st_uid != os.getuid():
        raise ValueError("workspace store ownership")
    # Runtime bytes alone need no process retirement. Unknown or interrupted workspace
    # entries do; never infer that a non-admitted entry has no live Host.
    component = contract.StateComponent.WORKSPACE_STORE
    if unowned := {entry.name for entry in store.iterdir()} - {"runtimes", "containers"}:
        raise install.reject_state(component, contract.Failure.UNOWNED_STATE_ENTRY, unowned)
    containers = store / "containers"
    if not os.path.lexists(containers):
        return
    if (
        containers.resolve() != containers
        or not containers.is_dir()
        or containers.stat().st_uid != os.getuid()
    ):
        raise ValueError("workspace container ownership")
    if live := ["containers/" + entry.name for entry in containers.iterdir()]:
        # Workspace retirement requires the prior qualified Control.
        raise install.reject_state(component, contract.Failure.UNRETIRED_WORKSPACE_ENTRY, live)


def read_management(raw: bytes, root: Path, command: Path, prior: Path) -> ProductManagement:
    """Admit the prior schema 3 receipt with exactly its recorded fields."""
    value = release.document(raw)
    reference = reference_at(prior, root)
    if not release.mapping(value) or value.get("schemaVersion") != 3:
        raise ValueError("management schema")
    required = {"schemaVersion", "installationRoot", "executable", "executableSha256", "product"}
    if set(value) != required:
        raise ValueError("management fields")
    if value["product"] != release.document(install.encoded(reference)):
        raise ValueError("management product identity")
    digest = release.file_digest(prior / "share/kast/libexec/kast-management")
    if (value.get("installationRoot"), value.get("executable"), value.get("executableSha256")) != (
        str(root),
        str(command),
        digest,
    ):
        raise ValueError("management installation identity")
    return ProductManagement(str(root), str(command), digest, reference)


def activate(
    root: Path,
    home: Path,
    admitted: product.AdmittedProduct,
    dry_run: bool = False,
    interpreter: PrivateInterpreter | None = None,
    runner: install.ChildRunner | None = None,
) -> ProductInstalled | ProductPlanned | contract.Rejected | contract.RecoveryRequired:
    reference = admitted.reference(root)
    if dry_run:
        return ProductPlanned(reference)
    try:
        with (
            install.management_lock(root),
            install.RECOVERY.managed_control_lock(root),
            ExitStack() as preparation,
        ):
            install.emit(contract.Stage.PREPARATION, "STARTED")
            if os.path.lexists(root / "recovery/replacement") or os.path.lexists(
                root / "shutdown.json"
            ):
                raise ValueError("pending replacement")
            previous = admit_prior(root)
            command = home / ".local/bin/kast"
            if isinstance(previous, journal.NoPrevious) and os.path.lexists(command):
                raise ValueError("foreign launcher")
            if (
                release.file_digest(admitted.components.control_archive)
                != admitted.components.control_digest
            ):
                raise ValueError("Control archive changed")
            damaged = prepare_runtime(admitted, reference, immutable_store.RepairPolicy.PREPARE)
            token = str(uuid.uuid4())
            immutable_store.physical(root.parent)
            stage = root.parent / (".kast-install-" + token)
            stage.mkdir(mode=0o700)
            _ = preparation.callback(
                clean_control_stage, root, stage, journal.Identity.observe(stage)
            )
            if stage.stat().st_dev != root.stat().st_dev:
                raise ValueError("Control staging crosses filesystem")
            extracted = stage / "payload"
            if isinstance(
                release.extract_control(admitted.components.control_archive, extracted),
                release.Failure,
            ):
                raise ValueError("Control restaging")
            for child in extracted.iterdir():
                install.rename_file_no_replace(child, stage / child.name)
            extracted.rmdir()
            metadata = stage / "share/kast"
            if interpreter is not None:
                if interpreter.pin.target != admitted.target.target:
                    raise ValueError("private interpreter target")
                interpreter.stage(metadata)
            with (metadata / "build-record.json").open("xb") as output:
                _ = output.write(admitted.record)
            with (metadata / "product-selection.json").open("xb") as output:
                _ = output.write(install.encoded(reference))
            active = root / "installation"
            materialize_client_entries(stage, active)
            stage_control_archive(
                stage,
                Path(reference.runtimeRoot),
                admitted.target.target,
                runner if runner is not None else install.run_child,
            )
            files = tuple(
                PayloadFile(
                    path.relative_to(stage).as_posix(),
                    "sha256:" + release.file_digest(path),
                    stat.S_IMODE(path.stat().st_mode),
                )
                for path in sorted(stage.rglob("*"))
                if path.is_file() and path.relative_to(stage).parts[0] in ("bin", "lib", "share")
            )
            manifest = InstallationManifest(
                str(active),
                str(active / "state"),
                str(active / "config/environment"),
                str(active / "config/workspaces.json"),
                str(home / ".codex"),
                files,
                "sha256:" + admitted.components.control_digest,
                "sha256:" + reference.buildRecordSha256,
                reference.version,
            )
            install.RECOVERY.save(stage / "installation.json", manifest)
            slot = root / "recovery/replacement"
            private_directory(slot.parent)
            private_directory(command.parent)
            # Publish the existing journal only after every receipt/preimage is durable.
            # A failed preparation cannot leave an unresumable canonical journal slot.
            with tempfile.TemporaryDirectory(prefix=".kast-prepare-", dir=root.parent) as temporary:
                prepared = Path(temporary)
                preimage = prepared_preimage(
                    root / "management.json", prepared, slot, "management-preimage"
                )
                executable = prepared_preimage(command, prepared, slot, "command-preimage")
                # Foreign launchers and registrations are not silently overwritten.
                if isinstance(previous, journal.NoPrevious) and isinstance(
                    executable, journal.ManagedFilePresent
                ):
                    raise ValueError("foreign launcher")
                if isinstance(previous, journal.PhysicalPrevious):
                    old = read_management(
                        install.read_record(root / "management.json"), root, command, active
                    )
                    if not install.file_matches(command, old.executableSha256, 0o755):
                        raise ValueError("previous product management")
                immutable_store.prepare_resets(prepared, admitted, damaged, token)
                record = journal.Replacement(
                    1,
                    journal.ReplacementStage.PREPARED,
                    str(active),
                    journal.Identity.observe(stage),
                    previous,
                    journal.ManagedReservation(
                        token,
                        str(stage),
                        admitted.components.control_digest,
                        reference.version,
                        release.file_digest(stage / "installation.json"),
                    ),
                    product=journal.ProductReplacement(
                        preimage,
                        executable,
                        release.file_digest(metadata / "product-selection.json"),
                        release.file_digest(active / "installation.json")
                        if isinstance(previous, journal.PhysicalPrevious)
                        else None,
                    ),
                )
                for path in sorted(stage.rglob("*"), reverse=True):
                    install.RECOVERY.sync(path)
                install.RECOVERY.sync(stage)
                install.emit(contract.Stage.JOURNAL, "STARTED")
                install.RECOVERY.save(prepared / "receipt.json", record)
                install.rename_file_no_replace(prepared, slot)
                install.RECOVERY.sync(slot.parent)
                install.RECOVERY.sync(root.parent)
            return resume_locked(root, home, record, runner)
    except (Exception, KeyboardInterrupt) as problem:
        # install.rejection keeps each typed failure and reports anything else as a defect.
        return failed(root, problem)


@dataclass(frozen=True)
class ControlEntry:
    main_class: str
    class_path: tuple[str, ...]
    java_home: str
    archive: str
    training: tuple[str, ...]


def control_entry(raw: bytes, target: str) -> ControlEntry:
    """The catalog's JVM entry for this target; every path stays beneath its root."""
    document = release.document(raw)
    control = document.get("control") if release.mapping(document) else None
    if not release.mapping(control):
        raise ValueError("Control catalog")
    main_class, class_path = control.get("mainClass"), control.get("classPath")
    homes, archive, training = (
        control.get("javaHomes"),
        control.get("sharedArchive"),
        control.get("archiveTraining"),
    )
    java_home = homes.get(target) if release.mapping(homes) else None
    if (
        not isinstance(main_class, str)
        or not isinstance(java_home, str)
        or not isinstance(archive, str)
        or not archive.endswith(".jsa")
        or not release.sequence(class_path)
        or not release.sequence(training)
    ):
        raise ValueError("Control catalog")
    paths = [item for item in class_path if isinstance(item, str)]
    arguments = [item for item in training if isinstance(item, str)]
    if len(paths) != len(class_path) or not paths or len(arguments) != len(training):
        raise ValueError("Control catalog")
    for relative in (archive, java_home, *paths):
        if Path(relative).is_absolute() or ".." in Path(relative).parts:
            raise ValueError("Control catalog path")
    return ControlEntry(main_class, tuple(paths), java_home, archive, tuple(arguments))


CONTROL_ARCHIVE_TIMEOUT_SECONDS = 120.0
MAXIMUM_CATALOG_BYTES = 4 << 20


def stage_control_archive(
    stage: Path, runtime_root: Path, target: str, runner: install.ChildRunner
) -> None:
    """Create the JVM entry's class-data archive with the installed runtime, inside staging.

    The installation manifest then digests the archive with every other payload file. The
    archive only shortens JVM startup, so a failed training run is reported and leaves no
    archive; the Control executable starts without one. A Control payload without the catalog
    predates the JVM entry and needs no archive.
    """
    catalog_path = stage / "share/kast/frontend.json"
    if not catalog_path.is_file():
        return
    entry = control_entry(install.read_record(catalog_path, MAXIMUM_CATALOG_BYTES), target)
    if os.path.lexists(stage / entry.archive):
        raise ValueError("Control archive path")
    archive = stage / entry.archive
    command = [
        str(runtime_root / entry.java_home / "bin/java"),
        f"-XX:ArchiveClassesAtExit={archive}",
        "-cp",
        ":".join(str(stage / path) for path in entry.class_path),
        entry.main_class,
        *entry.training,
    ]
    install.emit(contract.Stage.CONTROL_ARCHIVE, "STARTED")
    reason: contract.Failure | None = None
    with tempfile.TemporaryDirectory(prefix="kast-control-archive-") as home:
        # Training reads no installation state; an empty HOME keeps it from reaching any.
        environment = {"HOME": home, "PATH": "/usr/bin:/bin", "LC_ALL": "C"}
        try:
            if runner(command, environment, CONTROL_ARCHIVE_TIMEOUT_SECONDS).returncode != 0:
                reason = contract.Failure.CHILD_REJECTED
        except subprocess.TimeoutExpired:
            reason = contract.Failure.CHILD_TIMEOUT
        except OSError:
            reason = contract.Failure.CHILD_REJECTED
    if reason is None and (archive.is_symlink() or not archive.is_file()):
        reason = contract.Failure.CHILD_REJECTED
    if reason is not None:
        if os.path.lexists(archive):
            archive.unlink()
        install.emit(contract.Stage.CONTROL_ARCHIVE, "REJECTED", reason)
        return
    archive.chmod(0o644)
    install.emit(contract.Stage.CONTROL_ARCHIVE, "COMPLETED")


def clean_control_stage(root: Path, stage: Path, witness: journal.Identity) -> None:
    # A published journal takes ownership, including an uncertain journal rename.
    # Before that transition only this call's fresh allocation may be removed.
    if os.path.lexists(root / "recovery/replacement") or not os.path.lexists(stage):
        return
    immutable_store.physical(stage)
    if journal.Identity.observe(stage) != witness:
        raise ValueError("Control staging directory replaced")
    install.LIFECYCLE.delete_tree(stage)
    install.RECOVERY.sync(stage.parent)


def prepared_preimage(
    path: Path, prepared: Path, slot: Path, name: str
) -> journal.ManagedFilePreimage:
    value = install.saved_file(path, prepared / name)
    if isinstance(value, journal.ManagedFilePresent):
        install.RECOVERY.sync(prepared / name)
        return replace(value, preimage=str(slot / name))
    return value


def failed(root: Path, problem: BaseException) -> contract.Rejected | contract.RecoveryRequired:
    failure = install.rejection(problem)
    if isinstance(problem, journal.Rejected) and failure.state is not None:
        # The recovery adapter cannot emit; its rejected tree entry is observed here, once.
        print(
            install.encoded(
                contract.StateRejection(
                    failure.reason, failure.state.component, failure.state.entries
                )
            ).decode(),
            end="",
            file=sys.stderr,
        )
    install.emit(failure.stage, "REJECTED", failure.reason)
    if os.path.lexists(root / "recovery/replacement"):
        return contract.RecoveryRequired(
            "product",
            failure,
            contract.Rejected(contract.Stage.RECOVERY, contract.Failure.RECOVERY_UNPROVEN),
        )
    return failure


def resume(
    root: Path, home: Path, runner: install.ChildRunner | None = None
) -> ProductInstalled | contract.Rejected | contract.RecoveryRequired:
    try:
        with install.management_lock(root), install.RECOVERY.managed_control_lock(root):
            record = install.journal_types_record(
                install.RECOVERY.load(
                    root / "recovery/replacement/receipt.json", journal.Replacement
                )
            )
            return resume_locked(root, home, record, runner)
    except (Exception, KeyboardInterrupt) as problem:
        return failed(root, problem)


def resume_locked(
    root: Path, home: Path, record: journal.Replacement, runner: install.ChildRunner | None = None
) -> ProductInstalled:
    selected, reservation = record.product, record.reservation
    slot, active = root / "recovery/replacement", root / "installation"
    if (
        selected is None
        or reservation is None
        or record.installation != str(active)
        or record.schemaVersion != 1
        or str(uuid.UUID(reservation.transaction)) != reservation.transaction
        or reservation.staged
        not in (
            str(root.parent / (".kast-install-" + reservation.transaction)),
            str(root / (".install-" + reservation.transaction)),
        )
        or selected.management.destination != str(root / "management.json")
        or selected.publicExecutable.destination != str(home / ".local/bin/kast")
        or selected.phase
        not in (
            journal.ManagedPhase.PREPARED,
            journal.ManagedPhase.FENCED,
            journal.ManagedPhase.SETTLED,
            journal.ManagedPhase.CONTROL_COMMITTED,
            journal.ManagedPhase.ACTIVATION_PROVEN,
        )
    ):
        raise ValueError("product journal identity")
    staged = Path(reservation.staged)
    candidates = [
        path
        for path in (staged, active)
        if path.is_dir()
        and not path.is_symlink()
        and journal.Identity.observe(path) == record.installationIdentity
    ]
    if len(candidates) != 1:
        raise ValueError("selected payload location")
    current = candidates[0]
    witness = install.RECOVERY.managed_payload(
        current, active, record.installationIdentity, reservation
    )
    reference = reference_at(current, root)
    resets = immutable_store.reset_preflight(root, current, reference, record)
    if not any(item.proof.kind == "RUNTIME" for item, _ in resets):
        read_runtime(reference)
    if (
        release.file_digest(current / "share/kast/product-selection.json")
        != selected.selectionSha256
    ):
        raise ValueError("journal selection")
    previous = record.previous
    if isinstance(previous, journal.PhysicalPrevious):
        live_coordinator = selected.phase in PRE_RETIREMENT_PHASES
        prior = install.RECOVERY.managed_previous(
            active, slot, previous, live_coordinator=live_coordinator
        )
        if prior is None:
            raise ValueError("previous payload")
        verify_previous(
            prior[0], selected.previousManifestSha256, live_coordinator=live_coordinator
        )
        preimage = selected.management
        if not isinstance(preimage, journal.ManagedFilePresent) or preimage.preimage != str(
            slot / "management-preimage"
        ):
            raise ValueError("management preimage")
        raw = install.read_record(Path(preimage.preimage))
        if hashlib.sha256(raw).hexdigest() != preimage.sha256:
            raise ValueError("management preimage changed")
        _ = read_management(
            raw, root, Path(selected.publicExecutable.destination), prior[0]
        )
    elif selected.previousManifestSha256 is not None or not isinstance(
        selected.management, journal.ManagedFileAbsent
    ):
        raise ValueError("first installation carries prior management")
    if selected.phase in (journal.ManagedPhase.PREPARED, journal.ManagedPhase.FENCED):
        if current != staged or (
            isinstance(previous, journal.PhysicalPrevious) and not active.is_dir()
        ):
            raise ValueError("payload moved before retirement proof")
        record = retire_prior(root, home, record, runner)
        selected = record.product
        assert selected is not None
    require_fence(root, selected)
    immutable_store.finish_resets(root, current, reference, record)
    read_runtime(reference)
    if isinstance(previous, journal.PhysicalPrevious):
        prior = install.RECOVERY.managed_previous(active, slot, previous)
        if prior is None:
            raise ValueError("previous payload")
        verify_previous(prior[0], selected.previousManifestSha256)
        if prior[0] == active:
            install.emit(contract.Stage.CONTROL_REPLACEMENT, "STARTED")
            install.RECOVERY.managed_move(active, slot / "payload", prior[3])
    if current == staged:
        install.RECOVERY.managed_move(staged, active, witness)
    if selected.phase != journal.ManagedPhase.ACTIVATION_PROVEN:
        selected = replace(selected, phase=journal.ManagedPhase.CONTROL_COMMITTED)
        record = replace(record, stage=journal.ReplacementStage.COMMITTED, product=selected)
        install.RECOVERY.save(slot / "receipt.json", record)
    command = Path(selected.publicExecutable.destination)
    native = active / "share/kast/libexec/kast-management"
    receipt = ProductManagement(
        str(root),
        str(command),
        release.file_digest(native),
        reference,
    )
    for preimage, data, mode, role in (
        (
            selected.publicExecutable,
            install.read_record(native, install.MAXIMUM_FILE_PREIMAGE_BYTES),
            0o755,
            "command",
        ),
        (selected.management, install.encoded(receipt), 0o600, "management"),
    ):
        destination = Path(preimage.destination)
        if not install.file_matches(destination, hashlib.sha256(data).hexdigest(), mode):
            install.replace_file(
                destination, data, mode, preimage, reservation.transaction + "-" + role
            )
    _ = install.LIFECYCLE.Installation.admit(str(active))
    read_runtime(reference_at(active, root))
    if isinstance(previous, journal.PhysicalPrevious):
        verify_previous(slot / "payload", selected.previousManifestSha256)
    selected = replace(selected, phase=journal.ManagedPhase.ACTIVATION_PROVEN)
    record = replace(record, stage=journal.ReplacementStage.FINALIZING, product=selected)
    install.RECOVERY.save(slot / "receipt.json", record)
    require_fence(root, selected)
    if os.path.lexists(root / "shutdown.json"):
        (root / "shutdown.json").unlink()
        install.RECOVERY.sync(root)
    # This owner never admits mutable legacy state, and never deletes runtime-store bytes.
    install.RECOVERY.close_managed_journal(slot, record, uuid.UUID(reservation.transaction))
    install.emit(contract.Stage.FINALIZATION, "COMPLETED")
    return ProductInstalled(reference, str(command))


def require_fence(root: Path, selected: journal.ProductReplacement) -> None:
    observed = install.RECOVERY.managed_shutdown_fence(root / "installation")
    if selected.fence is None or (
        observed != selected.fence
        and not (observed is None and selected.phase == journal.ManagedPhase.ACTIVATION_PROVEN)
    ):
        raise install.reject(contract.Stage.FENCE, contract.Failure.CONCURRENT_CHANGE)


def retire_prior(
    root: Path, home: Path, record: journal.Replacement, runner: install.ChildRunner | None
) -> journal.Replacement:
    selected = record.product
    assert selected is not None
    slot = root / "recovery/replacement/receipt.json"
    active = root / "installation"
    install.emit(contract.Stage.FENCE, "STARTED")
    if selected.phase == journal.ManagedPhase.PREPARED and not isinstance(
        record.previous, journal.PhysicalPrevious
    ):
        require_empty_workspaces(root)
        fence = root / "shutdown.json"
        if not os.path.lexists(fence):
            descriptor = os.open(fence, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
            with os.fdopen(descriptor, "wb") as output:
                _ = output.write(install.encoded(install.ShutdownRequest(str(active))))
                output.flush()
                os.fsync(output.fileno())
            install.RECOVERY.sync(root)
    if selected.phase == journal.ManagedPhase.PREPARED and os.path.lexists(root / "shutdown.json"):
        selected = replace(
            selected,
            phase=journal.ManagedPhase.FENCED,
            fence=install.RECOVERY.managed_shutdown_fence(active),
        )
        record = replace(record, product=selected)
        install.RECOVERY.save(slot, record)
    if selected.phase == journal.ManagedPhase.FENCED:
        require_fence(root, selected)
    install.emit(contract.Stage.REQUEST_RETIREMENT, "STARTED")
    if isinstance(record.previous, journal.PhysicalPrevious):
        # Re-admit exact old bytes before executing them. The callback owns all-workspace
        # leases and Host settlement; a shutdown request alone is never retirement proof.
        _ = install.LIFECYCLE.Installation.admit(str(active))
        _ = reference_at(active, root)
        verify_previous(active, selected.previousManifestSha256, live_coordinator=True)
        try:
            result = (runner if runner is not None else install.run_child)(
                [
                    str(active / "share/kast/libexec/kast-management"),
                    "--internal-install",
                    "managed-retire",
                ],
                dict(os.environ) | {"KAST_INSTALL_ROOT": str(root), "HOME": str(home)},
                300.0,
            )
        except subprocess.TimeoutExpired:
            raise install.reject(
                contract.Stage.REQUEST_RETIREMENT, contract.Failure.CHILD_TIMEOUT
            ) from None
        except OSError:
            raise install.reject(
                contract.Stage.REQUEST_RETIREMENT, contract.Failure.CHILD_REJECTED
            ) from None
        finally:
            # Prior Control acquires all workspace leases before creating the fence.
            # Retain its exact identity even after failure/interruption, without treating
            # that observation as proof of successful retirement.
            if selected.phase == journal.ManagedPhase.PREPARED:
                observed = install.RECOVERY.managed_shutdown_fence(active)
                if observed is not None:
                    selected = replace(selected, phase=journal.ManagedPhase.FENCED, fence=observed)
                    record = replace(record, product=selected)
                    install.RECOVERY.save(slot, record)
        if result.returncode != 0:
            raise install.reject(contract.Stage.REQUEST_RETIREMENT, contract.Failure.CHILD_REJECTED)
    else:
        require_empty_workspaces(root)
    require_fence(root, selected)
    install.emit(contract.Stage.REQUEST_RETIREMENT, "COMPLETED")
    record = replace(record, product=replace(selected, phase=journal.ManagedPhase.SETTLED))
    install.RECOVERY.save(slot, record)
    return record


def verify_previous(
    path: Path, manifest_sha256: str | None, *, live_coordinator: bool = False
) -> None:
    if (
        manifest_sha256 is None
        or release.file_digest(path / "installation.json") != manifest_sha256
    ):
        raise ValueError("previous payload changed")
    verify_layout(path)
    verify_replacement_state(path, live_coordinator=live_coordinator)
    install.LIFECYCLE.verify_payload(
        path, release.document(install.read_record(path / "installation.json", 67_108_864))
    )
