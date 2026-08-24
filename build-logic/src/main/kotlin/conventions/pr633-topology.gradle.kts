package kast

import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSetContainer
import support.pr633.VerifyForbiddenBytecodeReferencesTask
import support.pr633.VerifyInternalKotlinClassTask
import support.pr633.VerifyTopologyContractApiTask
import support.pr633.WritePr633GateEvidenceTask

val topologyProgramFile = layout.projectDirectory.file("gradle/pr633/kast-pr633-program.json")
val topologyLifecycleSchemaFile = layout.projectDirectory.file(
    "gradle/pr633/schemas/topology-installed-lifecycle.schema.json",
)
val topologyContractApiManifest = layout.projectDirectory.file(
    "gradle/architecture/topology-contract-api.txt",
)
val topologyGateDirectory = layout.buildDirectory.dir("reports/pr633/gates")
val topologyGitHead = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.map(String::trim)
val registryEvidence = tasks.named<WritePr633GateEvidenceTask>(
    "operationRegistryAuthorityAcceptance",
)

fun mainOutputClassDirectories(projectPath: String) = objects.fileCollection().also { directories ->
    val targetProject = project(projectPath)
    targetProject.pluginManager.withPlugin("java") {
        val mainSourceSet = targetProject.extensions.getByType<SourceSetContainer>().named("main")
        directories.from(mainSourceSet.map { sourceSet -> sourceSet.output.classesDirs })
    }
}

val runtimeCompositionMainClassDirectories = mainOutputClassDirectories(":runtime:composition")
val evidenceSqliteMainClassDirectories = mainOutputClassDirectories(":evidence:sqlite")
val relationServiceMainClassDirectories = mainOutputClassDirectories(":relation:service")
val traversalServiceMainClassDirectories = mainOutputClassDirectories(":traversal:service")
val topologyContractMainClassDirectories = mainOutputClassDirectories(":topology:contract")
val topologyServiceMainClassDirectories = mainOutputClassDirectories(":topology:service")
val topologyConstructionAndPublicationOwners = listOf(
    "io/github/amichne/kast/topology/build/",
    "io/github/amichne/kast/topology/contract/TopologyBuildOperations",
    "io/github/amichne/kast/topology/contract/TopologyCandidateEnumerator",
    "io/github/amichne/kast/topology/contract/TopologyFileExtractor",
    "io/github/amichne/kast/topology/contract/TopologySnapshotPublisher",
    "io/github/amichne/kast/topology/contract/TopologySnapshotStore",
)
val topologyRuntimeAuthorityHolders = listOf(
    "io/github/amichne/kast/runtime/composition/TopologyRuntimePorts",
    "io/github/amichne/kast/runtime/composition/DirectKastOperations",
    "io/github/amichne/kast/runtime/composition/DirectKastRuntimeGraph",
    "io/github/amichne/kast/runtime/composition/KastRuntimeComposition",
    "io/github/amichne/kast/runtime/composition/KastRuntimeCompositionConstruction",
    "io/github/amichne/kast/runtime/composition/InstalledRuntimeAssemblyInputs",
)

val verifyChangePlanHasNoTopologyBuildAuthority =
    tasks.register<VerifyForbiddenBytecodeReferencesTask>(
        "verifyChangePlanHasNoTopologyBuildAuthority",
    ) {
        group = "verification"
        dependsOn(":runtime:composition:classes")
        classDirectories.from(runtimeCompositionMainClassDirectories)
        callerInternalNamePrefixes.set(
            listOf(
                "io/github/amichne/kast/runtime/composition/change/",
                "io/github/amichne/kast/runtime/composition/ChangePlanningOperations",
                "io/github/amichne/kast/runtime/composition/protocol/CanonicalChangePlan",
                "io/github/amichne/kast/runtime/composition/protocol/AuthorizedChangeIntent",
                "io/github/amichne/kast/runtime/composition/protocol/ChangeIntentAuthorization",
                "io/github/amichne/kast/runtime/composition/protocol/ChangePlanAdmission",
            ),
        )
        forbiddenOwnerPrefixes.set(
            topologyConstructionAndPublicationOwners + topologyRuntimeAuthorityHolders,
        )
        ruleName.set("change.plan cannot construct or publish topology")
    }

val verifyTraversalHasNoTopologyBuildOrPublishAuthority =
    tasks.register<VerifyForbiddenBytecodeReferencesTask>(
        "verifyTraversalHasNoTopologyBuildOrPublishAuthority",
    ) {
        group = "verification"
        dependsOn(":runtime:composition:classes")
        classDirectories.from(runtimeCompositionMainClassDirectories)
        callerInternalNamePrefixes.set(
            listOf(
                "io/github/amichne/kast/runtime/composition/protocol/graph/TopologyBackedTraversalOperations",
            ),
        )
        forbiddenOwnerPrefixes.set(
            topologyConstructionAndPublicationOwners + topologyRuntimeAuthorityHolders,
        )
        ruleName.set("traversal.run cannot construct, publish, or hold the topology store")
    }

val topologyPrerequisiteAcceptance = tasks.register<WritePr633GateEvidenceTask>(
    "topologyPrerequisiteAcceptance",
) {
    group = "verification"
    dependsOn(
        registryEvidence,
        verifyChangePlanHasNoTopologyBuildAuthority,
        verifyTraversalHasNoTopologyBuildOrPublishAuthority,
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
            "traversal-contract-violation-plan-rejected",
            "change-plan-no-topology-build-or-publish-authority",
            "traversal-router-no-topology-build-publish-or-store-authority",
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
        ":evidence:sqlite:classes",
        ":relation:service:classes",
        ":traversal:service:classes",
    )
    classDirectories.from(
        runtimeCompositionMainClassDirectories,
        evidenceSqliteMainClassDirectories,
        relationServiceMainClassDirectories,
        traversalServiceMainClassDirectories,
    )
    callerInternalNamePrefixes.set(
        listOf(
            "io/github/amichne/kast/runtime/composition/protocol/graph/CanonicalTraversalRunHandler",
            "io/github/amichne/kast/runtime/composition/protocol/graph/CanonicalRelationTraversalHandlersKt",
            "io/github/amichne/kast/runtime/composition/protocol/graph/TopologyBackedTraversalOperations",
            "io/github/amichne/kast/evidence/sqlite/SqliteTopology",
            "io/github/amichne/kast/relation/service/",
            "io/github/amichne/kast/traversal/service/",
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
        ) + topologyConstructionAndPublicationOwners.filterNot {
            it == "io/github/amichne/kast/topology/contract/TopologySnapshotStore"
        } + topologyRuntimeAuthorityHolders,
    )
    ruleName.set("installed traversal route cannot reach live compiler or topology construction")
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
    classDirectories.from(topologyContractMainClassDirectories)
    manifestFile.set(topologyContractApiManifest)
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
            "TopologyPath",
            "TopologyQuery",
        ),
    )
    forbiddenPublicMethodNames.set(
        setOf(
            "traverse",
            "reachability",
            "cycles",
            "stronglyConnectedComponents",
            "condensation",
            "quotient",
            "path",
            "query",
        ),
    )
}

val verifyGraphIndexInternal = tasks.register<VerifyInternalKotlinClassTask>(
    "verifyGraphIndexInternal",
) {
    group = "verification"
    dependsOn(":topology:service:classes")
    sourceFile.set(
        layout.projectDirectory.file(
            "topology/service/src/main/kotlin/io/github/amichne/kast/topology/service/GraphIndex.kt",
        ),
    )
    classDirectories.from(topologyServiceMainClassDirectories)
    expectedPackageName.set("io.github.amichne.kast.topology.service")
    expectedSimpleClassName.set("GraphIndex")
    reportFile.set(layout.buildDirectory.file("reports/pr633/checks/graph-index-internal.txt"))
}

val verifyTopologyOperationNames = tasks.register<Exec>("verifyTopologyOperationNames") {
    group = "verification"
    dependsOn(":protocol:wire:generateOperationRegistry")
    inputs.file(topologyProgramFile)
    inputs.file(layout.projectDirectory.file(".github/scripts/verify_pr633_program.py"))
    commandLine(
        "python3",
        layout.projectDirectory.file(".github/scripts/verify_pr633_program.py").asFile.absolutePath,
        "operation-names",
        "--root",
        layout.projectDirectory.asFile.absolutePath,
    )
}

val topologyContractAcceptance = tasks.register<WritePr633GateEvidenceTask>(
    "topologyContractAcceptance",
) {
    group = "verification"
    dependsOn(
        topologyInstalledProductAcceptance,
        verifyTopologyContractApi,
        verifyGraphIndexInternal,
        verifyTopologyOperationNames,
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
            "topology-contract-abi-manifest-exact",
            "topology-contract-no-zero-budget-graph-api",
            "graph-index-internal",
            "no-topology-read-operation-in-pr633",
            "no-topology-cycles-operation",
            "canonical-topology-operation-name-set-exact",
        ),
    )
    dependencyReports.from(topologyInstalledProductAcceptance.flatMap { it.reportFile })
    reportFile.set(topologyGateDirectory.map { it.file("topology-contract.json") })
}
