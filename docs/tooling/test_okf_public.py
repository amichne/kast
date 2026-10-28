"""The public site consumes attributed OKF without another prose owner."""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

import pytest

from okf_public import (
    ROOT,
    Authored,
    Code,
    EvidenceStatus,
    Failure,
    Manifest,
    Projection,
    View,
    parse_manifest,
    project,
    run,
)

PAGE = "openwiki/contracts/example.md"
CLAIMS = "openwiki/.claims/contracts/example.json"
SOURCE = "src/example.txt"
BODY = """# Example

## Source facts

Keep the [source](../../src/example.txt) and [related fact](example.md#source-facts).
Keep `literal [source](../../src/example.txt)` unchanged.

```kotlin
val text = "[source](../../src/example.txt)"
```

[reference]: ../../src/example.txt

Use [the source][reference].
"""


@dataclass(frozen=True)
class EvidenceDocument:
    resource: str
    version: str


@dataclass(frozen=True)
class ClaimDocument:
    id: str
    statement: str
    evidence: tuple[EvidenceDocument, ...]


@dataclass(frozen=True)
class ClaimsDocument:
    pageVersion: str
    claims: tuple[ClaimDocument, ...]
    schemaVersion: int = 1


@dataclass(frozen=True)
class ManifestDocument:
    views: tuple[View, ...]
    authored: tuple[Authored, ...] = ()
    schemaVersion: int = 1


def claims_document(page: str, version: str) -> str:
    value = ClaimsDocument(
        "sha256:" + hashlib.sha256(page.encode()).hexdigest(),
        (
            ClaimDocument(
                "claim_example",
                "The example keeps one source owner.",
                (EvidenceDocument("repo://" + SOURCE, version),),
            ),
        ),
    )
    return json.dumps(asdict(value))


def fixture(root: Path) -> tuple[Manifest, frozenset[str]]:
    page = "---\ntype: Contract\ntitle: Example\n---\n\n" + BODY
    source = "independent source\n"
    files = {
        PAGE: page,
        SOURCE: source,
        CLAIMS: claims_document(
            page, "repo-file-v1:sha256:" + hashlib.sha256(source.encode()).hexdigest()
        ),
    }
    for name, text in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    return Manifest(
        (View("reference/example", 'title: "Example"', (PAGE,), (), (), ("legacy-section",)),), ()
    ), frozenset(files)


def test_real_projection_keeps_source_text_code_links_and_evidence(tmp_path: Path) -> None:
    manifest, tracked = fixture(tmp_path)
    result = project(tmp_path, manifest, tracked)
    assert isinstance(result, Projection)
    artifacts = {item.path: item.text for item in result.artifacts}
    snippet = artifacts["docs/public/snippets/okf/contracts/example.mdx"]
    assert "[source](https://github.com/amichne/kast/blob/main/src/example.txt)" in snippet
    assert "[related fact](/reference/example#okf-contracts-example-source-facts)" in snippet
    assert "`literal [source](../../src/example.txt)`" in snippet
    assert 'val text = "[source](../../src/example.txt)"' in snippet
    assert "[reference]: https://github.com/amichne/kast/blob/main/src/example.txt" in snippet
    assert '<a id="okf-contracts-example-source-facts" />' in snippet
    assert result.report.concepts[0].evidence[0].status is EvidenceStatus.MATCHED
    shell = artifacts["docs/public/reference/example.mdx"]
    assert '<a id="legacy-section" />' in shell
    assert "<OkfConcept0 />" in shell
    assert "Keep the" not in shell


def test_source_drift_stays_qualified_in_page_and_report(tmp_path: Path) -> None:
    manifest, tracked = fixture(tmp_path)
    (tmp_path / SOURCE).write_text("changed source\n")
    result = project(tmp_path, manifest, tracked)
    assert isinstance(result, Projection)
    evidence = result.report.concepts[0].evidence[0]
    assert evidence.status is EvidenceStatus.CHANGED
    assert evidence.currentVersion != evidence.recordedVersion
    assert "does not prove current behavior" in result.artifacts[0].text


@pytest.mark.parametrize("missing", [PAGE, CLAIMS, SOURCE])
def test_missing_canonical_input_cannot_produce_a_view(tmp_path: Path, missing: str) -> None:
    manifest, tracked = fixture(tmp_path)
    (tmp_path / missing).unlink()
    assert project(tmp_path, manifest, tracked) == Failure(Code.IO, missing)


@pytest.mark.parametrize("untracked", [PAGE, CLAIMS, SOURCE])
def test_untracked_evidence_cannot_produce_an_attributed_view(
    tmp_path: Path, untracked: str
) -> None:
    manifest, tracked = fixture(tmp_path)
    assert project(tmp_path, manifest, tracked - {untracked}) == Failure(Code.TRACKING, untracked)


def test_page_edit_without_claim_reconciliation_rejects(tmp_path: Path) -> None:
    manifest, tracked = fixture(tmp_path)
    with (tmp_path / PAGE).open("a") as stream:
        stream.write("Unreconciled assertion.\n")
    assert project(tmp_path, manifest, tracked) == Failure(Code.ATTRIBUTION, PAGE)


@pytest.mark.parametrize("metadata", ["type: ''", "type: []", "type: Contract\ntype: Contract"])
def test_invalid_okf_metadata_rejects(tmp_path: Path, metadata: str) -> None:
    manifest, tracked = fixture(tmp_path)
    (tmp_path / PAGE).write_text("---\n" + metadata + "\n---\n\n" + BODY)
    assert project(tmp_path, manifest, tracked) == Failure(Code.ATTRIBUTION, PAGE)


def test_symlink_source_rejects_even_when_its_bytes_match(tmp_path: Path) -> None:
    manifest, tracked = fixture(tmp_path)
    original = tmp_path / "elsewhere.txt"
    (tmp_path / SOURCE).rename(original)
    (tmp_path / SOURCE).symlink_to(original)
    assert project(tmp_path, manifest, tracked) == Failure(Code.PATH, SOURCE)


INVALID_MANIFESTS: list[dict[str, object]] = [
    {"schemaVersion": True},
    {"unknown": "field"},
    {"views": []},
    {"authored": [{"route": "../escape", "purpose": "reader-navigation"}]},
    {"authored": [{"route": "reference/example", "purpose": {}}]},
]


@pytest.mark.parametrize("change", INVALID_MANIFESTS)
def test_manifest_rejects_unknown_states(change: dict[str, object]) -> None:
    data: dict[str, object] = {
        "schemaVersion": 1,
        "views": [
            {
                "route": "reference/example",
                "frontmatter": "title: Example",
                "concepts": [PAGE],
                "imports": [],
                "diagrams": [],
                "anchors": [],
            }
        ],
        "authored": [],
    }
    data.update(change)
    assert isinstance(parse_manifest(json.dumps(data)), Failure)


def test_duplicate_route_and_duplicate_json_key_reject() -> None:
    view = {
        "route": "reference/example",
        "frontmatter": "title: Example",
        "concepts": [PAGE],
        "imports": [],
        "diagrams": [],
        "anchors": [],
    }
    assert isinstance(
        parse_manifest(json.dumps({"schemaVersion": 1, "views": [view, view], "authored": []})),
        Failure,
    )
    assert isinstance(
        parse_manifest('{"schemaVersion":1,"schemaVersion":1,"views":[],"authored":[]}'), Failure
    )


def test_unrecognized_version_keeps_its_original_identity(tmp_path: Path) -> None:
    manifest, tracked = fixture(tmp_path)
    (tmp_path / CLAIMS).write_text(
        claims_document((tmp_path / PAGE).read_text(), "opaque-v2:recorded-authority")
    )
    result = project(tmp_path, manifest, tracked)
    assert isinstance(result, Projection)
    evidence = result.report.concepts[0].evidence[0]
    assert evidence.recordedVersion == "opaque-v2:recorded-authority"
    assert evidence.status is EvidenceStatus.NOT_ASSESSED


def test_cli_write_then_check_rejects_an_independent_output_edit(tmp_path: Path) -> None:
    manifest, _tracked = fixture(tmp_path)
    route = tmp_path / "docs/public/reference/example.mdx"
    route.parent.mkdir(parents=True)
    route.write_text("old separate public prose\n")
    metadata = tmp_path / "docs/public/.mintlify/product-brief.md"
    metadata.parent.mkdir(parents=True)
    metadata.write_text("Private product context, not a public route.\n")
    (tmp_path / "docs/okf-public.json").write_text(
        json.dumps(asdict(ManifestDocument(manifest.views)))
    )
    git = shutil.which("git")
    assert git is not None
    subprocess.run([git, "init", "-q", str(tmp_path)], check=True)
    subprocess.run([git, "add", "."], cwd=tmp_path, check=True)
    assert not isinstance(run(tmp_path, write=True), Failure)
    assert not isinstance(run(tmp_path, write=False), Failure)
    assert run(tmp_path, write=True) == run(tmp_path, write=False)
    orphan = tmp_path / "docs/public/snippets/okf/contracts/retired.mdx"
    orphan.write_text((tmp_path / "docs/public/snippets/okf/contracts/example.mdx").read_text())
    assert run(tmp_path, write=False) == Failure(
        Code.DRIFT, "docs/public/snippets/okf/contracts/retired.mdx"
    )
    assert not isinstance(run(tmp_path, write=True), Failure)
    assert not orphan.exists()
    orphan.write_text("An authored file must not disappear.\n")
    assert run(tmp_path, write=True) == Failure(
        Code.DRIFT, "docs/public/snippets/okf/contracts/retired.mdx"
    )
    assert orphan.read_text() == "An authored file must not disappear.\n"
    orphan.unlink()
    with route.open("a") as stream:
        stream.write("Another prose owner.\n")
    assert run(tmp_path, write=False) == Failure(Code.DRIFT, "docs/public/reference/example.mdx")


@pytest.mark.parametrize("suffix", ["md", "mdx"])
def test_public_inventory_needs_a_deliberate_owner_for_new_pages(
    tmp_path: Path, suffix: str
) -> None:
    manifest, _tracked = fixture(tmp_path)
    (tmp_path / "docs/public").mkdir(parents=True)
    (tmp_path / f"docs/public/unowned.{suffix}").write_text("No declared owner.\n")
    (tmp_path / "docs/okf-public.json").write_text(
        json.dumps(asdict(ManifestDocument(manifest.views)))
    )
    git = shutil.which("git")
    assert git is not None
    subprocess.run([git, "init", "-q", str(tmp_path)], check=True)
    result = run(tmp_path, write=True)
    assert isinstance(result, Failure)
    assert result.code is Code.INPUT
    assert not (tmp_path / "docs/public/snippets").exists()


def test_repository_public_projection_matches_its_canonical_sources() -> None:
    result = run(ROOT, write=False)
    assert not isinstance(result, Failure), result


@pytest.mark.parametrize("previously_generated", [False, True])
def test_write_creates_or_restores_declared_generated_route(
    tmp_path: Path, previously_generated: bool
) -> None:
    manifest, _tracked = fixture(tmp_path)
    (tmp_path / "docs/public").mkdir(parents=True)
    (tmp_path / "docs/okf-public.json").write_text(
        json.dumps(asdict(ManifestDocument(manifest.views)))
    )
    git = shutil.which("git")
    assert git is not None
    subprocess.run([git, "init", "-q", str(tmp_path)], check=True)
    subprocess.run([git, "add", "."], cwd=tmp_path, check=True)
    route = tmp_path / "docs/public/reference/example.mdx"
    if previously_generated:
        route.parent.mkdir(parents=True, exist_ok=True)
        route.write_text("Old public prose.\n")
        assert not isinstance(run(tmp_path, write=True), Failure)
        route.unlink()
    assert isinstance(run(tmp_path, write=False), Failure)
    assert not route.exists()
    process = subprocess.run(
        [
            sys.executable,
            str(ROOT / "docs/tooling/okf_public.py"),
            "--write",
            "--repo",
            str(tmp_path),
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    assert process.returncode == 0, process.stdout + process.stderr
    assert json.loads(process.stdout)["views"] == ["reference/example"]
    assert "<OkfConcept0 />" in route.read_text()
    assert not isinstance(run(tmp_path, write=False), Failure)


def test_write_does_not_invent_a_missing_authored_page(tmp_path: Path) -> None:
    manifest, _tracked = fixture(tmp_path)
    (tmp_path / "docs/public").mkdir(parents=True)
    (tmp_path / "docs/okf-public.json").write_text(
        json.dumps(
            asdict(ManifestDocument(manifest.views, (Authored("start", "reader-navigation"),)))
        )
    )
    git = shutil.which("git")
    assert git is not None
    subprocess.run([git, "init", "-q", str(tmp_path)], check=True)
    subprocess.run([git, "add", "."], cwd=tmp_path, check=True)
    result = run(tmp_path, write=True)
    assert isinstance(result, Failure)
    assert result.code is Code.INPUT
    assert list((tmp_path / "docs/public").iterdir()) == []
