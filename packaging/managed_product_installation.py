"""Complete-product publication and prior-Control retirement through the replacement journal."""

from __future__ import annotations

import hashlib
import os
import shutil
import stat
import subprocess
import tempfile
import uuid
import zipfile
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Literal

import immutable_store
import managed_installation as install
import managed_product as product
import managed_release as release
import managed_update_contract as contract
import managed_update_journal as journal
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
    registrations: tuple[install.Registration, ...] = ()
    guidance: tuple[install.Guidance, ...] = ()
    providers: tuple[install.ProviderRegistration, ...] = ()


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
    _ = immutable_store.admit(root, "RUNTIME", reference.runtimeArchiveSha256)


def publish_runtime(admitted: product.AdmittedProduct, reference: product.ProductReference) -> None:
    destination = Path(reference.runtimeRoot)
    archive = admitted.runtime.parent / admitted.target.runtime.name
    if release.file_digest(archive) != reference.runtimeArchiveSha256:
        raise ValueError("runtime archive changed")
    with zipfile.ZipFile(archive) as incoming:
        if incoming.read("manifest.json") != (admitted.runtime / "manifest.json").read_bytes():
            raise ValueError("runtime manifest changed after admission")
    product.verify_runtime(admitted.runtime, admitted.manifest)
    if admitted.base is not None:
        publish_base(admitted, destination.parent.parent.with_name("kast-runtime-bases"))
    private_directory(destination.parent)
    if os.path.lexists(destination):
        read_runtime(reference)
        return
    # Only a fully populated private directory may become the digest-addressed runtime.
    with tempfile.TemporaryDirectory(prefix=".runtime-", dir=destination.parent) as temporary:
        staged = Path(temporary) / "payload"
        _ = shutil.copytree(admitted.runtime, staged, symlinks=True)
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


def publish_base(admitted: product.AdmittedProduct, parent: Path) -> None:
    assert admitted.base is not None
    source, metadata, composition = admitted.base
    private_directory(parent)
    destination = parent / composition.base.sha256
    if os.path.lexists(destination):
        retained = immutable_store.admit(destination, "BASE", composition.base.sha256)
        if retained.manifest != (source / runtime_base.BASE_MANIFEST).read_bytes():
            raise ValueError("base changed after admission")
        return
    with tempfile.TemporaryDirectory(prefix=".base-", dir=parent) as temporary:
        staged = Path(temporary) / "payload"
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


def reference_at(control: Path, root: Path) -> product.ProductReference:
    raw = install.read_record(control / "share/kast/product-selection.json")
    value = release.document(raw)
    reference = install.RECOVERY.decode(product.ProductReference, value)
    if not isinstance(reference, product.ProductReference):
        raise ValueError("product reference")
    record = install.read_record(control / "share/kast/build-record.json")
    parsed = release.decode_product_record(record)
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
    if {path.name for path in active.iterdir()} - {"bin", "lib", "share", "installation.json"}:
        raise ValueError("legacy or live installation state requires retirement")
    slot = root / "recovery/replacement"
    return journal.PhysicalPrevious(
        "PHYSICAL",
        str(active),
        journal.Identity.observe(active),
        str(slot / "payload"),
        str(slot / "recovery"),
    )


def require_empty_workspaces(root: Path) -> None:
    store = root.parent / "kast-workspaces"
    if not os.path.lexists(store):
        return
    if store.resolve() != store or not store.is_dir() or store.stat().st_uid != os.getuid():
        raise ValueError("workspace store ownership")
    # Runtime bytes alone need no process retirement. Unknown or interrupted workspace
    # entries do; never infer that a non-admitted entry has no live Host.
    if {entry.name for entry in store.iterdir()} - {"runtimes", "containers"}:
        raise ValueError("unrecognized workspace store")
    containers = store / "containers"
    if os.path.lexists(containers) and (
        containers.resolve() != containers
        or not containers.is_dir()
        or containers.stat().st_uid != os.getuid()
        or any(containers.iterdir())
    ):
        raise ValueError("workspace retirement requires prior qualified Control")


def read_management(raw: bytes, root: Path, command: Path, prior: Path) -> ProductManagement:
    value = release.document(raw)
    reference = reference_at(prior, root)
    if not release.mapping(value) or type(value.get("schemaVersion")) is not int:
        raise ValueError("management schema")
    if value["schemaVersion"] == 2:
        legacy = install.parse_management(raw)
        registrations, guidance = legacy.registrations, legacy.guidance
        providers = legacy.providers
        identity = legacy.installedBuild
        if identity is not None and (
            identity.version,
            identity.buildRevision,
            identity.sourceTree,
        ) != (reference.version, reference.sourceRevision, reference.sourceTree):
            raise ValueError("management build identity")
    elif value["schemaVersion"] == 3:
        required = {
            "schemaVersion",
            "installationRoot",
            "executable",
            "executableSha256",
            "product",
        }
        if not required <= set(value) or set(value) - required - {
            "registrations",
            "guidance",
            "providers",
        }:
            raise ValueError("management fields")
        if value["product"] != release.document(install.encoded(reference)):
            raise ValueError("management product identity")
        registrations, guidance = install.parse_provisioning(
            2, value.get("registrations", []), value.get("guidance", [])
        )
        providers = install.parse_providers(value.get("providers", []))
    else:
        raise ValueError("management schema")
    digest = release.file_digest(prior / "share/kast/libexec/kast-management")
    if (value.get("installationRoot"), value.get("executable"), value.get("executableSha256")) != (
        str(root),
        str(command),
        digest,
    ):
        raise ValueError("management installation identity")
    validate_provisioning(root, registrations, guidance)
    return ProductManagement(
        str(root),
        str(command),
        digest,
        reference,
        registrations=registrations,
        guidance=guidance,
        providers=providers,
    )


def normalized_absolute(raw: str) -> bool:
    return Path(raw).is_absolute() and os.path.normpath(raw) == raw and "\x00" not in raw


def validate_provisioning(
    root: Path,
    registrations: tuple[install.Registration, ...],
    guidance: tuple[install.Guidance, ...],
) -> None:
    if len({entry.connection for entry in registrations}) != len(registrations):
        raise ValueError("duplicate registration")
    for entry in registrations:
        if not release.DIGEST.fullmatch(entry.payloadSha256):
            raise ValueError("registration digest")
        if entry.directory is not None:
            if not normalized_absolute(entry.directory) or not normalized_absolute(
                entry.destination
            ):
                raise ValueError("registration directory")
            directory = Path(entry.directory)
            match entry.connection:
                case "CODEX_MCP":
                    expected = root / "installation/bin/kast-mcp-complete"
                case "CODEX_APP_SERVER":
                    expected = directory / "kast-codex"
                case "COPILOT":
                    expected = directory / "extensions/kast/extension.mjs"
                case "PI":
                    expected = directory / "extensions/kast.ts"
            if str(expected) != entry.destination:
                raise ValueError("registration destination")
    if len({entry.harness for entry in guidance}) != len(guidance):
        raise ValueError("duplicate guidance")
    for entry in guidance:
        if (
            not normalized_absolute(entry.destination)
            or not entry.files
            or (len({item.path for item in entry.files}) != len(entry.files))
        ):
            raise ValueError("guidance identity")
        for item in entry.files:
            if (
                not release.DIGEST.fullmatch(item.sha256)
                or "\\" in item.path
                or any(
                    ord(character) < 32 or 127 <= ord(character) <= 159 for character in item.path
                )
                or any(part in ("", ".", "..") for part in item.path.split("/"))
            ):
                raise ValueError("guidance file")


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
        with install.management_lock(root), install.RECOVERY.managed_control_lock(root):
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
            publish_runtime(admitted, reference)
            token = str(uuid.uuid4())
            stage = root / (".install-" + token)
            if isinstance(
                release.extract_control(admitted.components.control_archive, stage), release.Failure
            ):
                raise ValueError("Control restaging")
            metadata = stage / "share/kast"
            if interpreter is not None:
                if interpreter.pin.target != admitted.target.target:
                    raise ValueError("private interpreter target")
                interpreter.stage(metadata)
            with (metadata / "build-record.json").open("xb") as output:
                _ = output.write(admitted.record)
            with (metadata / "product-selection.json").open("xb") as output:
                _ = output.write(install.encoded(reference))
            files = tuple(
                PayloadFile(
                    path.relative_to(stage).as_posix(),
                    "sha256:" + release.file_digest(path),
                    stat.S_IMODE(path.stat().st_mode),
                )
                for path in sorted(stage.rglob("*"))
                if path.is_file()
            )
            active = root / "installation"
            manifest = InstallationManifest(
                str(active),
                str(active / "state"),
                str(active / "config/environment"),
                str(active / "config/workspaces.json"),
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
            with tempfile.TemporaryDirectory(prefix=".prepare-", dir=slot.parent) as temporary:
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
            return resume_locked(root, home, record, runner)
    except (
        OSError,
        ValueError,
        install.StageRejected,
        install.RECOVERY.Rejected,
        install.LIFECYCLE.Rejected,
        zipfile.BadZipFile,
        KeyboardInterrupt,
    ) as problem:
        return failed(root, problem)


def prepared_preimage(
    path: Path, prepared: Path, slot: Path, name: str
) -> journal.ManagedFilePreimage:
    value = install.saved_file(path, prepared / name)
    if isinstance(value, journal.ManagedFilePresent):
        install.RECOVERY.sync(prepared / name)
        return replace(value, preimage=str(slot / name))
    return value


def failed(root: Path, problem: BaseException) -> contract.Rejected | contract.RecoveryRequired:
    failure = (
        problem.failure
        if isinstance(problem, install.StageRejected)
        else contract.Rejected(
            contract.Stage.PREPARATION,
            contract.Failure.CANCELLED
            if isinstance(problem, KeyboardInterrupt)
            else contract.Failure.OWNERSHIP_UNPROVEN,
        )
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
    except (
        OSError,
        ValueError,
        install.StageRejected,
        install.RECOVERY.Rejected,
        install.LIFECYCLE.Rejected,
        KeyboardInterrupt,
    ) as problem:
        return failed(root, problem)


def resume_locked(
    root: Path, home: Path, record: journal.Replacement, runner: install.ChildRunner | None = None
) -> ProductInstalled:
    selected, reservation = record.product, record.reservation
    slot, active = root / "recovery/replacement", root / "installation"
    if (
        selected is None
        or reservation is None
        or record.composition is not None
        or record.installation != str(active)
        or record.schemaVersion != 1
        or str(uuid.UUID(reservation.transaction)) != reservation.transaction
        or reservation.staged != str(root / (".install-" + reservation.transaction))
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
    read_runtime(reference)
    if (
        release.file_digest(current / "share/kast/product-selection.json")
        != selected.selectionSha256
    ):
        raise ValueError("journal selection")
    previous = record.previous
    prior_receipt: ProductManagement | None = None
    if isinstance(previous, journal.PhysicalPrevious):
        prior = install.RECOVERY.managed_previous(active, slot, previous)
        if prior is None:
            raise ValueError("previous payload")
        verify_previous(prior[0], selected.previousManifestSha256)
        preimage = selected.management
        if not isinstance(preimage, journal.ManagedFilePresent) or preimage.preimage != str(
            slot / "management-preimage"
        ):
            raise ValueError("management preimage")
        raw = install.read_record(Path(preimage.preimage))
        if hashlib.sha256(raw).hexdigest() != preimage.sha256:
            raise ValueError("management preimage changed")
        prior_receipt = read_management(
            raw, root, Path(selected.publicExecutable.destination), prior[0]
        )
    elif (
        not isinstance(previous, journal.NoPrevious)
        or selected.previousManifestSha256 is not None
        or not isinstance(selected.management, journal.ManagedFileAbsent)
    ):
        raise ValueError("legacy migration unavailable")
    if selected.phase in (journal.ManagedPhase.PREPARED, journal.ManagedPhase.FENCED):
        if current != staged or (
            isinstance(previous, journal.PhysicalPrevious) and not active.is_dir()
        ):
            raise ValueError("payload moved before retirement proof")
        record = retire_prior(root, home, record, runner)
        selected = record.product
        assert selected is not None
    require_fence(root, selected)
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
        registrations=prior_receipt.registrations if prior_receipt else (),
        guidance=prior_receipt.guidance if prior_receipt else (),
        providers=prior_receipt.providers if prior_receipt else (),
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
        verify_previous(active, selected.previousManifestSha256)
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


def verify_previous(path: Path, manifest_sha256: str | None) -> None:
    if (
        manifest_sha256 is None
        or release.file_digest(path / "installation.json") != manifest_sha256
        or {entry.name for entry in path.iterdir()} - {"bin", "lib", "share", "installation.json"}
    ):
        raise ValueError("previous payload changed")
    install.LIFECYCLE.verify_payload(
        path, release.document(install.read_record(path / "installation.json", 67_108_864))
    )
