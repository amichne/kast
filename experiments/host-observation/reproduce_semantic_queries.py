#!/usr/bin/env python3
"""Opt-in native reproduction. Setup/import and read-only replay are separate commands."""
import argparse
import base64
from collections import Counter
from dataclasses import dataclass, replace
from enum import Enum
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

from run_host_acceptance import wait_for

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
FIXTURE = HERE / "semantic-fixture"
FIELDS = ["NAME", "LOCATION", "SIGNATURE"]


class Finding(str, Enum):
    REPRODUCED = "reproduced"
    NOT_REPRODUCED = "not reproduced"
    CHANGED = "behavior changed"
    BLOCKED = "blocked"


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inventory(directory):
    return {p.relative_to(directory).as_posix(): digest(p) for p in sorted(directory.rglob("*"))
            if p.is_file() and not any(x in p.relative_to(directory).parts for x in ("build", ".gradle", ".idea", ".kotlin"))}


def fresh(path):
    path.mkdir(mode=0o700, parents=True, exist_ok=False)
    return path.resolve(strict=True)


def capture(command, cwd, stdin="", timeout=60):
    started = time.monotonic_ns()
    try:
        p = subprocess.run([str(x) for x in command], cwd=cwd, input=stdin, text=True,
                           capture_output=True, timeout=timeout)
        return dict(command=[str(x) for x in command], cwd=str(cwd), stdin=stdin,
                    exitCode=p.returncode, stdout=p.stdout, stderr=p.stderr,
                    elapsedNanos=time.monotonic_ns() - started, outcome="completed")
    except subprocess.TimeoutExpired as failure:
        # Harness timeout is explicitly different from a native semantic deadline.
        def decoded(value):
            return value.decode(errors="replace") if isinstance(value, bytes) else value or ""
        return dict(command=[str(x) for x in command], cwd=str(cwd), stdin=stdin,
                    outcome="harness-timeout", stdout=decoded(failure.stdout),
                    stderr=decoded(failure.stderr), elapsedNanos=time.monotonic_ns() - started)


def setup(args):
    root = args.fixture.absolute()
    if root.exists():
        raise ValueError("SETUP_REQUIRES_FRESH_DIRECTORY")
    shutil.copytree(FIXTURE, root)
    root = root.resolve(strict=True)
    shutil.copy(REPO / "gradlew", root / "gradlew")
    shutil.copytree(REPO / "gradle/wrapper", root / "gradle/wrapper")
    # A fixed selected package; only unrelated Gradle projects/declarations vary.
    noise = [f"noise{i}" for i in range(args.noise_modules)]
    (root / "settings.gradle.kts").write_text('rootProject.name = "kast-semantic-fixture"\ninclude(' +
        ", ".join(json.dumps(":" + name) for name in ["core", "logging", *noise]) + ")\n")
    for name in noise:
        (root / name).mkdir(exist_ok=True)
    folder = root / "noise0/src/main/kotlin/repro/noise"
    folder.mkdir(parents=True)
    # Unique names scale independently from module count; fixed file batches keep compilation small.
    for first in range(0, args.noise_names, 200):
        (folder / f"Noise{first}.kt").write_text("package repro.noise\n" + "\n".join(
            (f"class UnrelatedName{i:06d}" if args.noise_kind == "class" else f"fun unrelatedFunction{i:06d}() = Unit") for i in range(first, min(first + 200, args.noise_names))) + "\n")
    logger = root / "logging/src/main/kotlin/repro/logging/FixtureLogger.kt"
    extra = {"kotlin": "", "java": "; java.lang.System.nanoTime()", "outside": "; repro.external.outsideHelper()"}[args.callee]
    logger.write_text(logger.read_text().replace("fun trace() { loggerFunction() }", "fun trace() { loggerFunction()" + extra + " }"))
    if args.java_references:
        java = root / "logging/src/main/java/repro/logging/JavaManagerUse.java"
        java.parent.mkdir(parents=True)
        java.write_text("package repro.logging;\npublic class JavaManagerUse {\n    public FixtureLoggerManager manager() { return new FixtureLoggerManager(); }\n}\n")
    (root / "gradle.properties").write_text("org.gradle.jvmargs=-Xmx1024m\norg.gradle.workers.max=2\n")
    write(root / "fixture-parameters.json", dict(noiseModules=args.noise_modules, noiseNames=args.noise_names,
          callee=args.callee, javaReferences=args.java_references, noiseKind=args.noise_kind, gradleProjectCount=3 + args.noise_modules))
    evidence = fresh(args.output)
    write(evidence / "fixture.json", dict(root=str(root), sourceHashes=inventory(root)))
    result = capture([root / "gradlew", "classes", "testClasses"], root, timeout=600)
    write(evidence / "compile.json", result)
    print(json.dumps(dict(fixture=str(root), compilation=result.get("exitCode"),
                         next="Explicitly import this Gradle project into IDEA, then pin and replay.")))
    return 0 if result.get("exitCode") == 0 else 2


def pin(args):
    output = fresh(args.output)
    cli = args.cli.resolve(strict=True)
    launcher = args.idea_contents / "MacOS/idea"
    # Reuse the hosted acceptance original-process admission; no replacement worker or launch fallback.
    rows = subprocess.check_output(["ps", "-ww", "-axo", "pid=,command="], text=True).splitlines()
    pids = [int(row.split(maxsplit=1)[0]) for row in rows
            if len(row.split(maxsplit=1)) == 2 and row.split(maxsplit=1)[1] == str(launcher)]
    if len(pids) != 1:
        raise ValueError("EXACT_RUNNING_HOST_UNAVAILABLE")
    request = dict(project=str(args.fixture.resolve(strict=True)), hostPid=pids[0])
    write(output / "input.json", request)
    script = (HERE / "semantic-reproduction-pin.kts.template").read_text().replace(
        "@INPUT_BASE64@", base64.b64encode(str(output / "input.json").encode()).decode())
    (output / "pin.kts").write_text(script)
    write(output / "script-process.json", capture([launcher, "ideScript", output / "pin.kts"], REPO))
    wait_for("native pin receipt", lambda: (output / "host.json").exists(), seconds=45)
    host = json.loads((output / "host.json").read_text())
    version = capture([cli, "--version"], args.fixture)
    schema = capture([cli, "--schema"], args.fixture)
    write(output / "version-process.json", version)
    write(output / "schema-process.json", schema)
    if schema.get("exitCode") != 0:
        raise ValueError("INSTALLED_SCHEMA_UNAVAILABLE")
    (output / "installed-schema.json").write_text(schema["stdout"])
    plugin_path = Path(host["plugin"]["path"])
    plugin_files = {str(p): digest(p) for p in sorted((plugin_path / "lib").glob("*.jar"))}
    source = args.source_tree.resolve(strict=True) if args.source_tree else REPO
    patch = capture(["git", "diff", "--binary", "HEAD"], source)
    (output / "source.patch").write_text(patch["stdout"])
    source_head = capture(["git", "rev-parse", "HEAD"], source)["stdout"].strip()
    untracked_paths = capture(["git", "ls-files", "--others", "--exclude-standard"], source)["stdout"].splitlines()
    untracked = {name: digest(source / name) for name in untracked_paths if (source / name).is_file()}
    metadata = dict(schemaVersion=1, cli=dict(requested=str(args.cli), executable=str(cli),
        sha256=digest(cli), version=version["stdout"].strip(),
        jars={str(p): digest(p) for p in sorted((cli.parent.parent / "lib").glob("*.jar"))}),
        plugin=dict(native=host["plugin"], jars=plugin_files), host=host,
        installedSchemaSha256=digest(output / "installed-schema.json"),
        source=dict(path=str(source), commit=source_head, patchSha256=digest(output / "source.patch"), untrackedHashes=untracked,
                    runtimeCorrespondence="unproven unless independently matched to a release artifact or build receipt"),
        fixture=dict(root=str(args.fixture.resolve(strict=True)), hashes=inventory(args.fixture)),
        limits=dict(native=host["limits"], diagnosticAvailability=host["diagnosticAvailability"]))
    write(output / "pin.json", metadata)
    print(output / "pin.json")
    return 0 if host["model"]["smart"] and host["model"]["gradleProjectCount"] > 0 else 2


@dataclass(frozen=True)
class Case:
    name: str
    source: dict
    identities: tuple[str, ...] = ()
    steps: tuple[dict, ...] = ()
    select: tuple[str, ...] = tuple(FIELDS)
    reported: str = "complete"
    occurrences: str = ""
    tokens: tuple[str, ...] = ()
    schema_valid: bool = True
    unique_tokens: bool = True
    issued: tuple[dict, ...] = ()

    def request(self):
        return dict(type="QUERY", **{"from": self.source}, steps=list(self.steps), select=list(self.select))


def search(name, scope=None, match="EXACT"):
    return dict(type="SEARCH", query=name, match=match, **({"scope": scope} if scope else {}))


def scope(kind, value, containment="RECURSIVE", source_sets=None):
    return dict(type=kind, value=value, containment=containment,
                **({"sourceSets": source_sets} if source_sets else {}))


def cases(expected, parameters=None):
    ids = lambda *keys: tuple(expected["declarations"][key][1] for key in keys)
    core = scope("DIRECTORY", "core/src/main/kotlin/repro/core", "DIRECT")
    alias = scope("PACKAGE", "repro.core", "DIRECT")
    logging = scope("DIRECTORY", "logging", "RECURSIVE")
    result = [
        Case("all-logging-recursive", dict(type="ALL", scope=logging), tuple(expected["loggingRecursive"]), reported="budget-exceeded"),
        Case("all-core-direct", dict(type="ALL", scope=core), tuple(expected["coreDirect"]), reported="budget-exceeded"),
        Case("all-core-alias-direct", dict(type="ALL", scope=alias, kinds=["TYPE_ALIAS"]), ids("alias"), reported="budget-exceeded"),
    ]
    # Identical selected scopes and names across the unrelated-load experiments.
    for label, selected, name, wanted in (("logging", logging, "FixtureLogger", ids("logger", "manager")),
        ("core", core, "TraceLabel", ids("alias")), ("core-alias", alias, "TraceLabel", ids("alias"))):
        for match in ("EXACT", "FUZZY"):
            result.append(Case(f"{label}-{match.lower()}", search(name, selected, match),
                               wanted if match == "FUZZY" else wanted[:1]))
    for key in ("logger", "helper", "boolean", "alias", "manager", "unused", "trace", "test", "child", "base"):
        result.append(Case("exact-" + key, search(expected["declarations"][key][1].split(".")[-1]), ids(key)))
    for kind, selected in (("DIRECTORY", "logging/src/main/kotlin/repro/logging"), ("PACKAGE", "repro.logging")):
        for containment in ("DIRECT", "RECURSIVE"):
            result.append(Case(f"{kind.lower()}-{containment.lower()}", search("ChildMarker", scope(kind, selected, containment)),
                               ids("child") if containment == "RECURSIVE" else ()))
        result.append(Case(f"{kind.lower()}-foreign", search("FixtureLogger", scope(kind,
            "core/src/main/kotlin/repro/core" if kind == "DIRECTORY" else "repro.core")), ()))
    for source_set in ("main", "test"):
        result.append(Case("source-set-" + source_set, search("TestOnlyLogger", scope("DIRECTORY", ".", source_sets=[source_set])),
                           ids("test") if source_set == "test" else ()))
    for visibility in ("PRIVATE", "PUBLIC"):
        result.append(Case("visibility-" + visibility.lower(), search("loggerFunction"), ids("helper") if visibility == "PRIVATE" else (),
                           steps=({"type": "FILTER", "visibility": [visibility]},)))
    result.append(Case("distinct-five", search("sharedOperation"), tuple(expected["sameName"]), steps=({"type": "DISTINCT"},)))
    for relation in ("REFERENCES", "CALLERS"):
        result.append(Case("helper-" + relation.lower(), search("loggerFunction"), tuple(expected["helperCallers"]),
                           steps=({"type": "EXPAND", "relation": relation},), occurrences="helperOccurrences"))
    result.append(Case("alias-type-uses", search("TraceLabel"), tuple(expected["aliasUses"]),
                       steps=({"type": "EXPAND", "relation": "TYPE_USES"},), occurrences="aliasOccurrences"))
    for relation in ("IMPLEMENTATIONS", "INHERITORS"):
        result.append(Case("sink-" + relation.lower(), search("FixtureSink"), tuple(expected["descendants"]),
                           steps=({"type": "EXPAND", "relation": relation},)))
    result.append(Case("sink-overrides", search("transmit", scope("PACKAGE", "repro.hierarchy")), tuple(expected["overrides"]),
        steps=({"type": "EXPAND", "relation": "OVERRIDES"}, {"type": "DISTINCT"})))
    for label, selected in (("default", None), ("directory", scope("DIRECTORY", "logging")), ("package", scope("PACKAGE", "repro.logging"))):
        callee_ids = ids("helper")
        if (parameters or {}).get("callee") == "outside":
            # Public schema: discovery scope does not restrict expansion destinations.
            callee_ids += ("repro.external.outsideHelper",)
        result.append(Case("trace-callees-" + label, search("trace", selected), callee_ids,
                           steps=({"type": "EXPAND", "relation": "CALLEES"},), reported="relation-incomplete-one"))
    result.append(Case("manager-references", search("FixtureLoggerManager"), (),
                       steps=({"type": "EXPAND", "relation": "REFERENCES"},), reported="relation-incomplete-zero"))
    result.append(Case("unused-references", search("UnusedMarker"), (), steps=({"type": "EXPAND", "relation": "REFERENCES"},)))
    result.append(Case("invalid-reference", dict(type="REFS", refs=["exact:v3:NOT_ISSUED"]), reported="malformed-reference"))
    result.append(Case("invalid-reference-syntax", dict(type="REFS", refs=["not-a-reference"]), reported="invalid-arguments", schema_valid=False))
    return result


def expected_occurrences(root, definition):
    lines = (root / definition["file"]).read_text().splitlines(keepends=True)
    result = []
    positions = definition.get("positions", [(line, 1) for line in definition.get("lines", [])])
    for line, ordinal in positions:
        column = -1
        for _ in range(ordinal):
            column = lines[line - 1].index(definition["text"], column + 1)
        start = sum(len(s) for s in lines[:line - 1]) + column
        result.append((str(root / definition["file"]), start, start + len(definition["text"])))
    return sorted(result)


def assess(case, response, root, expected):
    items = response.get("items", [])
    actual_ids = [(item.get("signature") or {}).get("qualifiedIdentity") for item in items]
    checks = {}
    if "SIGNATURE" in case.select:
        checks["exactIdentities"] = Counter(actual_ids) == Counter(case.identities)
    checks["resultCount"] = len(items) == len(case.identities)
    checks["exactReferences"] = all(item.get("type") == "exact-symbol" and
        item.get("ref", {}).get("kind") == "exact-symbol" and item["ref"]["token"].startswith("exact:v") for item in items)
    if case.unique_tokens:
        checks["distinctTokens"] = len({item["ref"]["token"] for item in items}) == len(items)
    if case.tokens:
        checks["opaqueReferencesPreserved"] = sorted(item["ref"]["token"] for item in items) == sorted(case.tokens)
    if case.issued:
        # Issuer output is used only to check preservation, never as the semantic oracle.
        original = {item["ref"]["token"]: item for item in case.issued}
        checks["issuedProjectionsPreserved"] = len(items) == len(original) and all(
            item["ref"]["token"] in original and all(item.get(field.lower()) ==
                original[item["ref"]["token"]].get(field.lower()) for field in case.select) for item in items)
    for field_name in FIELDS:
        checks["projection-" + field_name] = all((item.get(field_name.lower()) is not None) == (field_name in case.select) for item in items)
    locations = []
    for item in items:
        location = item.get("location")
        if location:
            p = Path(location["file"])
            start, end = location["range"]["startInclusive"], location["range"]["endExclusive"]
            valid = p.is_relative_to(root) and p.is_file() and 0 <= start < end <= len(p.read_text())
            declaration = next((v for v in expected["declarations"].values() if v[1] == (item.get("signature") or {}).get("qualifiedIdentity")), None)
            if valid and declaration:
                valid = p == root / declaration[0] and p.read_text()[start:end].startswith(declaration[2])
            locations.append(valid)
    checks["sourceRanges"] = all(locations)
    connections = [c for item in items for c in item.get("connections", [])]
    if connections:
        checks["exactConnectionCoverage"] = all(c.get("coverage") == "exact-compiler-confirmed" and
            c.get("provenance") == "k2-authored-source" for c in connections)
        checks["compilerEndpoints"] = all(endpoint.get("compilerEvidence", {}).get("identity") and
            endpoint.get("compilerEvidence", {}).get("signature", {}).get("qualifiedIdentity") == endpoint.get("qualifiedIdentity")
            for c in connections for endpoint in (c["source"], c["target"]))
    if case.occurrences:
        target = expected["declarations"][expected[case.occurrences].get("target", "alias" if case.occurrences == "aliasOccurrences" else "helper")][1]
        checks["orientedConnections"] = Counter((c["source"].get("qualifiedIdentity"), c["target"].get("qualifiedIdentity")) for c in connections) == Counter((identity, target) for identity in case.identities)
    occurrences = sorted((c["occurrence"]["file"], c["occurrence"]["range"]["startInclusive"],
                          c["occurrence"]["range"]["endExclusive"]) for c in connections if c.get("occurrence"))
    if case.occurrences:
        checks["exactOccurrences"] = occurrences == expected_occurrences(root, expected[case.occurrences])
    qualification = response.get("qualification", {})
    limitations = qualification.get("limitations", [])
    status = response.get("status", response.get("outcome"))
    if case.reported == "complete":
        matches = status == "complete" and not limitations and not response.get("failures") and all(checks.values())
    elif case.reported.startswith("relation-incomplete"):
        minimum = 1 if case.reported.endswith("one") else 0
        matches = status == "qualified" and len(items) == minimum and qualification.get("knownMinimum") == minimum and "relation-incomplete" in limitations and all(checks.values())
    elif case.reported == "budget-exceeded":
        matches = response.get("failure") == "BUDGET_EXCEEDED" and response.get("stage") == "SEMANTIC_READ"
    elif case.reported == "malformed-reference":
        matches = status == "rejected" and not items and response.get("rejection", {}).get("type") == "reference-rejected" and response["rejection"].get("reason") == "malformed"
    else:
        matches = response.get("failure") == "INVALID_ARGUMENTS" or response.get("reason") in {"invalid-arguments", "arguments-rejected"}
    stale = response.get("rejection", {}).get("reason") == "stale-authority"
    finding = Finding.BLOCKED if stale else Finding.REPRODUCED if matches else Finding.NOT_REPRODUCED
    return dict(finding=finding.value,
        **({"prerequisite": "Stable host and project epoch between exact discovery and REFS"} if stale else {}),
        assertions=checks, expectedIdentities=list(case.identities), actualIdentities=actual_ids,
        itemCount=len(items), occurrenceCount=len(occurrences), occurrences=occurrences,
        aggregateCoverage=dict(status=status, qualification=qualification),
        connectionCoverage=[dict(meaning=c.get("meaning"), coverage=c.get("coverage"), provenance=c.get("provenance")) for c in connections],
        live=response.get("live"), failures=response.get("failures", []))


def compile_provider(cli, directory):
    jars = sorted((cli.parent.parent / "lib").glob("*.jar"))
    owners = [p for p in jars if p.name.startswith("app-server-")]
    if len(owners) != 1:
        raise ValueError("EXACT_PROVIDER_JAR_UNAVAILABLE")
    classpath = os.pathsep.join(str(p) for p in jars)
    target = directory / "provider.jar"
    result = capture(["kotlinc", HERE / "SemanticReproductionProvider.kt", "-jvm-target", "25", "-classpath",
        classpath, "-Xfriend-paths=" + str(owners[0]), "-d", target], REPO, timeout=120)
    write(directory / "provider-compile.json", result)
    if result.get("exitCode") != 0:
        raise ValueError("PROVIDER_HARNESS_COMPILATION_REJECTED")
    return ["java", "-classpath", str(target) + os.pathsep + classpath,
            "io.github.amichne.kast.appserver.manual.SemanticReproductionProvider"]


def replay(args):
    import jsonschema
    output = fresh(args.output)
    root = args.fixture.resolve(strict=True)
    cli = args.cli.resolve(strict=True)
    expected = json.loads((FIXTURE / "expected.json").read_text())
    pinned = json.loads(args.pin.read_text())
    schema = json.loads((args.pin.parent / "installed-schema.json").read_text())
    query_schema = next(t["inputSchema"] for t in schema["serverProjection"]["hostedBootstrap"]["tools"] if t["name"] == "query")
    if not pinned["host"]["model"]["smart"] or pinned["host"]["model"]["gradleProjectCount"] == 0:
        raise ValueError("IMPORTED_SMART_MODEL_NOT_PINNED")
    if pinned["cli"]["executable"] != str(cli) or pinned["cli"]["sha256"] != digest(cli):
        raise ValueError("PINNED_CLI_MISMATCH")
    if pinned["fixture"]["root"] != str(root) or pinned["fixture"]["hashes"] != inventory(root):
        raise ValueError("PINNED_FIXTURE_MISMATCH")
    write(output / "pin.json", pinned)
    write(output / "expected.json", expected)
    provider = compile_provider(cli, output) if args.surface == "provider" else None
    receipts, seeds, first_live = [], {}, None

    def invoke(case, phase):
        nonlocal first_live
        directory = fresh(output / (case.name + "-" + phase))
        request = case.request()
        log_before = args.idea_log.stat() if args.idea_log else None
        validation = list(jsonschema.Draft202012Validator(query_schema).iter_errors(request))
        if bool(validation) == case.schema_valid:
            raise ValueError("UNEXPECTED_SCHEMA_ADMISSION")
        write(directory / "schema-validation.json", dict(expectedValid=case.schema_valid, failures=len(validation)))
        write(directory / "request.json", request)
        if provider:
            result = capture([*provider, cli, root, directory / "request.json", directory / "provider.json"], root, timeout=30)
            native = [json.loads(p.read_text()) for p in sorted(directory.glob("process-*.json"))]
            queries = [p for p in native if p.get("arguments") == ["query", "run"]]
            raw = queries[0] if len(queries) == 1 else {}
            document = raw.get("stdout") or raw.get("stderr")
        else:
            result = capture([cli, "query", "run"], root, json.dumps(request), timeout=15)
            raw = result
            document = result.get("stdout") or result.get("stderr")
        write(directory / "process.json", result)
        diagnostics = []
        if args.idea_log:
            log_after = args.idea_log.stat()
            if log_after.st_ino == log_before.st_ino and 0 <= log_after.st_size - log_before.st_size <= 2 * 1024 * 1024:
                with args.idea_log.open("rb") as log:
                    log.seek(log_before.st_size)
                    for line in log.read(log_after.st_size - log_before.st_size).splitlines():
                        if b"kast_semantic_read " in line:
                            diagnostics.append(json.loads(line.split(b"kast_semantic_read ", 1)[1]))
                write(directory / "native-diagnostics.json", diagnostics)
            else:
                write(directory / "native-diagnostics-gap.json", dict(reason="LOG_ROTATED_OR_BOUND_EXCEEDED"))
        try:
            response = json.loads(document) if document else None
        except json.JSONDecodeError:
            response = None
        if response is None and provider and not case.schema_valid and (directory / "provider.json").exists():
            provider_response = json.loads((directory / "provider.json").read_text())
            receipt = dict(case=case.name, phase=phase, surface="provider", canonicalInvoked=False,
                finding=(Finding.REPRODUCED if provider_response.get("failureCode") == "INVALID_ARGUMENTS" else Finding.NOT_REPRODUCED).value,
                providerRejection=provider_response, elapsedNanos=result["elapsedNanos"],
                prerequisite="none; argument rejection precedes the CLI by contract")
        elif response is None:
            receipt = dict(case=case.name, phase=phase, finding=Finding.BLOCKED.value,
                           prerequisite="One canonical CLI response from the admitted host", processOutcome=result["outcome"])
        else:
            write(directory / "response.json", response)
            receipt = dict(case=case.name, phase=phase, reported=case.reported, surface=args.surface,
                exitCode=raw.get("exitCode"), elapsedNanos=raw.get("elapsedNanos"),
                **assess(case, response, root, expected))
            receipt["nativeDiagnosticRecords"] = len(diagnostics)
            receipt["diagnosticCorrelation"] = "one-record" if len(diagnostics) == 1 else "unavailable" if not diagnostics else "ambiguous"
            live = response.get("live")
            if len(diagnostics) == 1 and live:
                correlation = diagnostics[0].get("correlation", {})
                receipt["diagnosticCorrelation"] = "matched" if correlation.get("host") == live["host"] and correlation.get("epoch") == live["epoch"] else "mismatched"
            if live:
                if first_live is None:
                    first_live = live
                receipt["sameHostAndEpoch"] = live == first_live
                if live != first_live:
                    receipt["finding"] = Finding.BLOCKED.value
                    receipt["prerequisite"] = "Stable host and project epoch throughout this replay; finish setup/import before repinning"
            if provider and (directory / "provider.json").exists():
                envelope = json.loads((directory / "provider.json").read_text()).get("providerEnvelope", {})
                receipt["providerPreservesCanonicalResponse"] = envelope.get("document") == response
            if case.name.startswith("exact-") and response.get("status") == "complete" and len(response.get("items", [])) == 1:
                seeds[case.name[6:]] = response["items"][0]
        write(directory / "receipt.json", receipt)
        receipts.append(receipt)
        write(output / "receipts.json", receipts)
        print(f"{case.name} {phase}: {receipt['finding']}", flush=True)
        return response

    for case in cases(expected, json.loads((root / "fixture-parameters.json").read_text())):
        if args.verify_corrections and (case.reported == "budget-exceeded" or case.reported.startswith("relation-incomplete")):
            identities = case.identities
            if case.name == "manager-references" and json.loads((root / "fixture-parameters.json").read_text()).get("javaReferences"):
                identities = ("repro.logging.JavaManagerUse.manager",) * 2
                case = replace(case, unique_tokens=False, occurrences="managerJavaOccurrences")
            case = replace(case, reported="complete", identities=identities)
        if args.cases and not any(case.name.startswith(prefix) for prefix in args.cases.split(",")):
            continue
        for repeat in range(args.repeats):
            response = invoke(case, "first" if repeat == 0 else f"warm-{repeat}")
            # A separate, successful exact query must demonstrate the same owner remains usable.
            if response and (response.get("status") != "complete" or response.get("failure")):
                invoke(Case(case.name + "-recovery", search("FixtureLogger"),
                            (expected["declarations"]["logger"][1],)), f"after-{repeat}")
    if not args.cases:
        for name, keys in (("refs-roundtrip", ("logger",)), ("refs-multiple", ("logger", "alias")),
                           ("refs-deduplicate", ("logger", "logger"))):
            if not all(key in seeds for key in keys):
                receipts.append(dict(case=name, finding=Finding.BLOCKED.value, prerequisite="Successful exact discovery of " + ", ".join(keys)))
                continue
            unique = tuple(dict.fromkeys(keys))
            tokens = tuple(seeds[key]["ref"]["token"] for key in unique)
            invoke(Case(name, dict(type="REFS", refs=[seeds[key]["ref"]["token"] for key in keys]),
                        tuple(expected["declarations"][key][1] for key in unique), steps=({"type": "DISTINCT"},),
                        tokens=tokens, issued=tuple(seeds[key] for key in unique)), "first")
        if "helper" in seeds:
            for projection in FIELDS:
                token = seeds["helper"]["ref"]["token"]
                invoke(Case("projection-" + projection.lower(), dict(type="REFS", refs=[token]),
                    (expected["declarations"]["helper"][1],), select=(projection,), tokens=(token,), issued=(seeds["helper"],)), "first")
    write(output / "receipts.json", receipts)
    if pinned["fixture"]["hashes"] != inventory(root):
        raise ValueError("FIXTURE_CHANGED_DURING_REPLAY")
    write(output / "summary.json", dict(schemaVersion=1, fixture=str(root), surface=args.surface,
        queryBudgets="unchanged production budgets", cachePolicy="no cache deletion or invalidation",
        counts=dict(Counter(r["finding"] for r in receipts)), pin=str(args.pin), receipts="receipts.json"))
    # Observational differences are successful measurements. Missing execution evidence is not.
    if any(r["finding"] == Finding.BLOCKED.value for r in receipts):
        return 2
    return 1 if args.verify_corrections and any(r["finding"] != Finding.REPRODUCED.value for r in receipts) else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("setup", help="Create and compile a fresh synthetic fixture; never imports it")
    create.add_argument("--fixture", type=Path, required=True)
    create.add_argument("--output", type=Path, required=True)
    create.add_argument("--noise-modules", type=int, default=1)
    create.add_argument("--noise-names", type=int, default=1)
    create.add_argument("--noise-kind", choices=("class", "function"), default="class")
    create.add_argument("--callee", choices=("kotlin", "java", "outside"), default="kotlin")
    create.add_argument("--java-references", action="store_true")
    create.set_defaults(run=setup)
    identify = commands.add_parser("pin", help="Read loaded plugin/model facts from an existing IDEA process")
    identify.add_argument("--idea-contents", type=Path, required=True)
    identify.add_argument("--source-tree", type=Path)
    identify.set_defaults(run=pin)
    play = commands.add_parser("replay", help="Read-only replay through the public CLI or production provider")
    play.add_argument("--pin", type=Path, required=True)
    play.add_argument("--surface", choices=("cli", "provider"), required=True)
    play.add_argument("--repeats", type=int, choices=range(1, 6), default=2)
    play.add_argument("--idea-log", type=Path, help="Collect only bounded appended kast_semantic_read records")
    play.add_argument("--cases", help="Optional comma-separated case-name prefixes for causal experiments")
    play.add_argument("--verify-corrections", action="store_true", help="Require complete, independently expected results for the demonstrated fix cases")
    play.set_defaults(run=replay)
    for command in (identify, play):
        command.add_argument("--fixture", type=Path, required=True)
        command.add_argument("--cli", type=Path, required=True)
        command.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "setup" and (not 1 <= args.noise_modules <= 1500 or not 0 <= args.noise_names <= 100000):
        parser.error("noise-modules must be 1..1500; noise-names must be 0..100000")
    return args.run(args)


if __name__ == "__main__":
    raise SystemExit(main())
