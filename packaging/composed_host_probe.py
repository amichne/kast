"""Exercise compiler callers, Java references, and rejection through an assembled Host."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path

from composed_host import object_items
from managed_headless import object_fields

CALLERS = frozenset({"forwardOnce", "mutualSecond", "aliasWrapper", "mutableAliasWrapper"})


@dataclass(frozen=True, slots=True)
class ProbeReceipt:
    runtime: str
    fixture: str
    callers: tuple[str, ...]
    javaReferenceContexts: tuple[str, ...]
    unknownResultRejected: bool


def fields(value: object) -> dict[object, object]:
    assert object_fields(value), "Expected a public object"
    return value


def items(value: object) -> list[object]:
    assert object_items(value), "Expected public rows"
    return value


def complete(value: object) -> dict[object, object]:
    result = fields(value)
    assert result.get("type") == "complete", result
    document = fields(result.get("document"))
    assert fields(document.get("coverage")).get("exhaustive") is True, document
    summary = fields(document.get("original_evidence_summary"))
    assert summary.get("failure_count") == 0 and summary.get("omission_count") == 0, summary
    return document


def probe(root: Path, fixture: Path, evidence: Path, sandbox: Path) -> ProbeReceipt:
    """The caller supplies an owned installation and an explicitly trusted disposable fixture."""
    assert (
        root.is_absolute() and root.resolve() == root and (root / ".kast-headless-owned").is_file()
    )
    assert fixture.is_absolute() and fixture.resolve() == fixture
    assert not evidence.exists() and evidence.resolve() == evidence
    evidence.mkdir(parents=True)
    environment = {
        "PATH": "/usr/bin:/bin:/usr/sbin:/sbin",
        "HOME": str(root / "state/home"),
        "TMPDIR": str(root / "state/tmp"),
    }

    def call(case: str, arguments: tuple[str, ...]) -> dict[object, object]:
        command = ("/usr/bin/sandbox-exec", "-f", str(sandbox), str(root / "bin/kast"), *arguments)
        result = subprocess.run(
            command,
            cwd=fixture,
            env=environment,
            capture_output=True,
            timeout=1080,
            start_new_session=True,
            check=False,
        )
        _ = (evidence / (case + ".json")).write_bytes(result.stdout)
        _ = (evidence / (case + ".stderr")).write_bytes(result.stderr)
        raw: object = json.loads(result.stdout)
        return fields(raw)

    result = complete(
        call(
            "callers",
            (
                "query",
                "--name",
                "invokeCallback",
                "--kind",
                "function",
                "--in",
                "invocation",
                "--source-set",
                "main",
                "--relation",
                "callers",
                "--fields",
                "name,signature,location",
                "--json",
            ),
        )
    )
    names: list[str] = []
    expected_file = (
        fixture / "forwarding/src/main/kotlin/fixture/staticcallbacks/forwarding/Forwarding.kt"
    )
    for value in items(result.get("items")):
        row = fields(value)
        name = row.get("name")
        assert isinstance(name, str)
        names.append(name)
        assert fields(row.get("location")).get("file") == str(expected_file)
        connections = items(row.get("connections"))
        assert len(connections) == 1
        edge = fields(connections[0])
        assert edge.get("coverage") == "exact-compiler-confirmed"
        assert edge.get("provenance") == "k2-authored-source"
        assert fields(edge.get("target")).get("qualifiedIdentity") == (
            "fixture.staticcallbacks.invocation.invokeCallback"
        )
    assert len(names) == 4 and set(names) == CALLERS, names
    references = complete(
        call(
            "java-references",
            (
                "query",
                "--name",
                "Nested",
                "--kind",
                "class",
                "--in",
                "invocation",
                "--source-set",
                "main",
                "--relation",
                "references",
                "--output",
                "occurrences",
                "--json",
            ),
        )
    )
    java = fixture / "invocation/src/main/java/fixture/reference/usage/ReadJavaReferences.java"
    contexts = ("IMPORT", "TYPE", "CODE")
    expected = sorted(
        (match.start(), match.end(), context)
        for match, context in zip(
            re.finditer(r"\bNested\b", java.read_text()), contexts, strict=True
        )
    )
    actual: list[tuple[int, int, str]] = []
    for value in items(references.get("items")):
        occurrence = fields(fields(value).get("occurrence"))
        span = fields(occurrence.get("occurrence"))
        assert span.get("file") == str(java)
        location = fields(span.get("range"))
        start, end, context = (
            location.get("startInclusive"),
            location.get("endExclusive"),
            occurrence.get("context"),
        )
        assert isinstance(start, int) and isinstance(end, int) and isinstance(context, str)
        actual.append((start, end, context))
        assert occurrence.get("coverage") == "exact-compiler-confirmed"
        assert occurrence.get("provenance") == "k2-authored-source"
        assert fields(occurrence.get("target")).get("qualifiedIdentity") == (
            "fixture.reference.target.ReferenceOuter.Nested"
        )
    assert sorted(actual) == expected, actual
    rejected = call(
        "unknown-result",
        (
            "result",
            "read",
            "--ref",
            "result:v1:00000000-0000-4000-8000-000000000000",
            "--json",
        ),
    )
    assert rejected.get("type") == "rejected_document", rejected
    receipt = ProbeReceipt(str(root), str(fixture), tuple(sorted(names)), contexts, True)
    _ = (evidence / "probe.json").write_text(json.dumps(asdict(receipt), indent=2) + "\n")
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "fixture", "evidence", "sandbox"):
        _ = parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(asdict(probe(args.root, args.fixture, args.evidence, args.sandbox)), indent=2))


if __name__ == "__main__":
    main()
