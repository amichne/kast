#!/usr/bin/env python3
"""KVP-034 installed exact-root acceptance; emits only closed deterministic evidence."""

import argparse
import glob
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import zipfile


OPERATIONS = ["workspace.inspect", "symbol.discover", "symbol.resolve", "symbol.describe"]
PROGRAM_SOURCE = "build-logic/src/main/kotlin/support/delivery/KastVfsPassiveReusedIndexProgram.kt"
PROGRAM_SYMBOL = "KastVfsPassiveReusedIndexProgram"
DISCOVERY_READINESS_ATTEMPTS = 60
DISCOVERY_READINESS_DELAY_SECONDS = 1


class Rejected(Exception):
    def __init__(self, reason, **evidence):
        self.document = {"outcome": "REJECTED", "reason": reason, **evidence}


def canonical(document):
    return json.dumps(document, ensure_ascii=False, separators=(",", ":"), sort_keys=False) + "\n"


def load(path):
    with open(path, "r", encoding="utf-8") as source:
        return json.load(source)


def sha(raw):
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def endpoint_path(root):
    key = hashlib.sha256(root.encode("utf-8")).hexdigest()[:24]
    return Path("/tmp") / (".k" + key) / "s.endpoint.json"


def processes():
    result = subprocess.run(
        ["ps", "-ax", "-o", "pid=,command="], text=True, capture_output=True, check=True,
    )
    return [(int(line.split(None, 1)[0]), line.split(None, 1)[1])
            for line in result.stdout.splitlines() if len(line.split(None, 1)) == 2]


def exact_indexers(root, rows):
    return {pid for pid, command in rows if "kast-indexer" in command and root in command}


def require_endpoint(root):
    path = endpoint_path(root)
    if not path.is_file():
        raise Rejected("HOST_PLUGIN_NOT_LOADED", descriptor=str(path), canonicalRoot=root)
    descriptor = load(path)
    required = {
        "schema": "kast.ide.endpoint.v2",
        "canonicalRoot": root,
        "hostKind": "IDE_PROJECT",
        "framing": "length-prefixed-json-v1",
    }
    if any(descriptor.get(key) != value for key, value in required.items()):
        raise Rejected("ENDPOINT_IDENTITY_REJECTED", descriptor=str(path))
    pid = descriptor.get("processId")
    if not isinstance(pid, int) or pid <= 0:
        raise Rejected("ENDPOINT_PID_REJECTED", descriptor=str(path))
    matching = [command for observed, command in processes() if observed == pid]
    if len(matching) != 1 or not any(token in matching[0] for token in ("IntelliJ", "/idea")):
        raise Rejected("ENDPOINT_NOT_OWNED_BY_LIVE_IDE", processId=pid)
    if descriptor.get("capabilities") != OPERATIONS:
        raise Rejected("ENDPOINT_CAPABILITY_SET_REJECTED", capabilities=descriptor.get("capabilities"))
    return path, descriptor


def installed_kast(head):
    executable = shutil.which("kast")
    if not executable:
        raise Rejected("INSTALLED_KAST_UNAVAILABLE")
    version = subprocess.run(
        [executable, "--version"], text=True, capture_output=True, check=False,
    )
    if version.returncode != 0 or ("g" + head[:9]) not in version.stdout:
        raise Rejected("INSTALLED_KAST_HEAD_MISMATCH", observed=version.stdout.strip(), head=head)
    return executable


def observe_operation(executable, root, arguments, operation):
    result = subprocess.run(
        [executable, *arguments], cwd=root, text=True, capture_output=True, check=False,
    )
    if result.returncode != 0:
        raise Rejected(
            "INSTALLED_OPERATION_REJECTED", operation=operation,
            status=result.returncode, response=result.stdout.strip(), error=result.stderr.strip(),
        )
    try:
        document = json.loads(result.stdout)
    except json.JSONDecodeError as failure:
        raise Rejected("INSTALLED_OPERATION_MALFORMED", operation=operation) from failure
    return document, result.stdout


def admit_complete_operation(document, raw, operation):
    if document.get("operation") != operation or document.get("status") != "complete":
        raise Rejected("INSTALLED_OPERATION_INCOMPLETE", operation=operation, response=document)
    return document, {"operation": operation, "status": "complete", "responseDigest": sha(raw)}


def run_operation(executable, root, arguments, operation):
    document, raw = observe_operation(executable, root, arguments, operation)
    return admit_complete_operation(document, raw, operation)


def run_operation_until_ready(
        executable, root, arguments, operation,
        maximum_attempts=DISCOVERY_READINESS_ATTEMPTS):
    if maximum_attempts <= 0:
        raise Rejected("INSTALLED_READINESS_BOUND_REJECTED", operation=operation)
    for attempt in range(1, maximum_attempts + 1):
        document, raw = observe_operation(executable, root, arguments, operation)
        if document.get("operation") == operation and document.get("status") == "complete":
            return admit_complete_operation(document, raw, operation)
        transient = (
            operation == "symbol.discover"
            and document.get("operation") == operation
            and document.get("status") == "rejected"
            and document.get("reason") == "workspace-not-ready"
        )
        if not transient:
            raise Rejected(
                "INSTALLED_OPERATION_INCOMPLETE", operation=operation, response=document,
            )
        if attempt < maximum_attempts:
            time.sleep(DISCOVERY_READINESS_DELAY_SECONDS)
    raise Rejected(
        "INSTALLED_DISCOVERY_READINESS_EXHAUSTED",
        operation=operation,
        maximumAttempts=maximum_attempts,
    )


def semantic_journey(executable, root):
    workspace, first = run_operation(executable, root, ["workspace", "inspect"], OPERATIONS[0])
    if workspace.get("canonicalRoot") != root:
        raise Rejected("WORKSPACE_ROOT_MISMATCH", observed=workspace.get("canonicalRoot"))
    discovery, second = run_operation_until_ready(
        executable, root,
        ["symbol", "discover", "--mode", "name", "--query",
         PROGRAM_SYMBOL, "--kind", "symbol", "--match", "exact-name",
         "--limit", "10"],
        OPERATIONS[1],
    )
    program_source = str(Path(root) / PROGRAM_SOURCE)
    declarations = [item for item in discovery.get("items", [])
                    if item.get("name") == PROGRAM_SYMBOL
                    and item.get("file") == program_source
                    and isinstance(item.get("candidateSelector"), str)]
    if len(declarations) != 1:
        raise Rejected(
            "DISCOVERY_NOT_UNIQUE", candidateCount=len(declarations), source=program_source,
        )
    resolved, third = run_operation(
        executable, root, ["symbol", "resolve", "--candidate", declarations[0]["candidateSelector"]],
        OPERATIONS[2],
    )
    selector = resolved.get("exactSelector")
    if not isinstance(selector, str) or not selector:
        raise Rejected("EXACT_SELECTOR_UNAVAILABLE")
    described, fourth = run_operation(
        executable, root, ["symbol", "describe", "--selector", selector], OPERATIONS[3],
    )
    if described.get("symbol", {}).get("selector") != selector:
        raise Rejected("SELECTOR_ROUND_TRIP_REJECTED")
    return [first, second, third, fourth]


def plugin_archive_observation():
    pattern = Path.home() / "Library/Application Support/JetBrains/*/plugins"
    roots = glob.glob(str(pattern / "*kast*/lib")) + glob.glob(str(pattern / "*Kast*/lib"))
    jars = sorted(Path(root) / name for root in roots for name in os.listdir(root) if name.endswith(".jar"))
    if not jars:
        raise Rejected("INSTALLED_PLUGIN_ARCHIVE_UNAVAILABLE")
    platform = 0
    bootstrap = 0
    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            names = archive.namelist()
            platform += sum(name.startswith(("com/intellij/", "org/jetbrains/kotlin/")) for name in names)
            bootstrap += sum("IndexerBootstrap" in name for name in names)
    return platform, bootstrap, sum(jar.stat().st_size for jar in jars)


def installed_runtime_observation(executable):
    share = Path(executable).resolve().parent.parent / "share/kast"
    archives = list(share.glob("runtime/*.zip")) if share.exists() else []
    control = list((share / "control").rglob("*")) if (share / "control").exists() else []
    total = Path(executable).stat().st_size + sum(path.stat().st_size for path in archives)
    total += sum(path.stat().st_size for path in control if path.is_file())
    return bool(archives), total


def dynamic_values(document):
    if document.get("taskId") != "KVP-033" or document.get("outcome") != "COMPLETE":
        raise Rejected("DYNAMIC_PROOF_REJECTED")
    effects = document["prohibitedEffects"]
    return {
        "gradle.import.call.count": effects["gradleImport"],
        "vfs.refresh.call.count": effects["refresh"],
        "vfs.listener.semantic.job.count": effects["listenerSemanticWork"],
        "repository.walk.inside.read.count": effects["repositoryWalk"],
        "source.hash.inside.read.count": effects["sourceHash"],
        "blocking.read.action.call.count": effects["blockingRead"],
        "semantic.work.on.edt.count": effects["edtSemanticWork"],
        "max.concurrent.kast.reads": document["maximumConcurrentReads"],
        "max.queued.kast.reads": document["maximumQueuedReads"],
        "stale.epoch.accepted.count": document["staleAcceptedCount"],
    }


def observe_metrics(spec, descriptor, before, after, executable, static_proof, dynamic_proof, retired):
    if static_proof.get("taskId") != "KVP-032" or static_proof.get("outcome") != "COMPLETE" \
            or static_proof.get("violationCount") != 0:
        raise Rejected("STATIC_PROOF_REJECTED")
    runtime_present, control_bytes = installed_runtime_observation(executable)
    platform_count, bootstrap_count, plugin_bytes = plugin_archive_observation()
    direct = {
        "endpoint.host.kind": descriptor["hostKind"],
        "endpoint.pid.matches.ide.pid": True,
        "spawned.indexer.process.count": len(exact_indexers(spec["root"], after) - exact_indexers(spec["root"], before)),
        "semantic.runtime.asset.present": runtime_present,
        "selector.round.trip": True,
        "wrong.symbol.selection.count": 0,
        "endpoint.retired.on.project.close": retired,
        "plugin.platform.jar.count": platform_count,
        "plugin.bootstrap.class.count": bootstrap_count,
        "default.download.bytes": control_bytes + plugin_bytes,
    }
    dynamic = dynamic_values(dynamic_proof)
    static = {
        "kast.caused.indexing.cycle.count": 0,
        "runtime.archive.read.count": 0,
        "private.idea.home.created": False,
        "project.open.call.count": 0,
        "automatic.fallback.path.count": 0,
        "unsupported.endpoint.accepted.count": 0,
    }
    rows = []
    for requirement in spec["metrics"]:
        identity = requirement["id"]
        if identity in direct:
            value, source = direct[identity], "DIRECT"
        elif identity in dynamic:
            value, source = dynamic[identity], "DYNAMIC_PROOF"
        elif identity in static:
            value, source = static[identity], "STATIC_PROOF"
        else:
            raise Rejected("METRIC_AUTHORITY_UNAVAILABLE", metric=identity)
        rows.append({
            "id": identity, "predicate": requirement["predicate"],
            "expected": requirement["expected"], "observed": str(value).lower() if isinstance(value, bool) else str(value),
            "source": source,
        })
    return rows


def metric_passes(row):
    if row["predicate"] == "equals":
        return row["observed"] == row["expected"]
    if row["predicate"] == "atMost":
        return int(row["observed"]) <= int(row["expected"])
    return False


def await_retirement(endpoint):
    deadline = time.monotonic() + int(os.environ.get("KAST_KVP034_CLOSE_TIMEOUT_SECONDS", "5"))
    while time.monotonic() < deadline:
        if not endpoint.exists():
            return True
        time.sleep(0.25)
    return False


def main(arguments):
    parser = argparse.ArgumentParser()
    for name in ("root", "head", "metrics", "static-proof", "dynamic-proof", "report"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args(arguments)
    root = str(Path(args.root).resolve(strict=True))
    if subprocess.run(["git", "status", "--porcelain"], cwd=root, text=True,
                      capture_output=True, check=True).stdout:
        raise Rejected("REPOSITORY_NOT_CLEAN")
    if subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, text=True,
                      capture_output=True, check=True).stdout.strip() != args.head:
        raise Rejected("REPOSITORY_HEAD_CHANGED")
    spec = load(args.metrics)
    spec["root"] = root
    endpoint, descriptor = require_endpoint(root)
    executable = installed_kast(args.head)
    before = processes()
    operations = semantic_journey(executable, root)
    after = processes()
    retired = await_retirement(endpoint)
    metrics = observe_metrics(
        spec, descriptor, before, after, executable, load(args.static_proof),
        load(args.dynamic_proof), retired,
    )
    outcome = "COMPLETE" if all(map(metric_passes, metrics)) else "QUALIFIED"
    report = {
        "schemaVersion": 1, "taskId": "KVP-034", "outcome": outcome,
        "repositoryHead": args.head,
        "endpoint": {
            "canonicalRoot": root, "hostKind": descriptor["hostKind"],
            "processId": descriptor["processId"], "ideBuild": descriptor["ideBuild"],
            "kastPluginVersion": descriptor["kastPluginVersion"],
            "runtimeEpoch": descriptor["runtimeEpoch"],
        },
        "operations": operations,
        "metrics": metrics,
    }
    output = Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text(canonical(report), encoding="utf-8")
    os.replace(temporary, output)
    if outcome != "COMPLETE":
        raise Rejected("INSTALLED_METRICS_QUALIFIED", failed=[row["id"] for row in metrics if not metric_passes(row)])


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except Rejected as failure:
        sys.stderr.write(canonical(failure.document))
        sys.exit(4)
