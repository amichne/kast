package kast

import java.io.File
import org.gradle.api.tasks.Exec
import support.pr633.VerifyPr633GitDiffTask
import support.pr633.WritePr633GateEvidenceTask

val deliveryProgramFile = layout.projectDirectory.file("gradle/pr633/kast-pr633-program.json")
val deliveryVerifier = layout.projectDirectory.file(".github/scripts/verify_pr633_program.py")
val deliveryGateDirectory = layout.buildDirectory.dir("reports/pr633/gates")
val deliveryGitHead = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.map(String::trim)
val topologyContractEvidence = tasks.named<WritePr633GateEvidenceTask>(
    "topologyContractAcceptance",
)
val allSubprojectTests = subprojects
    .filter { subproject -> subproject.childProjects.isEmpty() }
    .map { subproject -> "${subproject.path}:test" }

fun registerPr633Exec(name: String, vararg command: String) = tasks.register<Exec>(name) {
    group = "verification"
    commandLine(*command)
}

val verifyPr633Authorities = tasks.register<Exec>("verifyPr633Authorities") {
    group = "verification"
    commandLine(
        "python3",
        deliveryVerifier.asFile.absolutePath,
        "authorities",
        "--root",
        layout.projectDirectory.asFile.absolutePath,
    )
}
val verifyRepositoryShape = registerPr633Exec(
    "verifyRepositoryShape",
    "python3",
    ".github/scripts/check-repository-shape.py",
    "--root",
    ".",
)
val verifyGeneratedCliReference = registerPr633Exec(
    "verifyGeneratedCliReference",
    "python3",
    "docs/generate_cli_reference.py",
    "--check",
)
verifyGeneratedCliReference.configure {
    dependsOn(":protocol:wire:generateOperationRegistry")
}
val verifyPublicDocs = registerPr633Exec(
    "verifyPublicDocs",
    "python3",
    "docs/test_public_docs.py",
)
val verifyGitDiff = tasks.register<VerifyPr633GitDiffTask>("verifyGitDiff") {
    group = "verification"
    mainGitRef.set("origin/main")
    headGitRef.set("HEAD")
    repositoryDirectory.set(layout.projectDirectory)
    reportFile.set(layout.buildDirectory.file("reports/pr633/checks/git-diff.json"))
}

val pr633AuthorityAcceptance = tasks.register<WritePr633GateEvidenceTask>(
    "pr633AuthorityAcceptance",
) {
    group = "verification"
    dependsOn(
        topologyContractEvidence,
        verifyPr633Authorities,
        verifyGeneratedCliReference,
        verifyPublicDocs,
    )
    programFile.set(deliveryProgramFile)
    gateId.set("GATE-050")
    headSha.set(deliveryGitHead)
    checkIds.set(
        listOf(
            "safe-change-builds-topology-first",
            "relation-read-live-k2",
            "traversal-run-snapshot-backed",
            "clean-slate-exact-twelve",
            "clean-slate-durable-topology-task",
            "generated-cli-reference-current",
            "public-docs-current",
        ),
    )
    dependencyReports.from(topologyContractEvidence.flatMap { it.reportFile })
    reportFile.set(deliveryGateDirectory.map { it.file("authorities.json") })
}

val pr633MergeCandidateAcceptance = tasks.register<WritePr633GateEvidenceTask>(
    "pr633MergeCandidateAcceptance",
) {
    group = "verification"
    dependsOn(
        pr633AuthorityAcceptance,
        "topologyAcceptance",
        "runtimeDeliveryMvpAcceptance",
        allSubprojectTests,
        "verifyKastModuleGraph",
        "verifyForbiddenEffects",
        "verifyNoLegacyArchitecture",
        verifyRepositoryShape,
        verifyGeneratedCliReference,
        verifyPublicDocs,
        verifyGitDiff,
    )
    programFile.set(deliveryProgramFile)
    gateId.set("GATE-060")
    headSha.set(deliveryGitHead)
    checkIds.set(
        listOf(
            "topology-acceptance",
            "runtime-delivery-mvp-acceptance",
            "all-tests",
            "module-graph",
            "forbidden-effects",
            "no-legacy-architecture",
            "repository-shape",
            "generated-cli-reference",
            "public-docs",
            "git-diff-check",
        ),
    )
    dependencyReports.from(pr633AuthorityAcceptance.flatMap { it.reportFile })
    reportFile.set(deliveryGateDirectory.map { it.file("merge-candidate.json") })
}

val exactHeadCiEvidenceFile = layout.file(
    providers.gradleProperty("pr633ExactHeadCiEvidence").map(::File),
)
val pr633Gate060EvidenceFile = layout.file(
    providers.gradleProperty("pr633Gate060Evidence").map(::File),
)
tasks.register<WritePr633GateEvidenceTask>("pr633ExactHeadCiAcceptance") {
    group = "verification"
    dependsOn("verifyPr633ProgramArtifacts")
    programFile.set(deliveryProgramFile)
    gateId.set("GATE-070")
    headSha.set(deliveryGitHead)
    externalEvidenceFile.set(exactHeadCiEvidenceFile)
    expectedExternalKind.set("exact-head-ci")
    expectedExternalFacts.set(
        mapOf(
            "repository" to "amichne/kast",
            "pullRequest" to "633",
            "baseRef" to "main",
            "checkName" to "pr633-merge-candidate",
            "checkConclusion" to "success",
        ),
    )
    checkIds.set(
        listOf(
            "checkout-exact-pr-head",
            "gate-060-program-and-head-bound",
            "required-merge-candidate-check",
            "check-conclusion-success",
            "check-head-equals-pr-head",
            "evidence-reused-without-product-work",
        ),
    )
    dependencyReports.from(pr633Gate060EvidenceFile)
    reportFile.set(deliveryGateDirectory.map { it.file("exact-head-ci.json") })
}
