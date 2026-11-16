"""Independent input and output checks for the two-stage report runner."""

import json
import shutil
import subprocess
from pathlib import Path

import pytest
from audit import alert_counts, corpus_paths


def test_accepts_explicit_markdown_and_mdx(tmp_path: Path) -> None:
    (tmp_path / "page.md").write_text("A page.\n")
    (tmp_path / "page.mdx").write_text("<Note>A page.</Note>\n")
    corpus = tmp_path / "corpus.txt"
    corpus.write_text("page.md\npage.mdx\n")
    assert corpus_paths(tmp_path, corpus) == ("page.md", "page.mdx")


@pytest.mark.parametrize("content", ["", "missing.md\n", "../outside.md\n", "page.md\npage.md\n"])
def test_rejects_invalid_corpus_before_vale(tmp_path: Path, content: str) -> None:
    (tmp_path / "page.md").write_text("A page.\n")
    corpus = tmp_path / "corpus.txt"
    corpus.write_text(content)
    with pytest.raises(ValueError):
        corpus_paths(tmp_path, corpus)


def test_counts_independent_severities() -> None:
    assert alert_counts({"page.md": [{"Severity": "error"}, {"Severity": "suggestion"}]}) == (
        {"error": 1, "warning": 0, "suggestion": 1},
        1,
    )
    assert alert_counts({}) == ({"error": 0, "warning": 0, "suggestion": 0}, 0)


def test_rejects_unknown_severity() -> None:
    with pytest.raises(ValueError):
        alert_counts({"page.md": [{"Severity": "new-value"}]})


def test_rejects_nonreport_output() -> None:
    with pytest.raises(ValueError):
        alert_counts(["failure"])


def test_claims_rule_distinguishes_best_effort_from_superlatives(tmp_path: Path) -> None:
    vale = shutil.which("vale")
    assert vale is not None, "The pinned Vale executable must be available"
    version = subprocess.run([vale, "--version"], capture_output=True, text=True, check=True)
    assert version.stdout.strip() == "vale version 3.24.0"
    styles = Path(__file__).resolve().parents[1] / "styles"
    config = tmp_path / ".vale.ini"
    config.write_text(
        f"StylesPath = {styles}\nMinAlertLevel = suggestion\n\n[*.md]\nSlopsentral.Claims = YES\n"
    )
    page = tmp_path / "claims.md"
    page.write_text(
        "Background presentation is best effort.\n\n"
        "Background presentation uses a best-effort request.\n\n"
        "This is the best product.\n\n"
        "This is the best effortlessness.\n\n"
        "Review the best practices.\n\n"
        "The product guarantees success.\n"
    )
    result = subprocess.run(
        [vale, "--no-global", f"--config={config}", "--output=JSON", str(page)],
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode in (0, 1), result.stderr
    alerts = json.loads(result.stdout)[str(page)]
    assert [(item["Check"], item["Line"], item["Match"]) for item in alerts] == [
        ("Slopsentral.Claims", 5, "best"),
        ("Slopsentral.Claims", 7, "best"),
        ("Slopsentral.Claims", 11, "guarantees"),
    ]
