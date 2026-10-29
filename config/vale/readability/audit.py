# /// script
# requires-python = ">=3.12,<3.14"
# dependencies = []
# [tool.uv]
# no-build = true
# ///
"""Run the frozen reader corpus through two independent Vale profiles."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
from collections import Counter
from dataclasses import asdict, dataclass
from pathlib import Path

type JsonValue = None | bool | int | float | str | list[JsonValue] | dict[str, JsonValue]


@dataclass(frozen=True)
class StageReport:
    stage: str
    command: tuple[str, ...]
    exit_code: int
    counts: dict[str, int]
    files_with_findings: int


def corpus_paths(root: Path, corpus: Path) -> tuple[str, ...]:
    """Reject absent, empty, duplicate, and out-of-repository inputs before Vale."""
    paths = tuple(corpus.read_text(encoding="utf-8").splitlines())
    if not paths or len(paths) != len(set(paths)):
        raise ValueError("The corpus must contain distinct, nonempty paths.")
    for name in paths:
        path = root / name
        if (
            not name
            or Path(name).is_absolute()
            or not path.resolve().is_relative_to(root.resolve())
            or not path.is_file()
            or path.suffix not in {".md", ".mdx", ".txt"}
        ):
            raise ValueError(f"Unsupported or missing corpus file: {name!r}")
    return paths


def alert_counts(report: JsonValue) -> tuple[dict[str, int], int]:
    """Reject malformed output instead of treating execution failures as no alerts."""
    if not isinstance(report, dict):
        raise ValueError("Vale output must be a file-to-alert object.")
    counts: Counter[str] = Counter({"error": 0, "warning": 0, "suggestion": 0})
    for rows in report.values():
        if not isinstance(rows, list):
            raise ValueError("Vale output has an invalid file or alert list.")
        for row in rows:
            if not isinstance(row, dict):
                raise ValueError("Vale output has an invalid alert.")
            severity = row.get("Severity")
            if not isinstance(severity, str) or severity not in {"error", "warning", "suggestion"}:
                raise ValueError("Vale output has an unknown severity.")
            counts[severity] += 1
    return dict(counts), len(report)


def run(root: Path, corpus: Path, output: Path, executable: str) -> int:
    paths = corpus_paths(root, corpus)
    environment = {key: value for key, value in os.environ.items() if not key.startswith("VALE_")}
    version = subprocess.run(
        [executable, "--version"], check=True, capture_output=True, text=True, env=environment
    ).stdout.strip()
    if version != "vale version 3.25.1":
        raise ValueError(f"Expected Vale 3.25.1, received {version!r}.")
    output.mkdir(parents=True, exist_ok=False)
    hashes = {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in paths}
    reports: list[StageReport] = []
    for stage in ("prose", "terminology"):
        command = (
            executable,
            "--no-global",
            f"--config=config/vale/readability/{stage}.ini",
            "--output=JSON",
            *paths,
        )
        result = subprocess.run(
            command, cwd=root, env=environment, capture_output=True, text=True, check=False
        )
        (output / f"{stage}.json").write_text(result.stdout, encoding="utf-8")
        (output / f"{stage}.stderr.txt").write_text(result.stderr, encoding="utf-8")
        if result.returncode not in {0, 1}:
            raise RuntimeError(
                f"Vale {stage} failed with exit {result.returncode}: {result.stderr}"
            )
        counts, files = alert_counts(json.loads(result.stdout))
        reports.append(StageReport(stage, command, result.returncode, counts, files))
    current_hashes = {
        name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in paths
    }
    if current_hashes != hashes:
        raise RuntimeError("Corpus changed during assessment. Repeat both stages after edits stop.")
    snapshot = {
        "engine": version,
        "stages": [asdict(report) for report in reports],
        "inputs": hashes,
        "policy": {
            str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted((root / "config/vale").rglob("*"))
            if path.is_file() and path.suffix in {".ini", ".yml", ".txt"}
        },
    }
    (output / "run.json").write_text(json.dumps(snapshot, indent=2) + "\n", encoding="utf-8")
    print(
        json.dumps(
            {
                report.stage: {"exitCode": report.exit_code, "counts": report.counts}
                for report in reports
            },
            indent=2,
        )
    )
    return max(report.exit_code for report in reports)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="New report directory")
    parser.add_argument(
        "--corpus", type=Path, default=Path("docs/reviews/readability-audit/corpus.txt")
    )
    parser.add_argument("--vale-bin", default=shutil.which("vale"))
    arguments = parser.parse_args()
    if arguments.vale_bin is None:
        parser.error("Vale is absent from PATH. Use mise exec vale@3.25.1.")
    try:
        return run(Path.cwd(), arguments.corpus, arguments.output, arguments.vale_bin)
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
