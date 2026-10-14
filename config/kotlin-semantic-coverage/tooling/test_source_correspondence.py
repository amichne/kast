"""Producer-coordinate and byte-identity controls, independent of compiler execution."""

from __future__ import annotations

import csv
import hashlib
import io
import shutil
from pathlib import Path
from zipfile import ZipFile

import pytest

import inventory as subject

ROOT = Path(__file__).resolve().parents[1]
VISITOR = "org/jetbrains/kotlin/fir/visitors/FirVisitor.kt"
CLASS = "org/jetbrains/kotlin/fir/visitors/FirVisitor.class"


def archive(entries: tuple[tuple[str, bytes], ...]) -> bytes:
    stream = io.BytesIO()
    with ZipFile(stream, "w") as output:
        for name, payload in entries:
            output.writestr(name, payload)
    return stream.getvalue()


def scope() -> tuple[subject.Pin, tuple[subject.PublishedFirSource, ...]]:
    raw = subject.read_pin(ROOT)
    assert isinstance(raw, subject.Accepted)
    assert raw.value.published_fir is not None
    payload = b"package org.jetbrains.kotlin.fir.visitors\nabstract class FirVisitor\n"
    digest = hashlib.sha256(payload).hexdigest()
    blob = hashlib.sha1(
        b"blob " + str(len(payload)).encode() + b"\0" + payload, usedforsecurity=False
    ).hexdigest()
    row = subject.PublishedFirSource(
        source_path="compiler/fir/tree/gen/" + VISITOR,
        archive_entry=VISITOR,
        git_blob=blob,
        sha256=digest,
    )
    published = raw.value.published_fir.model_copy(update={"class_count": 1, "source_count": 1})
    pin = raw.value.model_copy(update={"published_fir": published, "visitor_sha256": digest})
    return pin, (row,)


def test_matching_source_and_binary_byte_rule() -> None:
    pin, mappings = scope()
    binary = archive(((CLASS, b"independent class payload"),))
    source = archive(
        ((VISITOR, b"package org.jetbrains.kotlin.fir.visitors\nabstract class FirVisitor\n"),)
    )
    assert subject.fir_archive_issues(binary, binary, source, mappings, pin) == ()


@pytest.mark.parametrize(
    "control",
    [
        "changed_class",
        "missing_class",
        "changed_source",
        "extra_source",
        "wrong_blob",
        "missing_mapping",
        "alias_mapping",
        "wrong_visitor",
    ],
)
def test_rejects_source_correspondence_controls(control: str) -> None:
    pin, mappings = scope()
    source_payload = b"package org.jetbrains.kotlin.fir.visitors\nabstract class FirVisitor\n"
    binary = archive(((CLASS, b"independent class payload"),))
    plugin = binary
    source = archive(((VISITOR, source_payload),))
    expected = subject.Code.SOURCE_POPULATION_MISMATCH
    if control == "changed_class":
        plugin = archive(((CLASS, b"different readable archive payload"),))
        expected = subject.Code.SOURCE_BINARY_MISMATCH
    elif control == "missing_class":
        plugin = archive((("unrelated.class", b"unrelated"),))
        expected = subject.Code.SOURCE_BINARY_MISMATCH
    elif control == "changed_source":
        source = archive(((VISITOR, source_payload + b"// changed\n"),))
        expected = subject.Code.SOURCE_CHANGED
    elif control == "extra_source":
        source = archive(((VISITOR, source_payload), ("Unknown.kt", b"class Unknown")))
    elif control == "wrong_blob":
        mappings = (mappings[0].model_copy(update={"git_blob": "0" * 40}),)
        expected = subject.Code.SOURCE_CHANGED
    elif control == "missing_mapping":
        mappings = ()
    elif control == "alias_mapping":
        mappings = (mappings[0], mappings[0])
    elif control == "wrong_visitor":
        pin = pin.model_copy(update={"visitor_sha256": "0" * 64})
        expected = subject.Code.SOURCE_CHANGED
    else:
        pytest.fail("Unexpected control")
    assert {
        issue.code for issue in subject.fir_archive_issues(plugin, binary, source, mappings, pin)
    } == {expected}


def test_producer_coordinate_must_match_the_adapter() -> None:
    pom = (ROOT / "upstream/provenance/fir.pom").read_bytes()
    sbom = (ROOT / "upstream/provenance/fir.spdx.json").read_bytes()
    assert subject.producer_coordinate_issues(pom, sbom, "2.4.20-ij262-34") == ()
    wrong_pom = pom.replace(b"2.4.20-ij262-34", b"2.4.20-ij262-35")
    wrong_sbom = sbom.replace(b"2.4.20-ij262-34", b"2.4.20-ij262-35")
    for changed_pom, changed_sbom in [(wrong_pom, sbom), (pom, wrong_sbom)]:
        assert {
            issue.code
            for issue in subject.producer_coordinate_issues(
                changed_pom, changed_sbom, "2.4.20-ij262-34"
            )
        } == {subject.Code.SOURCE_PROVENANCE_REJECTED}


def test_changed_producer_artifact_rejects_before_source_admission(tmp_path: Path) -> None:
    raw = subject.read_pin(ROOT)
    assert isinstance(raw, subject.Accepted)
    changed = tmp_path / "changed.jar"
    _ = changed.write_bytes(archive(((CLASS, b"changed"),)))
    result = subject.admit_published_fir_sources(ROOT, raw.value, b"unreached", changed, changed)
    assert isinstance(result, subject.Rejected)
    assert subject.Code.DISTRIBUTION_CHANGED in {issue.code for issue in result.issues}


def test_duplicate_archive_entries_cannot_select_an_arbitrary_body() -> None:
    pin, mappings = scope()
    with pytest.warns(UserWarning, match="Duplicate name"):
        duplicate = archive(((CLASS, b"first"), (CLASS, b"second")))
    source = archive(((VISITOR, b"unused"),))
    assert {
        issue.code
        for issue in subject.fir_archive_issues(duplicate, duplicate, source, mappings, pin)
    } == {subject.Code.SOURCE_POPULATION_MISMATCH}


def test_xml_entity_provenance_rejects() -> None:
    sbom = (ROOT / "upstream/provenance/fir.spdx.json").read_bytes()
    malicious = b'<!DOCTYPE project [<!ENTITY forged "version">]><project>&forged;</project>'
    assert {
        issue.code
        for issue in subject.producer_coordinate_issues(malicious, sbom, "2.4.20-ij262-34")
    } == {subject.Code.SOURCE_PROVENANCE_REJECTED}


def test_admitted_source_bytes_remain_bound_to_their_pin(tmp_path: Path) -> None:
    pin, mappings = scope()
    assert pin.published_fir is not None
    metadata = tmp_path / "upstream/provenance"
    metadata.mkdir(parents=True)
    for name in ("fir.pom", "fir.spdx.json"):
        _ = shutil.copyfile(ROOT / "upstream/provenance" / name, metadata / name)
    with (metadata / "fir-source-map.tsv").open("w") as stream:
        writer = csv.writer(stream, delimiter="\t")
        writer.writerow(["source_path", "git_blob", "archive_entry", "sha256"])
        for row in mappings:
            writer.writerow([row.source_path, row.git_blob, row.archive_entry, row.sha256])
    binary = archive(((CLASS, b"independent class payload"),))
    source = archive(
        ((VISITOR, b"package org.jetbrains.kotlin.fir.visitors\nabstract class FirVisitor\n"),)
    )
    binary_path, source_path = tmp_path / "binary.jar", tmp_path / "source.jar"
    _ = binary_path.write_bytes(binary)
    _ = source_path.write_bytes(source)
    source_pin = pin.published_fir.model_copy(
        update={
            "binary_sha256": hashlib.sha256(binary).hexdigest(),
            "sources_sha256": hashlib.sha256(source).hexdigest(),
            "source_map_sha256": hashlib.sha256(
                (metadata / "fir-source-map.tsv").read_bytes()
            ).hexdigest(),
        }
    )
    pin = pin.model_copy(update={"published_fir": source_pin})
    admitted = subject.admit_published_fir_sources(tmp_path, pin, binary, binary_path, source_path)
    assert isinstance(admitted, subject.Accepted)
    distribution = subject.DistributionObservation(
        plugin_sha256=pin.plugin_sha256,
        compiler_common_sha256=pin.compiler_common_sha256,
        plugin_version=pin.plugin_version,
        adapter_compiler_version=pin.adapter_compiler_version,
        elements=("FirElement",),
        source_correspondence=admitted.value,
    )
    assert subject.source_correspondence_issues(pin, distribution) == ()
    foreign = pin.model_copy(update={"upstream_commit": "1" * 40})
    assert {
        issue.code for issue in subject.source_correspondence_issues(foreign, distribution)
    } == {subject.Code.SOURCE_PROVENANCE_REJECTED}


def test_partial_source_request_rejects_before_file_reads(
    capsys: pytest.CaptureFixture[str],
) -> None:
    assert (
        subject.main(["audit", "--root", "/missing-source-root", "--fir-binary-jar", "unread.jar"])
        == 1
    )
    result = subject.AuditReport.model_validate_json(capsys.readouterr().out)
    assert result.status == "FAIL"
    assert {issue.code for issue in result.issues} == {subject.Code.INVALID_INPUT}
