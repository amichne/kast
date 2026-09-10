#!/usr/bin/env python3
"""Opt-in compiled hosted-query proof in an already-running, explicitly selected IDEA project."""
import argparse
import base64
import hashlib
import json
from dataclasses import dataclass
from enum import Enum
from pathlib import Path, PurePosixPath
import subprocess
import tempfile
import time
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent


class AcceptanceCase(str, Enum):
    QUERY = "query"
    DIRTY_DOCUMENT = "dirty-document"
    EDIT = "edit"
    RETIRE = "retire"
    CANCEL = "cancel"
    PROJECT_CLOSE = "project-close"
    WRONG_ROOT = "wrong-root"
    PLUGIN_LIFECYCLE = "plugin-lifecycle"


class AcceptanceResult(str, Enum):
    VERIFIED = "verified"
    QUERY_REJECTED = "query_rejected"


class EvidenceFailure(str, Enum):
    UNAVAILABLE = "EVIDENCE_UNAVAILABLE"
    MALFORMED = "EVIDENCE_MALFORMED"
    RETIREMENT_UNCONFIRMED = "RETIREMENT_UNCONFIRMED"
    HOST_MISMATCH = "HOST_MISMATCH"
    PROJECT_MISMATCH = "PROJECT_MISMATCH"
    SUBSTITUTE_PROJECT = "SUBSTITUTE_PROJECT"
    TRANSPORT_READ_LOCK = "TRANSPORT_READ_LOCK"
    MODEL_CHANGED = "MODEL_CHANGED_DURING_PROOF"
    PLUGIN_LOAD_UNCONFIRMED = "PLUGIN_LOAD_UNCONFIRMED"
    PLUGIN_UNLOAD_UNCONFIRMED = "PLUGIN_UNLOAD_UNCONFIRMED"
    CHECKPOINT_UNCONFIRMED = "CHECKPOINT_UNCONFIRMED"
    CLEANUP_UNCONFIRMED = "CLEANUP_UNCONFIRMED"
    UNEXPECTED_RESULT = "UNEXPECTED_RESULT"


@dataclass(frozen=True)
class EvidenceRejected:
    failure: EvidenceFailure


AcceptanceValidation = AcceptanceResult | EvidenceRejected


class CarrierOutcome(str, Enum):
    COMPLETED = "COMPLETED"
    SCRIPT_ENTRY_UNCONFIRMED = "SCRIPT_ENTRY_UNCONFIRMED"
    BOOTSTRAP_REJECTED = "BOOTSTRAP_REJECTED"
    RESULT_UNCONFIRMED = "RESULT_UNCONFIRMED"
    RETIREMENT_UNCONFIRMED = "RETIREMENT_UNCONFIRMED"


def observe_carrier(evidence) -> CarrierOutcome:
    if (evidence / "bootstrap-failure.json").exists():
        return CarrierOutcome.BOOTSTRAP_REJECTED
    if not (evidence / "script-entered.txt").exists():
        return CarrierOutcome.SCRIPT_ENTRY_UNCONFIRMED
    if (evidence / "script-entered.txt").read_text() != "ENTERED":
        return CarrierOutcome.SCRIPT_ENTRY_UNCONFIRMED
    if not all((evidence / name).exists() for name in ("result.json", "identity.json")):
        return CarrierOutcome.RESULT_UNCONFIRMED
    if not (evidence / "retired.txt").exists():
        return CarrierOutcome.RETIREMENT_UNCONFIRMED
    return CarrierOutcome.COMPLETED


def restore_project(launcher, project, evidence, pid):
    rows = subprocess.check_output(["ps", "-ww", "-axo", "pid=,command="], text=True).splitlines()
    if not any(row.split(maxsplit=1) == [str(pid), str(launcher)] for row in rows):
        raise RuntimeError("ORIGINAL_HOST_LOST; project restoration cannot launch another host")
    template = (ROOT / "hosted-project-restoration.kts.template").read_text()
    script = template.replace("@INPUT_BASE64@", base64.b64encode(str(evidence / "input.json").encode()).decode())
    (evidence / "restore.kts").write_text(script)
    subprocess.run([str(launcher), str(project)], check=True, timeout=60, stdout=subprocess.DEVNULL)
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        subprocess.run([str(launcher), "ideScript", str(evidence / "restore.kts")], check=True, timeout=30, stdout=subprocess.DEVNULL)
        if (evidence / "restored.json").exists():
            state = json.loads((evidence / "restored.json").read_text())
            if all(state.get(key) is True for key in ("sameHost", "projectOpen", "smart", "imported")):
                return
        time.sleep(0.5)
    raise RuntimeError(f"PROJECT_RESTORATION_UNCONFIRMED: {evidence}")


def run(idea, artifact, project, source_file, offset, check=AcceptanceCase.QUERY):
    launcher = idea / "MacOS/idea"
    processes = subprocess.check_output(["ps", "-ww", "-axo", "pid=,command="], text=True)
    pids = [int(row.split(maxsplit=1)[0]) for row in processes.splitlines()
            if len(row.split(maxsplit=1)) == 2 and row.split(maxsplit=1)[1] == str(launcher)]
    if len(pids) != 1:
        raise RuntimeError("EXACT_RUNNING_HOST_UNAVAILABLE; the runner never launches an IDE for setup")
    project = project.resolve(strict=True)
    if offset < 0 or not source_file.endswith(".kt") or PurePosixPath(source_file).is_absolute() or ".." in PurePosixPath(source_file).parts:
        raise ValueError("INVALID_SELECTION")
    evidence = Path(tempfile.mkdtemp(prefix="kast-hosted-query-"))
    print(f"Evidence: {evidence}", flush=True)
    artifact_bytes = artifact.read_bytes()
    with zipfile.ZipFile(artifact) as archive:
        entries = [item for item in archive.infolist() if not item.is_dir()]
        if len(entries) != 5 or any(PurePosixPath(item.filename).parent != PurePosixPath("kast-hosted-query/lib") or not item.filename.endswith(".jar") for item in entries):
            raise ValueError("UNSUPPORTED_PLUGIN_LAYOUT")
        archive.extractall(evidence)
    jars = sorted((evidence / "kast-hosted-query/lib").glob("*.jar"))
    with zipfile.ZipFile(next(path for path in jars if path.name == "kast-hosted-query-0.1.0.jar")) as payload:
        descriptor = ET.fromstring(payload.read("META-INF/plugin.xml"))
        supported = descriptor.find("idea-version").attrib
    actual_build = json.loads((idea / "Resources/product-info.json").read_text())["buildNumber"]
    if supported != {"since-build": actual_build, "until-build": actual_build}:
        raise ValueError("ARTIFACT_HOST_MISMATCH; rebuild with -PhostedIdeaHome pointing to this installation")
    if check is AcceptanceCase.DIRTY_DOCUMENT:
        (evidence / "dirty.kt").write_text("interface HostedDirtyFixture\n")
    (evidence / "input.json").write_text(json.dumps(dict(project=str(project), file=source_file, offset=offset, hostPid=pids[0], jars=[str(path) for path in jars], check=check.value)))
    template = (ROOT / "hosted-query.kts.template").read_text()
    script = template.replace("@INPUT_BASE64@", base64.b64encode(str(evidence / "input.json").encode()).decode())
    (evidence / "run.kts").write_text(script)
    try:
        subprocess.run([str(launcher), "ideScript", str(evidence / "run.kts")], check=True, timeout=60, stdout=subprocess.DEVNULL)
        deadline = time.monotonic() + 60
        while True:
            carrier = observe_carrier(evidence)
            if carrier in (CarrierOutcome.COMPLETED, CarrierOutcome.BOOTSTRAP_REJECTED) or time.monotonic() >= deadline:
                break
            time.sleep(0.05)
        (evidence / "carrier.json").write_text(json.dumps(dict(schemaVersion=1, outcome=carrier.value)) + "\n")
    finally:
        if check is AcceptanceCase.PROJECT_CLOSE and (evidence / "project-close-requested.txt").exists():
            restore_project(launcher, project, evidence, pids[0])
        if check is AcceptanceCase.PLUGIN_LIFECYCLE and (evidence / "plugin-load-requested.txt").exists():
            template = (ROOT / "hosted-plugin-unload.kts.template").read_text()
            script = template.replace("@INPUT_BASE64@", base64.b64encode(str(evidence / "input.json").encode()).decode())
            (evidence / "unload.kts").write_text(script)
            subprocess.run([str(launcher), "ideScript", str(evidence / "unload.kts")], check=True, timeout=30, stdout=subprocess.DEVNULL)
    if carrier is not CarrierOutcome.COMPLETED:
        report = dict(acceptance="carrier_rejected", check=check.value,
                      artifactSha256=hashlib.sha256(artifact_bytes).hexdigest(), failure=carrier.value)
        (evidence / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report, indent=2))
        return 1
    answer = json.loads((evidence / "result.json").read_text())
    identity = json.loads((evidence / "identity.json").read_text())
    import jsonschema
    jsonschema.Draft202012Validator(json.loads((ROOT / "hosted-query.schema.json").read_text())).validate(answer)
    validation = validate_case(check, answer, identity, evidence, pids[0])
    report = dict(check=check.value, artifactSha256=hashlib.sha256(artifact_bytes).hexdigest(), identity=identity, result=answer)
    if isinstance(validation, EvidenceRejected):
        report.update(acceptance="evidence_rejected", failure=validation.failure.value)
        exit_code = 1
    else:
        report.update(acceptance=validation.value, retirement="RETIRED")
        exit_code = 0 if validation is AcceptanceResult.VERIFIED else 2
    (evidence / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report if isinstance(validation, EvidenceRejected) else answer, indent=2))
    return exit_code


def validate_case(check, answer, identity, evidence, pid) -> AcceptanceValidation:
    """Refine boundary evidence without checks that disappear under Python optimization."""
    try:
        return _validate_case(check, answer, identity, evidence, pid)
    except OSError:
        return EvidenceRejected(EvidenceFailure.UNAVAILABLE)
    except (ValueError, KeyError, TypeError):
        return EvidenceRejected(EvidenceFailure.MALFORMED)


def _validate_case(check, answer, identity, evidence, pid) -> AcceptanceValidation:
    if not isinstance(check, AcceptanceCase) or not isinstance(answer, dict) or not isinstance(identity, dict):
        return EvidenceRejected(EvidenceFailure.MALFORMED)
    if (evidence / "retired.txt").read_text() != "RETIRED":
        return EvidenceRejected(EvidenceFailure.RETIREMENT_UNCONFIRMED)
    if type(identity["hostPid"]) is not int or identity["hostPid"] != pid:
        return EvidenceRejected(EvidenceFailure.HOST_MISMATCH)
    if check is AcceptanceCase.PROJECT_CLOSE:
        if identity["sameProject"] is not False or identity["originalProjectDisposed"] is not True:
            return EvidenceRejected(EvidenceFailure.PROJECT_MISMATCH)
    else:
        if identity["sameProject"] is not True or identity["originalProjectDisposed"] is not False:
            return EvidenceRejected(EvidenceFailure.PROJECT_MISMATCH)
    if type(identity["newProjects"]) is not int or identity["newProjects"] != 0:
        return EvidenceRejected(EvidenceFailure.SUBSTITUTE_PROJECT)
    if identity["readLockDuringTransport"] is not False:
        return EvidenceRejected(EvidenceFailure.TRANSPORT_READ_LOCK)
    if check is not AcceptanceCase.PROJECT_CLOSE:
        if not isinstance(identity["modelBefore"], list) or not isinstance(identity["modelAfter"], list):
            return EvidenceRejected(EvidenceFailure.MALFORMED)
        if identity["modelBefore"] != identity["modelAfter"]:
            return EvidenceRejected(EvidenceFailure.MODEL_CHANGED)
    if check is AcceptanceCase.PLUGIN_LIFECYCLE:
        if (evidence / "plugin-loaded.txt").read_text() != "LOADED":
            return EvidenceRejected(EvidenceFailure.PLUGIN_LOAD_UNCONFIRMED)
        unload = json.loads((evidence / "plugin-unloaded.json").read_text())
        if not isinstance(unload, dict) or set(unload) != {"unloaded", "stillLoaded"}:
            return EvidenceRejected(EvidenceFailure.MALFORMED)
        if unload["unloaded"] is not True or unload["stillLoaded"] is not False:
            return EvidenceRejected(EvidenceFailure.PLUGIN_UNLOAD_UNCONFIRMED)
        return AcceptanceResult.VERIFIED if answer["outcome"] == "published" else EvidenceRejected(EvidenceFailure.UNEXPECTED_RESULT)
    if check is AcceptanceCase.DIRTY_DOCUMENT:
        if answer["outcome"] != "rejected" or answer["failure"] != "DIRTY_DOCUMENTS":
            return EvidenceRejected(EvidenceFailure.UNEXPECTED_RESULT)
        if (evidence / "dirty-cleanup.txt").read_text() != "RESTORED":
            return EvidenceRejected(EvidenceFailure.CLEANUP_UNCONFIRMED)
        return AcceptanceResult.VERIFIED
    if check in (AcceptanceCase.EDIT, AcceptanceCase.RETIRE, AcceptanceCase.CANCEL, AcceptanceCase.PROJECT_CLOSE):
        if (evidence / "checkpoint.txt").read_text() != "SEMANTIC_READ_DETACHED":
            return EvidenceRejected(EvidenceFailure.CHECKPOINT_UNCONFIRMED)
        if answer["outcome"] != "rejected" or answer["stage"] != "CONTENT_REVALIDATION":
            return EvidenceRejected(EvidenceFailure.UNEXPECTED_RESULT)
        if check is AcceptanceCase.EDIT:
            if (evidence / "edit-cleanup.txt").read_text() != "RESTORED":
                return EvidenceRejected(EvidenceFailure.CLEANUP_UNCONFIRMED)
            if not (answer["failure"] == "CONTENT_MOVED" or (answer["failure"] == "FRESHNESS_REJECTED" and answer["detail"] == "MOVED")):
                return EvidenceRejected(EvidenceFailure.UNEXPECTED_RESULT)
        else:
            if answer["failure"] != ("CANCELLED" if check is AcceptanceCase.CANCEL else "RETIRED"):
                return EvidenceRejected(EvidenceFailure.UNEXPECTED_RESULT)
        return AcceptanceResult.VERIFIED
    if check is AcceptanceCase.WRONG_ROOT:
        if answer["outcome"] != "rejected" or answer["failure"] != "PROJECT_ADMISSION_REJECTED" or answer["detail"] != "PROJECT_ROOT_MISMATCH":
            return EvidenceRejected(EvidenceFailure.UNEXPECTED_RESULT)
        return AcceptanceResult.VERIFIED
    if answer["outcome"] == "published":
        return AcceptanceResult.VERIFIED
    if answer["outcome"] == "rejected":
        return AcceptanceResult.QUERY_REJECTED
    return EvidenceRejected(EvidenceFailure.UNEXPECTED_RESULT)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--idea-contents", type=Path, required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--file", required=True)
    parser.add_argument("--offset", type=int, required=True, help="UTF-16 offset inside the class name")
    checks = parser.add_mutually_exclusive_group()
    checks.add_argument("--dirty-document-check", dest="check", action="store_const", const=AcceptanceCase.DIRTY_DOCUMENT, help="Check rejection with a temporary owned dirty document, then restore it")
    checks.add_argument("--check", type=AcceptanceCase, choices=list(AcceptanceCase), help="Controlled live acceptance case; project-close closes then reopens the selected project")
    parser.set_defaults(check=AcceptanceCase.QUERY)
    arguments = parser.parse_args()
    raise SystemExit(run(arguments.idea_contents, arguments.artifact, arguments.project, arguments.file, arguments.offset, arguments.check))
