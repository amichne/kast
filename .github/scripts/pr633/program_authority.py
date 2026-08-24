"""Closed repository-authority checks shared by the PR 633 verifier and tests."""

from __future__ import annotations

import re
import subprocess
from pathlib import Path
from typing import Any


_CLEAN_DEPENDENCIES = """001:;002:001;003:001;004:003;005:004;006:002,004,005;
007:003,005;008:007;009:007;010:009;011:010;023:007,010;012:011,023;013:007;
014:008,010;015:011,012,013,014,023;016:015;017:015,016;018:007,011,013,017;
019:018;020:010,011,012,013,019;021:006,020,023;022:021""".replace("\n", "")
EXPECTED_CLEAN_SLATE_DEPENDENCIES = {
    f"KCS-{task}": {f"KCS-{value}" for value in dependencies.split(",") if value}
    for task, dependencies in (row.split(":", 1) for row in _CLEAN_DEPENDENCIES.split(";"))
}
EXPECTED_INVARIANT_ENFORCERS = set("""WritePr633GateEvidenceTask operationRegistryAuthorityAcceptance
pr633ExactHeadCiAcceptance pr633MergeCandidateAcceptance pr633StackAcceptance topologyContractAcceptance
topologyCoverageAcceptance topologyInstalledProductAcceptance topologyPrerequisiteAcceptance
verifyChangePlanHasNoTopologyBuildAuthority verifyOperationRegistryAuthority verifyPr633Stack
verifyTopologyContractApi verifyTopologyOperationNames verifyTopologyTraversalNoK2 verifyTraversalHasNoTopologyBuildOrPublishAuthority""".split())
REQUIRED_GATE_AUTHORITIES = {
    "GATE-001": ("verifyCleanupMergedIntoMain", "cleanupMergedEvidenceFile"),
    "GATE-002": ("verifyPr633Stack",),
    "GATE-010": ("verifyOperationRegistryAuthority", '":protocol:registry:test"', '":protocol:wire:test"', '":cli:test"'),
    "GATE-020": ("verifyChangePlanHasNoTopologyBuildAuthority", "verifyTraversalHasNoTopologyBuildOrPublishAuthority", '":runtime:composition:test"'),
    "GATE-030": ("verifyInstalledTopologyJourneyReport", "validateInstalledTopologyJourneySchema", "verifyTopologyTraversalNoK2", '":evidence:sqlite:test"'),
    "GATE-040": ("verifyTopologyContractApi", "verifyGraphIndexInternal", "verifyTopologyOperationNames", '":topology:service:test"', "verifyKastModuleGraph", "verifyForbiddenEffects"),
    "GATE-050": ("verifyPr633Authorities", "verifyGeneratedCliReference", "verifyPublicDocs"),
    "GATE-060": ("topologyAcceptance", "runtimeDeliveryMvpAcceptance", "allSubprojectTests", "verifyKastModuleGraph", "verifyForbiddenEffects", "verifyNoLegacyArchitecture", "verifyRepositoryShape", "verifyGeneratedCliReference", "verifyPublicDocs", "verifyGitDiff"),
    "GATE-070": ("verifyPr633ProgramArtifacts", "pr633Gate060EvidenceFile", "exactHeadCiEvidenceFile", '"checkName" to "pr633-merge-candidate"', '"checkConclusion" to "success"'),
}
EXACT_RANGE_GIT_DIFF_CHECK = "git diff --check <resolved-origin-main-sha>...<resolved-head-sha>"


class VerificationFailure(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise VerificationFailure(message)


def verify_repository_sources(program: dict[str, Any], root: Path) -> None:
    missing: list[str] = []
    for source in program["sourceLedger"]:
        if source["revision"] != "repository-path":
            continue
        location = Path(source["location"])
        require(not location.is_absolute() and ".." not in location.parts, f"unsafe source path: {location}")
        if not (root / location).is_file():
            missing.append(location.as_posix())
    require(not missing, f"repository source ledger contains missing authorities: {missing}")


def verify_invariant_enforcers(program: dict[str, Any], root: Path) -> None:
    observed = {name for invariant in program["invariants"] for name in invariant["enforcedBy"]}
    require(observed == EXPECTED_INVARIANT_ENFORCERS, f"invariant enforcer set differs: {sorted(observed)}")
    source_paths = [
        root / "build.gradle.kts", root / "build-logic/src/main/kotlin/conventions/pr633-stack.gradle.kts",
        root / "build-logic/src/main/kotlin/conventions/pr633-topology.gradle.kts", root / "build-logic/src/main/kotlin/conventions/pr633-delivery.gradle.kts",
        root / "build-logic/src/main/kotlin/support/pr633/Pr633GateEvidenceSerialization.kt",
    ]
    sources = "\n".join(path.read_text(encoding="utf-8") for path in source_paths)
    registered = set(re.findall(r'tasks\.register(?:<[^>]+>)?\s*\(\s*"([^"]+)"', sources))
    if '"topologyCoverageAcceptance" to listOf(' in sources and "topologyAcceptanceChecks.forEach" in sources:
        registered.add("topologyCoverageAcceptance")
    if re.search(r"class\s+WritePr633GateEvidenceTask\b", sources):
        registered.add("WritePr633GateEvidenceTask")
    missing = sorted(EXPECTED_INVARIANT_ENFORCERS - registered)
    require(not missing, f"invariant enforcers are not registered authorities: {missing}")


def verify_gate_check_bindings(program: dict[str, Any], root: Path) -> None:
    for gate in program["gates"]:
        require(
            len(gate["checks"]) == len(gate["checkIds"]),
            f"{gate['id']} must give each configured check ID one description",
        )
    bindings: dict[str, list[str]] = {}
    for relative in (
        "build-logic/src/main/kotlin/conventions/pr633-stack.gradle.kts",
        "build-logic/src/main/kotlin/conventions/pr633-topology.gradle.kts",
        "build-logic/src/main/kotlin/conventions/pr633-delivery.gradle.kts",
    ):
        source = (root / relative).read_text(encoding="utf-8")
        for match in re.finditer(r'gateId\.set\("(GATE-[0-9]{3})"\)(.*?)(?=reportFile\.set)', source, re.DOTALL):
            gate_id, block = match.groups()
            check_list = re.search(r"checkIds\.set\(\s*listOf\((.*?)\)\s*,?\s*\)", block, re.DOTALL)
            require(check_list is not None, f"{gate_id} has no configured check IDs")
            require(gate_id not in bindings, f"{gate_id} has duplicate Gradle check bindings")
            bindings[gate_id] = re.findall(r'"([a-z][a-z0-9-]+)"', check_list.group(1))
            registration_start = source.rfind("tasks.register", 0, match.start())
            require(registration_start >= 0, f"{gate_id} is not inside a registered task")
            verify_gate_authority_tokens(gate_id, source[registration_start:match.end()])
    declared = {gate["id"]: gate["checkIds"] for gate in program["gates"]}
    require(bindings == declared, f"gate check bindings differ: configured={bindings}, declared={declared}")
    gate_060 = next(gate for gate in program["gates"] if gate["id"] == "GATE-060")
    git_diff_index = gate_060["checkIds"].index("git-diff-check")
    require(
        gate_060["checks"][git_diff_index] == EXACT_RANGE_GIT_DIFF_CHECK,
        "GATE-060 git-diff-check must name the exact resolved committed range",
    )
    verify_topology_api_zero_budget_policy(
        (root / "build-logic/src/main/kotlin/conventions/pr633-topology.gradle.kts").read_text(
            encoding="utf-8",
        ),
    )


def verify_topology_api_zero_budget_policy(source: str) -> None:
    expected_classes = {
        "TopologyGraph", "TopologyGraphOperations", "TopologyReachability", "TopologyCycle",
        "TopologyStrongComponent", "TopologyCondensation", "TopologyQuotientLevel",
        "TopologyQuotientNode", "TopologyQuotientEdge", "TopologyQuotientGraph",
        "TopologyPath", "TopologyQuery",
    }
    expected_methods = {
        "traverse", "reachability", "cycles", "stronglyConnectedComponents", "condensation",
        "quotient", "path", "query",
    }

    def configured_names(property_name: str) -> set[str]:
        match = re.search(
            rf"{property_name}\.set\(\s*setOf\((.*?)\)\s*,?\s*\)",
            source,
            re.DOTALL,
        )
        require(match is not None, f"topology API policy has no {property_name} inventory")
        return set(re.findall(r'"([A-Za-z][A-Za-z0-9]*)"', match.group(1)))

    observed_classes = configured_names("forbiddenClassSimpleNames")
    observed_methods = configured_names("forbiddenPublicMethodNames")
    require(observed_classes == expected_classes, "topology API forbidden class inventory differs")
    require(observed_methods == expected_methods, "topology API forbidden method inventory differs")


def verify_gate_authority_tokens(gate_id: str, gate_block: str) -> None:
    missing = [token for token in REQUIRED_GATE_AUTHORITIES[gate_id] if token not in gate_block]
    require(not missing, f"{gate_id} no longer depends on its declared proof authorities: {missing}")
    if gate_id == "GATE-070":
        verify_terminal_gate_task_block(gate_block)


def verify_terminal_gate_task_block(gate_block: str) -> None:
    dependencies = re.findall(r"dependsOn\((.*?)\)", gate_block, re.DOTALL)
    require(len(dependencies) == 1, "GATE-070 must declare exactly one dependency list")
    normalized = re.sub(r"\s+", "", dependencies[0]).rstrip(",")
    require(
        normalized == '"verifyPr633ProgramArtifacts"',
        "GATE-070 may depend only on the lightweight program verifier",
    )
    require(
        "dependencyReports.from(pr633Gate060EvidenceFile)" in gate_block,
        "GATE-070 must consume the supplied GATE-060 report as file evidence",
    )


def verify_ci_reuse(workflow: str) -> None:
    require("workflow_dispatch:" not in workflow, "PR 633 CI must not require manual dispatch")
    require(workflow.count("packaging/pr633-final-gate.sh") == 1, "heavy PR 633 gate must run exactly once")
    require(workflow.count("runs-on: macos-15") == 1, "PR workflow must have exactly one macOS producer")
    require("if: github.event.pull_request.number != 633" in workflow, "generic Kotlin suite is not skipped for PR 633")
    artifact = "pr633-gate-060-${{ github.event.pull_request.head.sha }}"
    require(workflow.count(artifact) == 3, "GATE-060 must be uploaded once and reused twice")
    marker = "\n  pr633-exact-head-ci:\n"
    require(workflow.count(marker) == 1, "terminal exact-head job is missing or duplicated")
    terminal = workflow.split(marker, 1)[1]
    require("    needs:\n      - pr633-merge-candidate\n" in terminal, "terminal job does not depend on the merge-candidate check")
    forbidden = ["pr633-final-gate.sh", "installedProductTest", "enterpriseAcceptance", "runtimeDeliveryMvpAcceptance", "topologyAcceptance"]
    require(not any(name in terminal for name in forbidden), "terminal exact-head job repeats heavy acceptance")


def verify_no_rust_paths(paths: list[str]) -> None:
    exact = {
        "Cargo.toml", "Cargo.lock", "rust-toolchain", "rust-toolchain.toml",
        "scripts/install-git-hooks.sh", "scripts/rust-agent-metadata.sh", "scripts/verify-setup-bundle.sh",
    }
    suffixes = (".rs", ".rlib", ".dylib", ".so", ".dll", ".exe")
    violations = sorted(path for path in paths if (
        path in exact or path.startswith(("cli-rs/", ".cargo/")) or path.endswith(suffixes)
        or Path(path).name in {"Cargo.toml", "Cargo.lock", "kast", "kastctl"}
        or (Path(path).name.startswith("kast-") and path.endswith((".tar.gz", ".zip")))
    ))
    require(not violations, f"tracked Rust product ownership remains: {violations}")


def verify_no_rust_product(root: Path) -> dict[str, Any]:
    process = subprocess.run(["git", "-C", str(root), "ls-files", "-z"], capture_output=True, text=True, check=False)
    require(process.returncode == 0, f"cannot enumerate tracked files: {process.stderr.strip()}")
    paths = [path for path in process.stdout.split("\0") if path]
    verify_no_rust_paths(paths)
    return {"status": "passed", "trackedPaths": len(paths)}
