"""Exact target and occurrence oracle for the imported public declaration-binding fixture."""
from dataclasses import dataclass
import json
import sys


@dataclass(frozen=True)
class BindingCase:
    use: str
    owner: str
    member: str
    relation: str
    target_text: str
    file: str = "src/main/kotlin/IdentityFixture.kt"


CASES = (
    BindingCase("genericRead", "Feed", "state", "references", "val state: StateFlow<T>"),
    BindingCase("stringRead", "Feed", "state", "references", "val state: StateFlow<T>"),
    BindingCase("starRead", "Feed", "state", "references", "val state: StateFlow<T>"),
    BindingCase("aliasRead", "Feed", "state", "references", "val state: StateFlow<T>"),
    BindingCase("inheritedRead", "Feed", "state", "references", "val state: StateFlow<T>"),
    BindingCase("inheritedOverrideRead", "OverrideFeed", "state", "references", "override val state: StateFlow<T>"),
    BindingCase("implementationRead", "StringFeed", "state", "references", "override val state: StateFlow<String>"),
    BindingCase("stringAccepts", "Feed", "accepts", "callers", "fun accepts(value: @UnsafeVariance T)"),
    BindingCase("inheritedAccepts", "Feed", "accepts", "callers", "fun accepts(value: @UnsafeVariance T)"),
    BindingCase("chooseText", "Overloads", "select", "callers", "fun select(value: String)"),
    BindingCase("chooseNumber", "Overloads", "select", "callers", "fun select(value: Int)"),
    BindingCase("nestedBinders", "Outer", "pair", "callers", "fun <U> pair(first: T, second: U)"),
    BindingCase("shadowText", "Overloads", "select", "callers", "fun select(value: String)", "shadow/src/main/kotlin/ShadowFixture.kt"),
    BindingCase("shadowNumber", "Overloads", "select", "callers", "fun select(value: Int)", "shadow/src/main/kotlin/ShadowFixture.kt"),
)


def prove_binding_edges(acceptance, timeout):
    # Import the existing harness lazily to avoid a second runtime/bootstrap owner.
    import enterprise_acceptance
    from topology_identity_diagnostic import DiagnosticEvidenceError, acceptance_command

    package = "kast.identity.fixture."
    selectors = {}
    graphs = {}
    passed = []
    for case in CASES:
        source_file = acceptance.workspace / case.file
        source = source_file.read_text()
        qid = package + case.owner + "." + case.member
        target_start = source.index(case.target_text)
        key = (case.file, qid, target_start, case.relation)
        if key not in selectors:
            discovery = acceptance_command(acceptance, "symbol", "discover", "--query", case.member,
                                          "--match", "exact-name", "--limit", "100", timeout=timeout)
            matches = []
            for candidate in enterprise_acceptance.declaration_candidates(discovery):
                result = acceptance_command(acceptance, "symbol", "inspect", "--candidate", candidate, timeout=timeout)
                symbol = result.get("symbol", {})
                if (result.get("status") == "complete" and symbol.get("qualifiedIdentity") == qid
                        and symbol.get("range", {}).get("startInclusive") == target_start
                        and symbol.get("file") == str(source_file)):
                    matches.append(symbol["selector"])
            if len(matches) != 1:
                raise DiagnosticEvidenceError(f"independent binding target is not exact: {case}: {matches}")
            selectors[key] = matches[0]
            result = acceptance_command(acceptance, "traversal", "run", "--selector", matches[0],
                                        "--relation", case.relation, "--maximum-depth", "1",
                                        "--maximum-results", "100", timeout=timeout)
            depth_only = result.get("qualification") == {
                "type": "terminal_incomplete", "limitations": ["depth-limit-reached"],
                "relationLimitations": [],
            }
            if result.get("status") != "complete" and not (result.get("status") == "qualified" and depth_only):
                raise DiagnosticEvidenceError(f"binding traversal is incomplete: {case}: {result}")
            graphs[key] = result["graph"]
        graph = graphs[key]
        nodes = {node["id"]: node for node in graph["nodes"]}
        use_start = source.index("fun " + case.use + "(") if case.use != "genericRead" else source.index("fun <T> genericRead(")
        use_end = source.index("\n", use_start)
        occurrence = source.index("." + case.member, use_start, use_end) + 1
        exact = []
        for edge in graph["edges"]:
            target = nodes[edge["target"]]["selector"] == selectors[key]
            use = nodes[edge["source"]].get("qualifiedIdentity") == package + case.use
            if target and use:
                if edge.get("meaning") != case.relation:
                    raise DiagnosticEvidenceError(f"binding relation differs: {case}: {edge['meaning']}")
                observed = edge.get("occurrence", {})
                if observed.get("file") != str(source_file) or observed.get("range") != {
                    "startInclusive": occurrence, "endExclusive": occurrence + len(case.member)
                }:
                    raise DiagnosticEvidenceError(f"binding occurrence differs: {case}: {edge}")
                exact.append(edge)
        if len(exact) != 1:
            raise DiagnosticEvidenceError(f"missing or duplicate exact binding edge: {case}: {graph}")
        passed.append(case.use)
        print(json.dumps({"stage": "topology-binding", "case": case.use, "outcome": "passed"}),
              file=sys.stderr, flush=True)
    forbidden = (
        ("substitutedDelegation", "Feed", "state", "val state: StateFlow<T>", "references"),
        ("inheritedOverrideRead", "Feed", "state", "val state: StateFlow<T>", "references"),
        ("implementationRead", "Feed", "state", "val state: StateFlow<T>", "references"),
        ("chooseText", "Overloads", "select", "fun select(value: Int)", "callers"),
        ("chooseNumber", "Overloads", "select", "fun select(value: String)", "callers"),
    )
    root_file = "src/main/kotlin/IdentityFixture.kt"
    source = (acceptance.workspace / root_file).read_text()
    for use, owner, member, text, relation in forbidden:
        key = (root_file, package + owner + "." + member, source.index(text), relation)
        graph = graphs[key]
        nodes = {node["id"]: node for node in graph["nodes"]}
        if any(nodes[edge["source"]].get("qualifiedIdentity") == package + use
               and nodes[edge["target"]]["selector"] == selectors[key] for edge in graph["edges"]):
            raise DiagnosticEvidenceError(f"wrong declaration accepted: {use} -> {text}")
        passed.append("reject:" + use + "->" + text)
    for file, forbidden_uses in (
        (root_file, {"shadowText", "shadowNumber"}),
        ("shadow/src/main/kotlin/ShadowFixture.kt", {"chooseText", "chooseNumber"}),
    ):
        for key, graph in graphs.items():
            if key[0] != file or key[1] != package + "Overloads.select":
                continue
            nodes = {node["id"]: node for node in graph["nodes"]}
            if any(nodes[edge["source"]].get("qualifiedIdentity") in {package + use for use in forbidden_uses}
                   for edge in graph["edges"]):
                raise DiagnosticEvidenceError("declaration binding crossed unrelated Gradle modules")
    for owner in ("GenericLeft", "GenericRight"):
        qid = package + owner + ".consume"
        discovery = acceptance_command(acceptance, "symbol", "discover", "--query", "consume",
                                      "--match", "exact-name", "--limit", "100", timeout=timeout)
        candidates = []
        for candidate in enterprise_acceptance.declaration_candidates(discovery):
            result = acceptance_command(acceptance, "symbol", "inspect", "--candidate", candidate, timeout=timeout)
            symbol = result.get("symbol", {})
            if symbol.get("qualifiedIdentity") == qid:
                candidates.append(symbol["selector"])
        if len(candidates) != 1:
            raise DiagnosticEvidenceError("intersection fixture origin is not independently exact")
        result = acceptance_command(acceptance, "traversal", "run", "--selector", candidates[0],
                                    "--relation", "callers", "--maximum-depth", "1",
                                    "--maximum-results", "100", timeout=timeout)
        graph = result.get("graph")
        if result.get("status") != "complete" or not isinstance(graph, dict) or graph.get("edges") != []:
            raise DiagnosticEvidenceError(f"intersection origin was admitted: {owner}")
        passed.append("reject:substitutedIntersection->" + owner)
    passed.append("reject:wrong-module")
    return passed
