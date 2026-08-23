package kast

import java.io.File
import java.security.MessageDigest
import org.gradle.api.tasks.Exec
import support.pr633.VerifyPr633StackTask
import support.pr633.WritePr633GateEvidenceTask
import support.tasks.WriteProcessOutputTask

val pr633ProgramFile = layout.projectDirectory.file("gradle/pr633/kast-pr633-program.json")
val pr633PathPolicyFile = layout.projectDirectory.file(
    "gradle/pr633/policies/pr633-path-policy.json",
)
val cleanupPathPolicyFile = layout.projectDirectory.file(
    "gradle/pr633/policies/cleanup-path-policy.json",
)
val pr633ExpectedOperations = layout.projectDirectory.file(
    "gradle/pr633/operation-registry.expected.json",
)
val pr633Verifier = layout.projectDirectory.file(".github/scripts/verify_pr633_program.py")
val pr633GateDirectory = layout.buildDirectory.dir("reports/pr633/gates")
val pr633GitHead = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.map(String::trim)
val pr633EventFile = layout.file(
    providers.gradleProperty("pr633EventFile")
        .orElse(providers.environmentVariable("GITHUB_EVENT_PATH"))
        .map(::File),
)
val cleanupMergedEvidenceFile = layout.file(
    providers.gradleProperty("pr633CleanupMergedEvidence").map(::File),
)
val cleanupPullRequest = 635
val cleanupMergeSha = "5400847a6d07ecb1060b575b6073a9535b31bc13"
val cleanupPolicySha256 = providers.provider {
    "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(cleanupPathPolicyFile.asFile.readBytes())
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}

val verifyPr633ProgramArtifacts = tasks.register<Exec>("verifyPr633ProgramArtifacts") {
    group = "verification"
    description = "Validates the installed PR 633 program and exact GATE-001 through GATE-070 chain."
    commandLine(
        "python3",
        pr633Verifier.asFile.absolutePath,
        "artifact",
        "--root",
        layout.projectDirectory.asFile.absolutePath,
    )
}

val verifyCleanupMergedIntoMain = tasks.register<Exec>("verifyCleanupMergedIntoMain") {
    group = "verification"
    description = "Requires the cleanup PR merge commit to be contained in origin/main."
    commandLine("git", "merge-base", "--is-ancestor", cleanupMergeSha, "origin/main")
}

val cleanupBaseAcceptance = tasks.register<WritePr633GateEvidenceTask>("cleanupBaseAcceptance") {
    group = "verification"
    dependsOn(verifyPr633ProgramArtifacts, verifyCleanupMergedIntoMain)
    programFile.set(pr633ProgramFile)
    gateId.set("GATE-001")
    headSha.set(pr633GitHead)
    externalEvidenceFile.set(cleanupMergedEvidenceFile)
    expectedExternalKind.set("merged-pull-request")
    externalEvidenceBindsHead.set(false)
    expectedExternalFacts.put("repository", "amichne/kast")
    expectedExternalFacts.put("pullRequest", cleanupPullRequest.toString())
    expectedExternalFacts.put("baseRef", "main")
    expectedExternalFacts.put("mergeCommitSha", cleanupMergeSha)
    expectedExternalFacts.put("pathPolicySha256", cleanupPolicySha256)
    checkIds.set(
        listOf(
            "cleanup-pull-request-merged",
            "cleanup-merge-contained-in-main",
            "cleanup-review-separate-from-topology",
        ),
    )
    reportFile.set(pr633GateDirectory.map { it.file("cleanup-base.json") })
}

val verifyPr633Stack = tasks.register<VerifyPr633StackTask>("verifyPr633Stack") {
    group = "verification"
    dependsOn(verifyPr633ProgramArtifacts)
    eventFile.set(pr633EventFile)
    programFile.set(pr633ProgramFile)
    pathPolicyFile.set(pr633PathPolicyFile)
    expectedPullRequest.set(633)
    expectedBaseRef.set("main")
    mainGitRef.set("origin/main")
    headGitRef.set("HEAD")
    repositoryDirectory.set(layout.projectDirectory)
    reportFile.set(layout.buildDirectory.file("reports/pr633/checks/stack.json"))
}

val pr633StackAcceptance = tasks.register<WritePr633GateEvidenceTask>("pr633StackAcceptance") {
    group = "verification"
    dependsOn(cleanupBaseAcceptance, verifyPr633Stack)
    programFile.set(pr633ProgramFile)
    gateId.set("GATE-002")
    headSha.set(pr633GitHead)
    checkIds.set(
        listOf(
            "pr-number-633",
            "base-ref-main",
            "event-head-equals-git-head",
            "main-is-ancestor",
            "changed-paths-admitted",
        ),
    )
    dependencyReports.from(cleanupBaseAcceptance.flatMap { it.reportFile })
    reportFile.set(pr633GateDirectory.map { it.file("stack.json") })
}

val generatedOperationRegistry = project(":protocol:wire").layout.buildDirectory.file(
    "generated/operation-registry/operation-registry.json",
)
val stagedOperationRegistry = layout.buildDirectory.file(
    "generated/control-metadata/operation-registry.json",
)
val installedSchemaFile = layout.buildDirectory.file("reports/pr633/checks/installed-schema.json")

val captureInstalledSchema = tasks.register<WriteProcessOutputTask>("captureInstalledSchema") {
    group = "verification"
    dependsOn("stageInstalledProduct")
    val executable = layout.buildDirectory.file("installed-product/bin/kast")
    executableFile.set(executable)
    arguments.set(listOf("--schema"))
    environmentVariables.set(
        mapOf(
            "KAST_RUNTIME_ARCHIVE" to "",
            "KAST_RUNTIME_STORE" to layout.buildDirectory.dir("pr633-no-runtime")
                .get().asFile.absolutePath,
        ),
    )
    outputFile.set(installedSchemaFile)
}

val verifyOperationRegistryAuthority = tasks.register<Exec>("verifyOperationRegistryAuthority") {
    group = "verification"
    dependsOn(
        ":protocol:wire:generateOperationRegistry",
        "generateKastControlMetadata",
        captureInstalledSchema,
    )
    inputs.files(
        pr633ExpectedOperations,
        generatedOperationRegistry,
        stagedOperationRegistry,
        installedSchemaFile,
    )
    commandLine(
        "python3",
        pr633Verifier.asFile.absolutePath,
        "registry",
        "--expected",
        pr633ExpectedOperations.asFile.absolutePath,
        "--generated",
        generatedOperationRegistry.get().asFile.absolutePath,
        "--installed",
        stagedOperationRegistry.get().asFile.absolutePath,
        "--schema",
        installedSchemaFile.get().asFile.absolutePath,
    )
}

val operationRegistryAuthorityAcceptance = tasks.register<WritePr633GateEvidenceTask>(
    "operationRegistryAuthorityAcceptance",
) {
    group = "verification"
    dependsOn(
        pr633StackAcceptance,
        verifyOperationRegistryAuthority,
        ":protocol:registry:test",
        ":protocol:wire:test",
        ":cli:test",
    )
    programFile.set(pr633ProgramFile)
    gateId.set("GATE-010")
    headSha.set(pr633GitHead)
    checkIds.set(
        listOf(
            "canonical-definitions-generate-registry",
            "generated-registry-exact-twelve",
            "control-metadata-byte-copy",
            "registry-round-trip",
            "duplicate-operation-rejected",
        ),
    )
    dependencyReports.from(pr633StackAcceptance.flatMap { it.reportFile })
    reportFile.set(pr633GateDirectory.map { it.file("operation-registry.json") })
}
