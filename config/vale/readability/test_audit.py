"""Independent input and output checks for the two-stage report runner."""

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
