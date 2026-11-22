"""Read the checked-in acceptance baseline pin and admit installed-acceptance inputs.

`baseline` decodes `config/ci/acceptance-baseline.json` and, with `--github-output`, exposes its
fields to later workflow steps. `admit` checks the downloaded baseline against that pin and the
producing run's jobs, then downloads and admits the pinned private interpreter for one target.
The pin is reviewed in a pull request; changing the baseline is a one-file source change.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PIN = ROOT / "config/ci/acceptance-baseline.json"
WORKFLOW = ".github/workflows/ci.yml"


class Failure(StrEnum):
    PIN_UNREADABLE = "PIN_UNREADABLE"
    PIN_INVALID = "PIN_INVALID"
    BASELINE_REJECTED = "BASELINE_REJECTED"
    INTERPRETER_TRANSFER_REJECTED = "INTERPRETER_TRANSFER_REJECTED"
    UNKNOWN_TARGET = "UNKNOWN_TARGET"


class Rejected(Exception):
    def __init__(self, failure: Failure, detail: str) -> None:
        super().__init__(f"{failure}: {detail}")
        self.failure = failure


@dataclass(frozen=True)
class BaselinePin:
    run_id: int
    run_attempt: int
    revision: str
    record_sha256: str

    def outputs(self) -> dict[str, str]:
        return {
            "run_id": str(self.run_id),
            "run_attempt": str(self.run_attempt),
            "revision": self.revision,
            "record_sha256": self.record_sha256,
        }


_FIELDS = {"schemaVersion", "runId", "runAttempt", "revision", "recordSha256"}


def decode(raw: str) -> BaselinePin:
    """Admit exactly one schema-1 pin; any missing, extra, or malformed field rejects."""
    try:
        document = json.loads(raw)
    except json.JSONDecodeError as error:
        raise Rejected(Failure.PIN_INVALID, f"not JSON: {error.msg}") from error
    if not isinstance(document, dict) or set(document) != _FIELDS or document["schemaVersion"] != 1:
        raise Rejected(Failure.PIN_INVALID, f"fields must be exactly {sorted(_FIELDS)} with schemaVersion 1")
    run_id, attempt = document["runId"], document["runAttempt"]
    if type(run_id) is not int or run_id <= 0 or type(attempt) is not int or attempt <= 0:
        raise Rejected(Failure.PIN_INVALID, "runId and runAttempt must be positive integers")
    if not isinstance(document["revision"], str) or not re.fullmatch(r"[0-9a-f]{40}", document["revision"]):
        raise Rejected(Failure.PIN_INVALID, "revision must be a full lowercase commit ID")
    if not isinstance(document["recordSha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", document["recordSha256"]):
        raise Rejected(Failure.PIN_INVALID, "recordSha256 must be a lowercase SHA-256")
    return BaselinePin(run_id, attempt, document["revision"], document["recordSha256"])


def read_pin(path: Path = PIN) -> BaselinePin:
    try:
        return decode(path.read_text())
    except OSError as error:
        raise Rejected(Failure.PIN_UNREADABLE, f"{path}: {error.strerror}") from error


def write_outputs(pin: BaselinePin, destination: Path) -> None:
    with destination.open("a") as output:
        for key, value in pin.outputs().items():
            output.write(f"{key}={value}\n")


def admit(pin: BaselinePin, target: str, baseline: Path, build: Path) -> None:
    """Admit the downloaded baseline's provenance, then the pinned private interpreter for one target."""
    sys.path[:0] = [str(ROOT / ".github/scripts/release"), str(ROOT / "packaging/internal/bootstrap"), str(ROOT / "packaging")]
    import product_candidate  # noqa: PLC0415 - resolved from the release scripts above
    from private_interpreter import DEPENDENCY_URL, PINS, PrivateInterpreter  # noqa: PLC0415

    producer = product_candidate.product.ProductProducer(pin.run_id, pin.run_attempt, WORKFLOW)
    route = f"repos/amichne/kast/actions/runs/{pin.run_id}/attempts/{pin.run_attempt}/jobs?per_page=100"
    jobs = product_candidate.product.document(
        subprocess.run(["gh", "api", route], check=True, capture_output=True, timeout=30).stdout
    )
    admitted = product_candidate.admit_baseline(
        baseline, pin.record_sha256, pin.revision, producer, product_candidate.observe(producer), jobs
    )
    if isinstance(admitted, product_candidate.candidate.Failure):
        raise Rejected(Failure.BASELINE_REJECTED, admitted.value)
    interpreter = next((item for item in PINS if item.target == target), None)
    if interpreter is None:
        raise Rejected(Failure.UNKNOWN_TARGET, target)
    source = product_candidate.product.HttpReleaseSource()
    for url, name, limit in (
        (interpreter.url, "private-python.tar.gz", 268435456),
        (DEPENDENCY_URL, "defusedxml.whl", 1048576),
    ):
        result = source.download(url, build / name, limit)
        if isinstance(result, product_candidate.product.TRANSFER_REJECTIONS):
            raise Rejected(Failure.INTERPRETER_TRANSFER_REJECTED, product_candidate.product.transfer(result).cause.value)
    PrivateInterpreter.admit(build / "private-python.tar.gz", build / "defusedxml.whl", interpreter.target)


def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    baseline = commands.add_parser("baseline", help="decode the pin and optionally expose it to GitHub Actions")
    baseline.add_argument("--github-output", type=Path)
    admission = commands.add_parser("admit", help="admit the downloaded baseline and the private interpreter")
    admission.add_argument("--target", required=True)
    admission.add_argument("--baseline", type=Path, default=ROOT / "build/product-baseline")
    admission.add_argument("--build", type=Path, default=ROOT / "build")
    parsed = parser.parse_args(arguments)
    try:
        pin = read_pin()
        if parsed.command == "baseline":
            print(json.dumps(pin.outputs(), sort_keys=True))
            if parsed.github_output is not None:
                write_outputs(pin, parsed.github_output)
        else:
            admit(pin, parsed.target, parsed.baseline, parsed.build)
    except Rejected as rejection:
        print(f"acceptance-inputs: {rejection}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
