"""Retain executable bytes and original evidence in the existing recovery bundle."""

import os
import stat
from dataclasses import replace
from pathlib import Path

import immutable_store as store
import managed_installation as install
import managed_product as product
import managed_release as release
import managed_update_journal as journal
import product_mcp_removal


def location(root: Path) -> Path:
    return root / "recovery/installation/product-removal"


def stores(root: Path) -> tuple[tuple[str, Path], ...]:
    return (
        ("RUNTIME", root.parent / "kast-workspaces/runtimes"),
        ("BASE", root.parent / "kast-runtime-bases"),
    )


def capture(
    root: Path, reference: product.ProductReference
) -> tuple[list[store.ImmutableRemoval], journal.Identity | None, journal.Identity | None]:
    proofs: list[store.ImmutableRemoval] = []
    identities: list[journal.Identity | None] = []
    for kind, parent in stores(root):
        if not os.path.lexists(parent):
            identities.append(None)
            continue
        store.physical(parent)
        identities.append(journal.Identity.observe(parent))
        store.recover_publications(parent, kind, target=reference.target)
        for child in sorted(parent.iterdir()):
            if len(proofs) >= 1024:
                raise ValueError("immutable entry limit")
            archive_sha = store.sha(child.name)
            evidence = store.admit(child, kind, archive_sha)
            if evidence.provenance.target != reference.target:
                raise ValueError("immutable target mismatch")
            proofs.append(
                store.ImmutableRemoval(
                    kind,
                    archive_sha,
                    journal.Identity.observe(child),
                    evidence.provenance.recordSha256,
                    evidence.provenance.manifestSha256,
                    store.digest(install.read_record(child / "provenance/provenance.json")),
                )
            )
    return proofs, identities[0], identities[1]


def private(path: Path) -> None:
    if not os.path.lexists(path):
        private(path.parent)
        path.mkdir(mode=0o700)
    store.physical(path)
    if path.stat().st_mode & 0o077:
        raise ValueError("recovery directory permissions")


def copy_bytes(destination: Path, raw: bytes, mode: int) -> None:
    private(destination.parent)
    if os.path.lexists(destination):
        previous = install.read_record(destination, len(raw) + 1)
        if not raw.startswith(previous) or stat.S_IMODE(destination.stat().st_mode) != mode:
            raise ValueError("retained execution bytes changed")
        # An interrupted prefix remains bound to the unchanged original source.
        with destination.open("ab") as output:
            _ = output.write(raw[len(previous) :])
            output.flush()
            os.fsync(output.fileno())
    else:
        descriptor = os.open(
            destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode
        )
        with os.fdopen(descriptor, "wb") as output:
            os.fchmod(output.fileno(), mode)
            _ = output.write(raw)
            output.flush()
            os.fsync(output.fileno())
    install.RECOVERY.sync(destination.parent)


def execution(root: Path, proof: store.ProductRemoval) -> None:
    bundle = location(root)
    store.physical(bundle)
    if journal.Identity.observe(bundle) != proof.executionIdentity:
        raise ValueError("retained execution identity")
    expected = {"control"}
    if proof.immutableRemovals:
        expected.add("evidence")
    if proof.mcp is not None:
        expected.add("mcp-receipt.json")
    if {child.name for child in bundle.iterdir()} != expected:
        raise ValueError("foreign retained execution state")
    control = bundle / "control"
    if {child.name for child in control.iterdir()} - {"bin", "lib", "share", "installation.json"}:
        raise ValueError("foreign retained Control state")
    manifest = install.read_record(control / "installation.json", 67_108_864)
    if store.digest(manifest) != proof.installationManifestSha256:
        raise ValueError("retained installation inventory")
    document = install.LIFECYCLE.read_json(
        control / "installation.json", 67_108_864, install.LIFECYCLE.Failure.MANIFEST_REJECTED
    )
    install.LIFECYCLE.verify_payload(control, document)
    from managed_product_removal import payload_directories

    payload_directories(control, document)
    support = control / "share/kast"
    for required in ("install-candidate.sh", "private-python/bin/python3.12", "bootstrap_entry.py"):
        path = support / required
        if not path.is_file() or path.is_symlink():
            raise ValueError("retained private execution unavailable")
    if not os.access(support / "private-python/bin/python3.12", os.X_OK):
        raise ValueError("retained interpreter not executable")


def prepare(root: Path, path: Path) -> store.ProductRemoval:
    payload = root / "installation"
    raw = install.LIFECYCLE.read_json(
        path,
        install.LIFECYCLE.UNINSTALL_JOURNAL_MAXIMUM_BYTES,
        install.LIFECYCLE.Failure.RECOVERY_REJECTED,
    )
    selected = install.LIFECYCLE.ProductRemovalRoot(
        payload, install.LIFECYCLE.journal_identity(raw["controlIdentity"])
    )
    admitted = install.LIFECYCLE.admit_uninstall_journal(path, selected)
    record = admitted[0]
    bundle = location(root)
    proof = store.removal(raw["productRemoval"]) if "productRemoval" in raw else None
    if proof is None:
        installation = install.LIFECYCLE.Installation.admit(str(payload))
        proofs, runtime_identity, base_identity = capture(root, record.product)
        mcp = product_mcp_removal.capture(payload)
        if os.path.lexists(bundle):
            raise ValueError("foreign retained execution")
        # No payload is removed before this identity is durably reserved in the journal.
        private(bundle)
        proof = store.ProductRemoval(
            "PREPARING",
            journal.Identity.observe(bundle),
            store.digest(install.read_record(payload / "installation.json", 67_108_864)),
            proofs,
            runtime_identity,
            base_identity,
            mcp=mcp,
        )
        kind = (
            install.LIFECYCLE.RetainedWorkspaceProductUninstallJournal
            if "workspaceRemovals" in raw
            else install.LIFECYCLE.RetainedProductUninstallJournal
        )
        successor = kind(**vars(record), productRemoval=proof)
        install.LIFECYCLE.commit_control_retirement(
            path, installation, admitted, next_journal=successor
        )
        admitted = install.LIFECYCLE.admit_uninstall_journal(path, selected)
        record = admitted[0]
    if journal.Identity.observe(bundle) != proof.executionIdentity:
        raise ValueError("retained execution replaced")
    if (
        proof.phase == "PREPARED"
        and record.type != install.LIFECYCLE.UninstallPhase.CONTROL_RETIRED
    ):
        raise ValueError("retained execution phase")
    if proof.phase == "PREPARING":
        installation = install.LIFECYCLE.Installation.admit(str(payload))
        if product_mcp_removal.capture(payload) != proof.mcp:
            raise ValueError("MCP ownership changed")
        manifest = install.read_record(payload / "installation.json", 67_108_864)
        if store.digest(manifest) != proof.installationManifestSha256:
            raise ValueError("original payload changed")
        control = bundle / "control"
        private(control)
        for entry in sorted(payload.rglob("*")):
            # Recovery execution needs the immutable import tree, not saved owner
            # input or native invocation state. Kotlin admits this same inventory.
            if entry.relative_to(payload).parts[0] in {"state", "config"}:
                continue
            if entry.is_symlink():
                raise ValueError("linked original payload")
            if entry.is_file():
                copy_bytes(
                    control / entry.relative_to(payload),
                    install.read_record(entry, release.MAXIMUM_PAYLOAD_BYTES),
                    stat.S_IMODE(entry.stat().st_mode),
                )
        for item in proof.immutableRemovals:
            source = store.entry(root, item)
            if journal.Identity.observe(source) != item.identity:
                raise ValueError("immutable entry changed")
            original = store.admit(source, item.kind, item.archiveSha256)
            snapshot = store.snapshot_directory(bundle, item)
            copy_bytes(snapshot / "manifest.json", original.manifest, 0o600)
            for name in ("provenance.json", "build-record.json", "qualification.json"):
                copy_bytes(
                    snapshot / name,
                    install.read_record(source / "provenance" / name, 1048576),
                    0o600,
                )
        if proof.mcp is not None:
            copy_bytes(
                bundle / "mcp-receipt.json",
                install.read_record(payload / "state/mcp/receipt.json", 67_108_864),
                0o600,
            )
        execution(root, proof)
        _ = preflight(root, proof, partial=False)
        proof = replace(proof, phase="PREPARED")
        install.LIFECYCLE.commit_control_retirement(
            path,
            installation,
            admitted,
            next_journal=replace(
                record, productRemoval=proof, type=install.LIFECYCLE.UninstallPhase.CONTROL_RETIRED
            ),
        )
    execution(root, proof)
    from managed_product_installation import reference_at

    if reference_at(bundle / "control", root) != record.product:
        raise ValueError("retained original product changed")
    return proof


def preflight(
    root: Path, proof: store.ProductRemoval, *, partial: bool
) -> tuple[tuple[store.ImmutableRemoval, store.Evidence], ...]:
    bundle = location(root)
    if journal.Identity.observe(bundle) != proof.executionIdentity:
        raise ValueError("retained execution replaced")
    product_mcp_removal.verify(root, bundle, proof)
    evidence_root = bundle / "evidence"
    expected_files = {
        f"{item.kind}/{item.archiveSha256}/{name}"
        for item in proof.immutableRemovals
        for name in ("manifest.json", "build-record.json", "qualification.json", "provenance.json")
    }
    expected_directories = {
        parent.as_posix() for name in expected_files for parent in Path(name).parents
    }
    if os.path.lexists(evidence_root):
        store.physical(evidence_root)
        for child in evidence_root.rglob("*"):
            name = child.relative_to(evidence_root).as_posix()
            if child.is_symlink() or name not in expected_files | expected_directories:
                raise ValueError("foreign retained evidence")
    for (kind, parent), expected_identity in zip(
        stores(root), (proof.runtimeStoreIdentity, proof.baseStoreIdentity), strict=True
    ):
        if expected_identity is None:
            if os.path.lexists(parent):
                raise ValueError("unrecorded immutable store")
            continue
        store.physical(parent)
        if journal.Identity.observe(parent) != expected_identity:
            raise ValueError("immutable store replaced")
        allowed = {item.archiveSha256 for item in proof.immutableRemovals if item.kind == kind}
        if {child.name for child in parent.iterdir()} - allowed:
            raise ValueError("unrecorded immutable entry")
    result: list[tuple[store.ImmutableRemoval, store.Evidence]] = []
    for item in proof.immutableRemovals:
        snapshot = store.snapshot_directory(bundle, item)
        store.physical(snapshot)
        if {child.name for child in snapshot.iterdir()} != {
            "manifest.json",
            "build-record.json",
            "qualification.json",
            "provenance.json",
        }:
            raise ValueError("retained evidence inventory")
        evidence = store.evidence(snapshot)
        if (
            evidence.provenance.kind != item.kind
            or evidence.provenance.archiveSha256 != item.archiveSha256
            or evidence.provenance.recordSha256 != item.recordSha256
            or evidence.provenance.manifestSha256 != item.manifestSha256
            or store.digest(install.read_record(snapshot / "provenance.json"))
            != item.provenanceSha256
        ):
            raise ValueError("retained original evidence changed")
        source = store.entry(root, item)
        if os.path.lexists(source):
            if journal.Identity.observe(source) != item.identity:
                raise ValueError("immutable entry replaced")
            store.verify(source, evidence, partial=partial)
        elif not partial:
            raise ValueError("immutable entry disappeared")
        result.append((item, evidence))
    return tuple(result)
