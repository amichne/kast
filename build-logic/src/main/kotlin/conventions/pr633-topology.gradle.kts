package kast

import org.gradle.api.tasks.Exec
import support.pr633.VerifyForbiddenBytecodeReferencesTask
import support.pr633.VerifyTopologyContractApiTask
import support.pr633.WritePr633GateEvidenceTask

val topologyProgramFile = layout.projectDirectory.file("gradle/pr633/kast-pr633-program.json")
val topologyLifecycleSchemaFile = layout.projectDirectory.file(
    "gradle/pr633/schemas/topology-installed-lifecycle.schema.json",
)
val topologyGateDirectory = layout.buildDirectory.dir("reports/pr633/gates")
val topologyGitHead = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.map(String::trim)
val registryEvidence = tasks.named<WritePr633GateEvidenceTask>(
    "operationRegistryAuthorityAcceptance",
)
val runtimeCompositionClasses = project(":runtime:composition").layout.buildDirectory.dir(
    "classes/kotlin/main",
)

val verifyChangePlanHasNoTopologyBuildAuthority =
    tasks.register<VerifyForbiddenBytecodeReferencesTask>(
        "verifyChangePlanHasNoTopologyBuildAuthority",
    ) {
        group = "verification"
        dependsOn(":runtime:composition:classes")
        classDirectories.from(runtimeCompositionClasses)
        callerInternalNamePrefixes.set(
            listOf(
                "io/github/amichne/kast/runtime/composition/change/",
                "io/github/amichne/kast/runtime/composition/protocol/CanonicalChangePlan",
            ),
        )
        forbiddenOwnerPrefixes.set(
            listOf(
                "io/github/amichne/kast/topology/build/",
                "io/github/amichne/kast/topology/contract/TopologyBuildOperations",
                "io/github/amichne/kast/topology/contract/TopologySnapshotPublisher",
            ),
        )
        ruleName.set("change.plan cannot construct topology")
    }

val topologyPrerequisiteAcceptance = tasks.register<WritePr633GateEvidenceTask>(
    "topologyPrerequisiteAcceptance",
) {
    group = "verification"
    dependsOn(
        registryEvidence,
        verifyChangePlanHasNoTopologyBuildAuthority,
        ":traversal:contract:test",
        ":traversal:service:test",
        ":protocol:wire:test",
        ":runtime:composition:test",
        ":cli:test",
    )
    programFile.set(topologyProgramFile)
    gateId.set("GATE-020")
    headSha.set(topologyGitHead)
    checkIds.set(
        listOf(
            "missing-topology-build-required",
            "stale-topology-build-required",
            "stale-selector-selector-stale",
            "bounded-required-traversal-incomplete",
            "invalid-public-traversal-plan-rejected",
            "change-plan-no-topology-build-authority",
        ),
    )
    dependencyReports.from(registryEvidence.flatMap { it.reportFile })
    reportFile.set(topologyGateDirectory.map { it.file("topology-prerequisite.json") })
}

val verifyTopologyTraversalNoK2 = tasks.register<VerifyForbiddenBytecodeReferencesTask>(
    "verifyTopologyTraversalNoK2",
) {
    group = "verification"
    dependsOn(
        ":runtime:composition:classes",
        ":topology:build:classes",
        ":evidence:sqlite:classes",
    )
    classDirectories.from(
        runtimeCompositionClasses,
        project(":topology:build").layout.buildDirectory.dir("classes/kotlin/main"),
        project(":evidence:sqlite").layout.buildDirectory.dir("classes/kotlin/main"),
    )
    callerInternalNamePrefixes.set(
        listOf(
            "io/github/amichne/kast/runtime/composition/protocol/graph/TopologyBackedTraversalOperations",
            "io/github/amichne/kast/topology/build/",
            "io/github/amichne/kast/evidence/sqlite/SqliteTopologySnapshotStore",
        ),
    )
    forbiddenOwnerPrefixes.set(
        listOf(
            "org/jetbrains/",
            "com/intellij/",
            "io/github/amichne/kast/topology/intellij/",
            "io/github/amichne/kast/relation/intellij/",
            "io/github/amichne/kast/symbol/intellij/",
            "org/gradle/",
        ),
    )
    ruleName.set("topology build, publication, and traversal routes cannot reach K2")
}

val topologyJourneyReport = layout.buildDirectory.file(
    "reports/installed-product/topology-installed-product.json",
)
val verifyInstalledTopologyJourneyReport = tasks.register<Exec>(
    "verifyInstalledTopologyJourneyReport",
) {
    group = "verification"
    dependsOn("installedProductTest")
    inputs.file(topologyJourneyReport)
    commandLine(
        "python3",
        layout.projectDirectory.file("packaging/topology_installed_acceptance.py").asFile.absolutePath,
        "verify",
        "--report",
        topologyJourneyReport.get().asFile.absolutePath,
        "--registry",
        layout.buildDirectory.file("generated/control-metadata/operation-registry.json").get().asFile.absolutePath,
        "--output",
        layout.buildDirectory.file(
            "reports/pr633/checks/topology-installed-product-verification.json",
        ).get().asFile.absolutePath,
    )
}

val validateInstalledTopologyJourneySchema = tasks.register<Exec>(
    "validateInstalledTopologyJourneySchema",
) {
    group = "verification"
    dependsOn("installedProductTest")
    inputs.file(topologyJourneyReport)
    inputs.file(topologyLifecycleSchemaFile)
    commandLine(
        "python3",
        layout.projectDirectory.file(".github/scripts/verify_pr633_program.py").asFile.absolutePath,
        "lifecycle",
        "--report",
        topologyJourneyReport.get().asFile.absolutePath,
        "--schema",
        topologyLifecycleSchemaFile.asFile.absolutePath,
    )
}

val topologyInstalledProductAcceptance = tasks.register<WritePr633GateEvidenceTask>(
    "topologyInstalledProductAcceptance",
) {
    group = "verification"
    dependsOn(
        topologyPrerequisiteAcceptance,
        "installedProductTest",
        verifyInstalledTopologyJourneyReport,
        validateInstalledTopologyJourneySchema,
        verifyTopologyTraversalNoK2,
        ":topology:build:test",
        ":evidence:sqlite:test",
        ":traversal:service:test",
    )
    programFile.set(topologyProgramFile)
    gateId.set("GATE-030")
    headSha.set(topologyGitHead)
    checkIds.set(
        listOf(
            "installed-schema-exact-twelve",
            "first-build-published",
            "second-build-reused",
            "same-generation-and-digest",
            "restart-semantic-result-equal",
            "traversal-router-no-k2",
            "mutation-advances-generation",
            "old-selector-stale",
            "fresh-selector-requires-rebuild",
            "stale-negative-not-complete",
            "complete-rebuild-published",
            "new-edge-observed",
            "corrupt-or-incomplete-snapshot-rejected",
        ),
    )
    dependencyReports.from(topologyPrerequisiteAcceptance.flatMap { it.reportFile })
    reportFile.set(topologyGateDirectory.map { it.file("topology-installed-product.json") })
}

tasks.matching { it.name == "topologyAcceptance" }.configureEach {
    dependsOn(topologyInstalledProductAcceptance)
}

val verifyTopologyContractApi = tasks.register<VerifyTopologyContractApiTask>(
    "verifyTopologyContractApi",
) {
    group = "verification"
    dependsOn(":topology:contract:classes")
    classDirectories.from(
        project(":topology:contract").layout.buildDirectory.dir("classes/kotlin/main"),
    )
    forbiddenClassSimpleNames.set(
        setOf(
            "TopologyGraph",
            "TopologyGraphOperations",
            "TopologyReachability",
            "TopologyCycle",
            "TopologyStrongComponent",
            "TopologyCondensation",
            "TopologyQuotientLevel",
            "TopologyQuotientNode",
            "TopologyQuotientEdge",
            "TopologyQuotientGraph",
        ),
    )
    forbiddenPublicMethodNames.set(
        setOf("traverse", "reachability", "cycles", "stronglyConnectedComponents", "condensation", "quotient"),
    )
}

val topologyContractAcceptance = tasks.register<WritePr633GateEvidenceTask>(
    "topologyContractAcceptance",
) {
    group = "verification"
    dependsOn(
        topologyInstalledProductAcceptance,
        verifyTopologyContractApi,
        ":topology:contract:test",
        ":topology:service:test",
        "verifyKastModuleGraph",
        "verifyForbiddenEffects",
    )
    programFile.set(topologyProgramFile)
    gateId.set("GATE-040")
    headSha.set(topologyGitHead)
    checkIds.set(
        listOf(
            "topology-contract-no-zero-budget-graph-api",
            "graph-index-internal",
            "no-topology-read-operation-in-pr633",
            "no-topology-cycles-operation",
        ),
    )
    dependencyReports.from(topologyInstalledProductAcceptance.flatMap { it.reportFile })
    reportFile.set(topologyGateDirectory.map { it.file("topology-contract.json") })
}
