#!/usr/bin/env python3
"""Local proof boundary. Never promotes reference checks to installed-product acceptance."""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def encoded(value: object) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()


def run(command: list[str]) -> bytes:
    return subprocess.run(command, cwd=ROOT, check=True, capture_output=True, timeout=120).stdout


def head_and_inputs() -> tuple[str, str]:
    if Path(run(["git", "rev-parse", "--show-toplevel"]).decode().strip()).resolve() != ROOT:
        raise ValueError("repository-root-mismatch")
    if run(["git", "status", "--porcelain", "--untracked-files=normal"]).strip():
        raise ValueError("dirty-checkout")
    head = run(["git", "rev-parse", "HEAD"]).decode().strip()
    manifest = []
    for raw in sorted(run(["git", "ls-files", "-z"]).split(b"\0")):
        if raw:
            path = ROOT / os.fsdecode(raw)
            # Bind link text, not bytes outside the admitted checkout.
            content = os.readlink(path).encode() if path.is_symlink() else path.read_bytes()
            manifest.append([os.fsdecode(raw), digest(content)])
    return head, digest(encoded(manifest))


def classpath_digest(classpath: str) -> str:
    manifest = []
    for raw in classpath.split(os.pathsep):
        path = Path(raw).resolve(strict=True)
        if path.is_dir():
            for item in sorted(path.rglob("*")):
                if item.is_file():
                    manifest.append([str(item), digest(item.read_bytes())])
        else:
            manifest.append([str(path), digest(path.read_bytes())])
    return digest(encoded(manifest))


def write(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(encoded(value))
    temporary.replace(path)


def record(program_path: Path, java: str, classpath: str) -> None:
    receipt = HERE / "build/identity-proof/model.json"
    receipt.unlink(missing_ok=True)  # An interrupted attempt cannot retain predecessor success.
    head, inputs = head_and_inputs()
    java_path = Path(java).resolve(strict=True)
    commands = [[str(java_path), "-cp", classpath, "kast.example.binding.ProgramKt", mode]
                for mode in ("graph", "check")]
    before_classpath = classpath_digest(classpath)
    graph = json.loads(run(commands[0]))
    if encoded(graph) != encoded(json.loads(program_path.read_text())):
        raise ValueError("program-projection-mismatch")
    report = json.loads(run(commands[1]))
    if report.get("kind") != "reference-checks" or report.get("status") != "PASS" or report.get("proofScope") != "pure-reference-model-only":
        raise ValueError("reference-proof-rejected")
    if len(report.get("cases", [])) != 17 or len(set(report["cases"])) != 17:
        raise ValueError("reference-case-set-mismatch")
    if head_and_inputs() != (head, inputs) or classpath_digest(classpath) != before_classpath:
        raise ValueError("inputs-changed-during-execution")
    report_path = HERE / "build/identity-proof/reference-observed.json"
    write(report_path, report)
    write(receipt, {
        "schemaVersion": 1, "kind": "proof-receipt", "gate": "MODEL", "status": "PASS",
        "proofScope": "pure-reference-model-only", "programFingerprint": digest(encoded(graph)),
        "repositoryHead": head, "baseRevision": graph["baseRevision"],
        "dependencyReceiptDigests": {}, "declaredInputDigest": inputs,
        "executedCommands": commands, "executedCommandDigest": digest(encoded(commands)),
        "compilerClasspathDigest": before_classpath, "javaExecutableDigest": digest(java_path.read_bytes()),
        "observedProofValues": report["cases"],
        "artifacts": {str(report_path.relative_to(ROOT)): digest(report_path.read_bytes())},
    })
    print("PASS: MODEL receipt only; native and installed proof are not established")


def check_receipt(path: Path) -> dict:
    receipt = json.loads(path.read_text())
    head, inputs = head_and_inputs()
    if (receipt["repositoryHead"], receipt["declaredInputDigest"]) != (head, inputs):
        raise ValueError("stale-receipt")
    if receipt["gate"] != "MODEL" or receipt["proofScope"] != "pure-reference-model-only" or receipt["status"] != "PASS":
        raise ValueError("unsupported-proof-authority")
    commands = receipt["executedCommands"]
    if receipt["executedCommandDigest"] != digest(encoded(commands)):
        raise ValueError("command-digest-mismatch")
    if len(commands) != 2 or [command[-1] for command in commands] != ["graph", "check"]:
        raise ValueError("command-set-mismatch")
    for command in commands:
        if len(command) != 5 or command[1] != "-cp" or command[3] != "kast.example.binding.ProgramKt":
            raise ValueError("command-contract-mismatch")
    if commands[0][:-1] != commands[1][:-1] or receipt["javaExecutableDigest"] != digest(Path(commands[0][0]).read_bytes()):
        raise ValueError("execution-input-changed")
    if receipt["compilerClasspathDigest"] != classpath_digest(commands[0][2]):
        raise ValueError("classpath-changed")
    program = json.loads((HERE / "build/program.json").read_text())
    if receipt["programFingerprint"] != digest(encoded(program)):
        raise ValueError("program-changed")
    if receipt["baseRevision"] != program["baseRevision"] or receipt["dependencyReceiptDigests"] != {}:
        raise ValueError("dependency-or-base-mismatch")
    expected_artifact = str((HERE / "build/identity-proof/reference-observed.json").relative_to(ROOT))
    if set(receipt["artifacts"]) != {expected_artifact}:
        raise ValueError("artifact-set-mismatch")
    report = json.loads((ROOT / expected_artifact).read_text())
    if report["status"] != "PASS" or report["proofScope"] != "pure-reference-model-only" or report["cases"] != receipt["observedProofValues"]:
        raise ValueError("observed-proof-mismatch")
    for raw, expected in receipt["artifacts"].items():
        path = (ROOT / raw).resolve(strict=True)
        path.relative_to(HERE / "build/identity-proof")
        if digest(path.read_bytes()) != expected:
            raise ValueError("artifact-corruption")
    return receipt


def admit_idea(raw: str, build: str, plugin: str) -> None:
    if not raw:
        raise ValueError("matched-IDEA-directory-required")
    home = Path(raw).resolve(strict=True)
    paths = [home / "Resources/product-info.json", home / "product-info.json"]
    present = [path for path in paths if path.is_file()]
    if len(present) != 1:
        raise ValueError("IDEA-product-info-unavailable")
    observed = str(json.loads(present[0].read_text())["buildNumber"]).split("-")[-1]
    if observed != build:
        raise ValueError("example-requires-exact-reference-IDEA-build")
    versions = []
    for jar in (home / "plugins/Kotlin/lib").glob("*.jar"):
        with zipfile.ZipFile(jar) as archive:
            if "META-INF/plugin.xml" not in archive.namelist():
                continue
            descriptor = ET.fromstring(archive.read("META-INF/plugin.xml"))
            if descriptor.findtext("id") == "org.jetbrains.kotlin":
                versions.append(descriptor.findtext("version"))
    if versions != [plugin]:
        raise ValueError("matched-Kotlin-plugin-unavailable")
    print("PASS: exact reference IDEA and Kotlin plugin metadata")


def main(arguments: list[str]) -> None:
    if arguments[0] == "idea" and len(arguments) == 4:
        admit_idea(*arguments[1:])
    elif arguments[0] == "receipt" and len(arguments) == 4:
        record(Path(arguments[1]).resolve(), arguments[2], arguments[3])
    elif arguments[0] == "receipt-check" and len(arguments) == 2:
        check_receipt(Path(arguments[1])); print("PASS: current MODEL receipt")
    elif arguments[0] == "delivery" and len(arguments) == 2:
        graph = json.loads(Path(arguments[1]).read_text())
        check_receipt(HERE / "build/identity-proof/model.json")
        # This reference change implements no authority for the remaining production gates.
        # Even hand-written PASS JSON files cannot promote them to success.
        missing = [node["id"] for node in graph["tasks"] if node["id"] != "MODEL"]
        print(json.dumps({"outcome": "Rejected", "reason": "production-proof-not-implemented", "gates": missing}))
        raise SystemExit(2)
    else:
        raise ValueError("expected idea, receipt, receipt-check or delivery with documented arguments")


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except (ValueError, OSError, KeyError, IndexError, subprocess.SubprocessError, ET.ParseError) as error:
        print(json.dumps({"outcome": "Rejected", "reason": str(error)}), file=sys.stderr)
        raise SystemExit(2)
