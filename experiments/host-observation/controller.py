#!/usr/bin/env python3
"""Console-only host qualification. No process launch, listener, or force recovery."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import stat
import sys
import time
import uuid
from dataclasses import dataclass
from datetime import datetime
from enum import Enum
from pathlib import Path

BUILD = "262.10315.125"
FACTORY = "org.jetbrains.kotlin.jsr223.Jsr223KotlincScriptEngineFactory"
ROOT = Path(__file__).resolve().parent
MAX_REQUEST = 16 * 1024
MAX_RECEIPT = 128 * 1024


class Reason(str, Enum):
    RECEIPT_UNAVAILABLE = "RECEIPT_UNAVAILABLE"
    RECEIPT_REJECTED = "RECEIPT_REJECTED"
    REQUEST_REJECTED = "REQUEST_REJECTED"
    OUTPUT_REJECTED = "OUTPUT_REJECTED"
    HOST_UNSUPPORTED = "HOST_UNSUPPORTED"
    ENGINE_UNAVAILABLE = "ENGINE_UNAVAILABLE"
    API_UNAVAILABLE = "API_UNAVAILABLE"
    TARGET_UNAVAILABLE = "TARGET_UNAVAILABLE"
    CAPACITY_EXCEEDED = "CAPACITY_EXCEEDED"
    EXECUTION_UNCONFIRMED = "EXECUTION_UNCONFIRMED"
    OWNERSHIP_CONFLICT = "OWNERSHIP_CONFLICT"
    RETIREMENT_UNCONFIRMED = "RETIREMENT_UNCONFIRMED"
    RESOURCE_EXHAUSTED = "RESOURCE_EXHAUSTED"


@dataclass(frozen=True)
class Rejected:
    reason: Reason


@dataclass(frozen=True)
class UnmetPrecondition:
    reason: Reason


@dataclass(frozen=True)
class Observed:
    receipt: dict


@dataclass(frozen=True)
class Prepared:
    directory: Path


Outcome = Rejected | UnmetPrecondition | Observed | Prepared


class Retirement(str, Enum):
    CONFIRMED = "CONFIRMED"
    UNCONFIRMED = "UNCONFIRMED"


def text(value, limit=1024):
    return isinstance(value, str) and 0 < len(value) <= limit and not any(ord(c) < 32 for c in value)


def shape(value, keys):
    return isinstance(value, dict) and set(value) == set(keys.split())


def canonical_uuid(value):
    try:
        return str(uuid.UUID(value)) == value
    except (ValueError, AttributeError, TypeError):
        return False


def valid_request(value):
    common = "type version requestId artifactSha256 expectedBuild outputDirectory"
    if not isinstance(value, dict):
        return False
    keys = common if value.get("type") == "PREFLIGHT" else common + " hostPid hostStartedAt targetBasePath targetLocationHash sessionId"
    valid = (shape(value, keys) and value["type"] in ("PREFLIGHT", "ATTACH")
             and type(value["version"]) is int and value["version"] == 1
             and canonical_uuid(value["requestId"]) and isinstance(value["artifactSha256"], str)
             and re.fullmatch(r"[0-9a-f]{64}", value["artifactSha256"]) is not None
             and value["expectedBuild"] == BUILD and text(value["outputDirectory"])
             and Path(value["outputDirectory"]).is_absolute())
    if not valid or value["type"] == "PREFLIGHT":
        return valid
    return (type(value["hostPid"]) is int and value["hostPid"] > 0 and text(value["hostStartedAt"], 64)
            and text(value["targetBasePath"]) and Path(value["targetBasePath"]).is_absolute()
            and text(value["targetLocationHash"], 256) and canonical_uuid(value["sessionId"]))


def valid_host(value):
    if not shape(value, "type pid startedAt build jbr"):
        return False
    if not (value["type"] == "HOST" and type(value["pid"]) is int and value["pid"] > 0
            and value["build"] == BUILD and text(value["jbr"], 128) and text(value["startedAt"], 64)):
        return False
    try:
        return datetime.fromisoformat(value["startedAt"].replace("Z", "+00:00")).tzinfo is not None
    except ValueError:
        return False


def valid_engine(value):
    return (shape(value, "type factory name version pluginVersion") and value["type"] == "ENGINE"
            and value["factory"] == FACTORY and value["pluginVersion"] == BUILD + "-IJ"
            and text(value["name"], 128) and text(value["version"], 128))


def valid_project(value):
    return (shape(value, "type locationHash basePath contentRoots") and value["type"] == "PROJECT"
            and text(value["locationHash"], 256) and text(value["basePath"])
            and Path(value["basePath"]).is_absolute() and isinstance(value["contentRoots"], list)
            and len(value["contentRoots"]) <= 256
            and all(text(p) and Path(p).is_absolute() for p in value["contentRoots"])
            and value["contentRoots"] == sorted(set(value["contentRoots"])))


def verify_receipt(request, receipt) -> Outcome:
    """Admit correlated historical receipts; never infer current host liveness."""
    if not valid_request(request):
        return Rejected(Reason.REQUEST_REJECTED)
    if receipt is None:
        return UnmetPrecondition(Reason.RECEIPT_UNAVAILABLE)
    if not isinstance(receipt, dict):
        return Rejected(Reason.RECEIPT_REJECTED)
    correlated = (receipt.get("requestId") == request["requestId"]
                  and receipt.get("artifactSha256") == request["artifactSha256"]
                  and type(receipt.get("version")) is int and receipt["version"] == 1
                  )
    if not correlated:
        return Rejected(Reason.RECEIPT_REJECTED)
    if shape(receipt, "type version requestId artifactSha256 reason stage ownership") and receipt["type"] == "REJECTED":
        host_reasons = {"REQUEST_REJECTED", "OUTPUT_REJECTED", "HOST_UNSUPPORTED", "ENGINE_UNAVAILABLE",
                        "API_UNAVAILABLE", "TARGET_UNAVAILABLE", "CAPACITY_EXCEEDED", "OWNERSHIP_CONFLICT", "RETIREMENT_UNCONFIRMED"}
        if isinstance(receipt["reason"], str) and isinstance(receipt["stage"], str) and receipt["reason"] in host_reasons and receipt["stage"] in {"REQUEST", "HOST", "ENGINE", "API", "PROJECTS", "OUTPUT", "ATTACH"} and receipt["ownership"] == ("RETIREMENT_UNCONFIRMED" if receipt["reason"] == "RETIREMENT_UNCONFIRMED" else "NOT_ACQUIRED"):
            return Rejected(Reason(receipt["reason"]))
        return Rejected(Reason.RECEIPT_REJECTED)
    if request["type"] == "ATTACH":
        if not (shape(receipt, "type version requestId artifactSha256 sessionId host project sessionDirectory")
                and receipt["type"] == "ATTACHED" and receipt["sessionId"] == request["sessionId"]
                and valid_host(receipt["host"]) and valid_project(receipt["project"])
                and receipt["host"]["pid"] == request["hostPid"]
                and receipt["host"]["startedAt"] == request["hostStartedAt"]
                and receipt["project"]["basePath"] == request["targetBasePath"]
                and receipt["project"]["locationHash"] == request["targetLocationHash"]
                and receipt["sessionDirectory"] == str(session_directory(request))):
            return Rejected(Reason.RECEIPT_REJECTED)
        return Observed(receipt)
    if not (shape(receipt, "type version requestId artifactSha256 host engine projects registrations capability")
            and type(receipt["registrations"]) is int and receipt["registrations"] == 0
            and receipt["type"] == "PREFLIGHT" and receipt["capability"] == "PREFLIGHT_ONLY"
            and valid_host(receipt["host"]) and valid_engine(receipt["engine"])
            and isinstance(receipt["projects"], list) and len(receipt["projects"]) <= 64
            and all(valid_project(p) for p in receipt["projects"])):
        return Rejected(Reason.RECEIPT_REJECTED)
    identities = [(p["locationHash"], p["basePath"]) for p in receipt["projects"]]
    if len(set(identities)) != len(identities):
        return Rejected(Reason.RECEIPT_REJECTED)
    return Observed(receipt)


def session_directory(request):
    digest = lambda s: hashlib.sha256(s.encode()).hexdigest()
    return (Path.home() / ".local/state/kast-host-observation" /
            digest(f"{request['hostPid']}\n{request['hostStartedAt']}") /
            digest(f"{request['targetLocationHash']}\n{request['targetBasePath']}") /
            "sessions" / request["sessionId"])


def read_status(directory):
    admitted = verify_directory(directory)
    if not isinstance(admitted, Observed):
        return admitted
    if admitted.receipt["type"] != "ATTACHED":
        return UnmetPrecondition(Reason.TARGET_UNAVAILABLE)
    receipt = admitted.receipt
    try:
        value = read_json(Path(receipt["sessionDirectory"]) / "status.json", MAX_RECEIPT)
    except (OSError, ValueError, RecursionError):
        return UnmetPrecondition(Reason.RETIREMENT_UNCONFIRMED)
    if not (shape(value, "type version sessionId host project state stopSignal coverage lastSequence callbackCount")
            and value["type"] == "SESSION" and type(value["version"]) is int and value["version"] == 1
            and value["sessionId"] == receipt["sessionId"] and value["host"] == receipt["host"]
            and value["project"] == receipt["project"] and isinstance(value["state"], str)
            and value["state"] in {"ATTACHING", "OBSERVING", "STOPPING", "DETACHED", "RETIREMENT_UNCONFIRMED"}
            and (value["stopSignal"] == {"type": "ACTIVE"} or
                 (shape(value["stopSignal"], "type cause") and value["stopSignal"]["type"] == "REQUESTED"
                  and isinstance(value["stopSignal"]["cause"], str) and value["stopSignal"]["cause"] in
                  {"REQUESTED", "PROJECT_CLOSED", "OUTPUT_FAILURE", "CALLBACK_FAILURE", "ATTACH_FAILURE"}))
            and (value["state"] not in {"DETACHED", "STOPPING", "RETIREMENT_UNCONFIRMED"} or value["stopSignal"].get("type") == "REQUESTED")
            and shape(value["coverage"], "type reasons dropped") and value["coverage"]["type"] == "COVERAGE"
            and isinstance(value["coverage"]["reasons"], list) and len(value["coverage"]["reasons"]) <= 7
            and all(isinstance(r, str) and r in {"INITIAL_GAP", "QUEUE_OVERFLOW", "PATH_LIMIT", "BATCH_LIMIT", "JOURNAL_ROTATED", "OUTPUT_FAILURE", "CONTROL_REJECTED"} for r in value["coverage"]["reasons"])
            and "INITIAL_GAP" in value["coverage"]["reasons"]
            and len(set(value["coverage"]["reasons"])) == len(value["coverage"]["reasons"])
            and type(value["coverage"]["dropped"]) is int and 0 <= value["coverage"]["dropped"] <= 2**63-1
            and type(value["lastSequence"]) is int and 0 <= value["lastSequence"] <= 2**63-1
            and type(value["callbackCount"]) is int and 0 <= value["callbackCount"] <= 32
            and (value["state"] != "DETACHED" or value["callbackCount"] == 0)):
        return Rejected(Reason.RECEIPT_REJECTED)
    if value["state"] == "DETACHED" and retirement_evidence(receipt) is Retirement.UNCONFIRMED:
        return UnmetPrecondition(Reason.RETIREMENT_UNCONFIRMED)
    return Observed(value)


def retirement_evidence(receipt):
    """Require original-owner retirement proof and release of its shared admission."""
    location = Path(receipt["sessionDirectory"])
    try:
        marker = read_json(location / "retired.json", MAX_REQUEST)
        if marker != dict(type="RETIRED", version=1, sessionId=receipt["sessionId"]) or type(marker["version"]) is not int:
            return Retirement.UNCONFIRMED
        admission = location.parent.parent / "admission"
        if not os.path.lexists(admission):
            return Retirement.CONFIRMED
        owner = read_json(admission / "owner.json", MAX_REQUEST)
        if (shape(owner, "type sessionId") and owner["type"] == "OWNER" and canonical_uuid(owner["sessionId"])
                and owner["sessionId"] != receipt["sessionId"]):
            return Retirement.CONFIRMED
    except (OSError, ValueError, RecursionError):
        pass
    return Retirement.UNCONFIRMED



def valid_event(value):
    if not isinstance(value, dict):
        return False
    if value.get("type") == "DUMB":
        return shape(value, "type transition") and value["transition"] in ("ENTERED", "EXITED")
    if value.get("type") == "INDEXING":
        return (shape(value, "type activity activityId cancellation")
                and value["activity"] in ("SCAN_STARTED", "SCAN_FINISHED", "INDEX_STARTED", "INDEX_FINISHED")
                and type(value["activityId"]) is int and 0 <= value["activityId"] <= 2**63-1
                and value["cancellation"] in ("UNKNOWN", "CANCELLED", "NOT_CANCELLED")
                and (not value["activity"].endswith("STARTED") or value["cancellation"] == "UNKNOWN"))
    return (shape(value, "type paths batchSize inspected limitations") and value["type"] == "VFS"
            and isinstance(value["paths"], list) and len(value["paths"]) <= 32
            and all(text(p) and Path(p).is_absolute() for p in value["paths"])
            and len(value["paths"]) == len(set(value["paths"])) and sum(map(len, value["paths"])) <= 8192
            and type(value["batchSize"]) is int and 0 <= value["batchSize"] <= 2**31-1
            and type(value["inspected"]) is int and value["inspected"] == min(value["batchSize"], 128)
            and isinstance(value["limitations"], list) and len(value["limitations"]) <= 2
            and all(v in ("PATH_LIMIT", "BATCH_LIMIT") for v in value["limitations"])
            and (value["batchSize"] <= 128 or "BATCH_LIMIT" in value["limitations"]))


def inspect_journal(directory):
    status = read_status(directory)
    if not isinstance(status, Observed):
        return status
    if status.receipt.get("type") != "SESSION" or status.receipt["state"] != "DETACHED":
        return UnmetPrecondition(Reason.RETIREMENT_UNCONFIRMED)
    receipt = verify_directory(directory).receipt
    sequences = set()
    limitations = set(status.receipt["coverage"]["reasons"])
    for segment in range(4):
        try:
            raw = read_bytes(Path(receipt["sessionDirectory"]) / f"journal-{segment}.jsonl", 1024 * 1024)
        except FileNotFoundError:
            continue
        except (OSError, ValueError):
            limitations.add("SEGMENT_REJECTED")
            continue
        for line in raw.splitlines(keepends=True):
            if not line.endswith(b"\n"):
                limitations.add("TRUNCATED_TAIL")
                break
            try:
                record = json.loads(line, object_pairs_hook=no_duplicates)
                valid = (len(line) <= 64 * 1024 and shape(record, "type version sessionId sequence event")
                         and record["type"] == "OBSERVATION" and type(record["version"]) is int and record["version"] == 1
                         and record["sessionId"] == receipt["sessionId"] and type(record["sequence"]) is int
                         and 1 <= record["sequence"] <= status.receipt["lastSequence"] and valid_event(record["event"]))
            except (ValueError, RecursionError):
                valid = False
            if not valid:
                limitations.add("RECORD_REJECTED")
                break
            if record["sequence"] in sequences:
                limitations.add("SEQUENCE_REJECTED")
                break
            sequences.add(record["sequence"])
    if len(sequences) != status.receipt["lastSequence"]:
        limitations.add("SEQUENCE_GAP")
    return Observed(dict(type="JOURNAL", sessionId=receipt["sessionId"], retainedRecords=len(sequences),
                         limitations=sorted(limitations), semanticAuthority="NONE"))


def stop_session(directory):
    admitted = verify_directory(directory)
    if not isinstance(admitted, Observed) or admitted.receipt["type"] != "ATTACHED":
        return Rejected(Reason.REQUEST_REJECTED)
    receipt = admitted.receipt
    location = Path(receipt["sessionDirectory"])
    stop = dict(type="STOP", version=1, sessionId=receipt["sessionId"])
    current = read_status(directory)
    if isinstance(current, Observed) and current.receipt["state"] == "DETACHED":
        return current
    try:
        if location.resolve(strict=True) != location:
            return Rejected(Reason.OUTPUT_REJECTED)
        pending = location / "stop.pending"
        if not (location / "stop.json").exists():
            try:
                fd = os.open(pending, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            except FileExistsError:
                pass  # Another caller owns publication; join its retirement below.
            else:
                try:
                    with os.fdopen(fd, "w") as stream:
                        json.dump(stop, stream)
                    try:
                        os.link(pending, location / "stop.json", follow_symlinks=False)
                    except FileExistsError:
                        pass
                finally:
                    pending.unlink()
        if (location / "stop.json").exists() and read_json(location / "stop.json", MAX_REQUEST) != stop:
            return Rejected(Reason.REQUEST_REJECTED)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            current = read_status(directory)
            if isinstance(current, Observed) and current.receipt["state"] == "DETACHED":
                return current
            if isinstance(current, Rejected):
                return current
            time.sleep(0.05)
        return UnmetPrecondition(Reason.RETIREMENT_UNCONFIRMED)
    except (OSError, ValueError, RecursionError):
        return Rejected(Reason.OUTPUT_REJECTED)


def no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate field")
        result[key] = value
    return result


def read_bytes(path, maximum):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, "rb") as stream:
        metadata = os.fstat(stream.fileno())
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > maximum:
            raise ValueError("unbounded input")
        raw = stream.read(maximum + 1)
    if len(raw) > maximum:
        raise ValueError("unbounded input")
    return raw


def read_json(path, maximum):
    return json.loads(read_bytes(path, maximum), object_pairs_hook=no_duplicates,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("nonfinite number")))


def verify_directory(directory: Path) -> Outcome:
    try:
        request = read_json(directory / "request.json", MAX_REQUEST)
    except (OSError, ValueError, RecursionError):
        return Rejected(Reason.REQUEST_REJECTED)
    if not valid_request(request) or request["outputDirectory"] != str(directory.resolve()):
        return Rejected(Reason.REQUEST_REJECTED)
    try:
        receipt = read_json(directory / "receipt.json", MAX_RECEIPT)
    except FileNotFoundError:
        try:
            evaluation = read_json(directory / "evaluation.json", MAX_RECEIPT)
            if (shape(evaluation, "type requestId stage outcome artifactSha256")
                    and evaluation["type"] == "EVALUATION" and evaluation["requestId"] == request["requestId"]
                    and evaluation["artifactSha256"] == request["artifactSha256"] and evaluation["stage"] == "ENGINE"
                    and evaluation["outcome"] in ("RESOURCE_EXHAUSTED", "EXECUTION_UNCONFIRMED")):
                return UnmetPrecondition(Reason(evaluation["outcome"]))
        except (OSError, ValueError, RecursionError):
            pass
        return UnmetPrecondition(Reason.RECEIPT_UNAVAILABLE)
    except (OSError, ValueError, RecursionError):
        return Rejected(Reason.RECEIPT_REJECTED)
    return verify_receipt(request, receipt)


def prepare(directory: Path, preflight: Path | None = None, project: str | None = None) -> Outcome:
    """Allocate a fresh private request directory; never overwrite a previous run."""
    directory = directory.expanduser().absolute()
    try:
        parent = directory.parent.resolve(strict=True)
        destination = parent / directory.name
        repo = ROOT.parents[1]
        if destination == repo or repo in destination.parents or destination in repo.parents:
            return Rejected(Reason.OUTPUT_REJECTED)
        artifact = ROOT / "observer.kts"
        digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
        request = dict(type="PREFLIGHT", version=1, requestId=str(uuid.uuid4()), artifactSha256=digest,
                       expectedBuild=BUILD, outputDirectory=str(destination))
        if preflight is not None:
            prior = verify_directory(preflight)
            if not isinstance(prior, Observed) or prior.receipt["type"] != "PREFLIGHT":
                return Rejected(Reason.REQUEST_REJECTED)
            candidates = [p for p in prior.receipt["projects"] if p["basePath"] == project]
            if len(candidates) != 1:
                return Rejected(Reason.TARGET_UNAVAILABLE)
            selected = candidates[0]; host = prior.receipt["host"]
            request.update(type="ATTACH", hostPid=host["pid"], hostStartedAt=host["startedAt"],
                           targetBasePath=selected["basePath"], targetLocationHash=selected["locationHash"],
                           sessionId=str(uuid.uuid4()))
        if not valid_request(request):
            return Rejected(Reason.REQUEST_REJECTED)
        destination.mkdir(mode=0o700)
        # Directory exclusivity and owner-only permissions protect all three CREATE_NEW writes.
        (destination / "request.json").write_text(json.dumps(request, indent=2) + "\n")
        b64 = lambda p: base64.b64encode(str(p).encode()).decode("ascii")
        template = (ROOT / "console.kts.template").read_text()
        bootstrap = template.replace("@REQUEST_PATH_BASE64@", b64(destination / "request.json"))
        bootstrap = bootstrap.replace("@CARRIER_PATH_BASE64@", b64(artifact))
        bootstrap = bootstrap.replace("@REQUEST_ID_BASE64@", b64(request["requestId"]))
        (destination / "console.kts").write_text(bootstrap)
        return Prepared(destination)
    except (OSError, ValueError):
        return Rejected(Reason.OUTPUT_REJECTED)


def render(outcome: Outcome):
    match outcome:
        case Prepared(directory):
            return dict(type="PREPARED", directory=str(directory), console=str(directory / "console.kts")), 0
        case Observed(receipt):
            return dict(type="OBSERVED", receipt=receipt), 0
        case Rejected(reason):
            return dict(type="REJECTED", reason=reason.value), 1
        case UnmetPrecondition(reason):
            return dict(type="UNMET_PRECONDITION", reason=reason.value), 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("preflight", "attach", "verify", "status", "stop", "journal"))
    parser.add_argument("--directory", required=True, type=Path, help="Explicit private output directory outside all projects")
    parser.add_argument("--preflight", type=Path, help="Previous preflight request directory; host admission is repeated by the carrier")
    parser.add_argument("--project", help="Exact canonical basePath reported by preflight")
    args = parser.parse_args()
    if args.operation == "attach" and (args.preflight is None or args.project is None):
        parser.error("attach requires --preflight and --project")
    if args.operation in ("preflight", "attach"):
        outcome = prepare(args.directory, args.preflight if args.operation == "attach" else None, args.project)
    elif args.operation == "stop":
        outcome = stop_session(args.directory)
    elif args.operation == "journal":
        outcome = inspect_journal(args.directory)
    elif args.operation == "status":
        outcome = read_status(args.directory)
    else:
        outcome = verify_directory(args.directory)
    payload, code = render(outcome)
    print(json.dumps(payload, separators=(",", ":")))
    return code


if __name__ == "__main__":
    sys.exit(main())
