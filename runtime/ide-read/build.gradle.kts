import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("kast.kotlin-library")
    kotlin("plugin.serialization")
    id("kast.role.ide-read-only")
}

group = "${rootProject.group}.runtime"

base {
    archivesName.set("runtime-ide-read")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideHostBuild = catalog.findVersion("ide-host-build").get().requiredVersion

private val workspaceIdeaDistributionDirectory = layout.dir(
    providers.provider {
        gradle.gradleUserHomeDir.resolve(
            "kast/workspace-intellij-read-idea-distributions/$ideHostBuild",
        )
    },
)

private val workspaceIdeaLibraries = files(
    workspaceIdeaDistributionDirectory.map { directory ->
        fileTree(directory) {
            include("**/lib/**/*.jar")
            exclude("**/plugins/**")
            exclude("**/lib/intellij.libraries.kotlinx.serialization.*.jar")
            exclude("**/lib/intellij.libraries.ktor.utils.jar")
        }
    },
).builtBy(":workspace:intellij-read:extractWorkspaceReadIdeaDistribution")

dependencies {
    implementation(project(":protocol:wire"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:intellij-read"))
    compileOnly(workspaceIdeaLibraries)
    testImplementation(workspaceIdeaLibraries)
    testImplementation(catalog.findLibrary("serialization-json").get())
}

private val workspaceContractFriendPath =
    project(":workspace:contract").layout.buildDirectory.dir("classes/kotlin/main")
private val workspaceIntellijReadFriendPath =
    project(":workspace:intellij-read").layout.buildDirectory.dir("classes/kotlin/main")

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(
        ":workspace:contract:compileKotlin",
        ":workspace:intellij-read:compileKotlin",
        ":workspace:intellij-read:extractWorkspaceReadIdeaDistribution",
    )
    compilerOptions.freeCompilerArgs.add(
        workspaceContractFriendPath.zip(workspaceIntellijReadFriendPath) { contract, intellijRead ->
            "-Xfriend-paths=${contract.asFile.absolutePath},${intellijRead.asFile.absolutePath}"
        },
    )
}

val singleFlightReport = layout.buildDirectory.file(
    "reports/KVP-020-single-flight.json",
)
val singleFlightExpectedHead = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
    workingDir(rootProject.rootDir)
}.standardOutput.asText.map(String::trim)

val generateSingleFlightReport =
    tasks.register<support.delivery.GenerateKvp020SingleFlightReportTask>(
        "generateSingleFlightReport",
    ) {
        group = "verification"
        description = "Generates the exact predecessor-bound KVP-020 single-flight report."
        dependsOn(
            rootProject.tasks.named("verifyKVP014CompletionReceipt"),
            rootProject.tasks.named("verifyKVP019CompletionReceipt"),
        )
        kvp014CompletionReceipt.set(rootProject.layout.buildDirectory.file(
            "reports/delivery/receipts/KVP-014-COMPLETE.receipt.json",
        ))
        kvp019CompletionReceipt.set(rootProject.layout.buildDirectory.file(
            "reports/delivery/receipts/KVP-019-COMPLETE.receipt.json",
        ))
        reportFile.set(singleFlightReport)
    }

val verifySingleFlightReportNegative =
    tasks.register<support.delivery.VerifyKvp020SingleFlightReportNegativeTask>(
        "verifySingleFlightReportNegative",
    ) {
        group = "verification"
        description = "Rejects every fixed KVP-020 single-flight report mutation."
        dependsOn(generateSingleFlightReport)
        reportFile.set(singleFlightReport)
        kvp014CompletionReceipt.set(generateSingleFlightReport.flatMap(
            support.delivery.GenerateKvp020SingleFlightReportTask::kvp014CompletionReceipt,
        ))
        kvp019CompletionReceipt.set(generateSingleFlightReport.flatMap(
            support.delivery.GenerateKvp020SingleFlightReportTask::kvp019CompletionReceipt,
        ))
    }

val defaultTest = tasks.named<Test>("test")

defaultTest.configure {
    dependsOn(verifySingleFlightReportNegative)
    mustRunAfter(verifySingleFlightReportNegative)
    inputs.file(singleFlightReport).withPathSensitivity(PathSensitivity.NONE)
    inputs.property("kast.ide.single.flight.expected.head", singleFlightExpectedHead)
    systemProperty("kast.ide.single.flight.report", singleFlightReport.get().asFile.absolutePath)
    systemProperty(
        "kast.ide.single.flight.kvp014.receipt",
        rootProject.layout.buildDirectory.file(
            "reports/delivery/receipts/KVP-014-COMPLETE.receipt.json",
        ).get().asFile.absolutePath,
    )
    systemProperty(
        "kast.ide.single.flight.kvp019.receipt",
        rootProject.layout.buildDirectory.file(
            "reports/delivery/receipts/KVP-019-COMPLETE.receipt.json",
        ).get().asFile.absolutePath,
    )
    systemProperty(
        "kast.ide.single.flight.expected.head",
        singleFlightExpectedHead.get(),
    )
}

val cancellableReadReport = layout.buildDirectory.file(
    "reports/KVP-021-cancellable-read.json",
)
val generateCancellableReadReport =
    tasks.register<support.delivery.GenerateKvp021CancellableReadReportTask>(
        "generateCancellableReadReport",
    ) {
        group = "verification"
        description = "Generates the exact predecessor-bound KVP-021 cancellable-read report."
        dependsOn(
            rootProject.tasks.named("verifyKVP019CompletionReceipt"),
            rootProject.tasks.named("verifyKVP020CompletionReceipt"),
        )
        kvp019CompletionReceipt.set(rootProject.layout.buildDirectory.file(
            "reports/delivery/receipts/KVP-019-COMPLETE.receipt.json",
        ))
        kvp020CompletionReceipt.set(rootProject.layout.buildDirectory.file(
            "reports/delivery/receipts/KVP-020-COMPLETE.receipt.json",
        ))
        reportFile.set(cancellableReadReport)
    }

val verifyCancellableReadReportNegative =
    tasks.register<support.delivery.VerifyKvp021CancellableReadReportNegativeTask>(
        "verifyCancellableReadReportNegative",
    ) {
        group = "verification"
        description = "Rejects every fixed KVP-021 report and gate-evidence mutation."
        dependsOn(generateCancellableReadReport)
        reportFile.set(cancellableReadReport)
        kvp019CompletionReceipt.set(generateCancellableReadReport.flatMap(
            support.delivery.GenerateKvp021CancellableReadReportTask::kvp019CompletionReceipt,
        ))
        kvp020CompletionReceipt.set(generateCancellableReadReport.flatMap(
            support.delivery.GenerateKvp021CancellableReadReportTask::kvp020CompletionReceipt,
        ))
    }

fun support.delivery.Kvp021CancellableReadGateTask.configureCancellableReadGate(
    command: support.delivery.Kvp021GateCommand,
    evidencePath: String,
) {
    group = "verification"
    testClassesDirs = defaultTest.get().testClassesDirs
    classpath = defaultTest.get().classpath
    filter.includeTestsMatching(command.selectorPattern)
    filter.setFailOnNoMatchingTests(true)
    dependsOn(verifyCancellableReadReportNegative)
    repositoryRootPath.set(rootProject.layout.projectDirectory.asFile.absolutePath)
    declaredCommand.set(command.declaredCommand)
    gateEvidenceFile.set(layout.buildDirectory.file(evidencePath))
    doFirst { beginGateExecution() }
    doLast { completeGateExecution() }
    inputs.file(cancellableReadReport).withPathSensitivity(PathSensitivity.NONE)
    systemProperty(
        "kast.ide.cancellable.read.report",
        cancellableReadReport.get().asFile.absolutePath,
    )
    systemProperty(
        "kast.ide.cancellable.read.gate.evidence",
        gateEvidenceFile.get().asFile.absolutePath,
    )
}

val cancellableReadNegativeGate =
    tasks.register<support.delivery.Kvp021CancellableReadGateTask>(
        "cancellableReadNegativeGate",
    ) {
        description = "Runs only the canonical KVP-021 negative selector at one exact head."
        configureCancellableReadGate(
            support.delivery.Kvp021GateCommand.RED,
            "reports/KVP-021-red-gate.json",
        )
    }

val cancellableReadGate = tasks.register<support.delivery.Kvp021CancellableReadGateTask>(
    "cancellableReadGate",
) {
    description = "Runs only the canonical KVP-021 GREEN selector at one exact head."
    configureCancellableReadGate(
        support.delivery.Kvp021GateCommand.GREEN,
        "reports/KVP-021-green-gate.json",
    )
    mustRunAfter(cancellableReadNegativeGate, ":recordKVP021RedReceipt")
}

val epochRevalidationReport = layout.buildDirectory.file(
    "reports/KVP-022-epoch-revalidation.json",
)
val generateEpochRevalidationReport =
    tasks.register<support.delivery.GenerateKvp022EpochRevalidationReportTask>(
        "generateEpochRevalidationReport",
    ) {
        group = "verification"
        description = "Generates the exact KVP-021-bound KVP-022 epoch-revalidation report."
        dependsOn(rootProject.tasks.named("verifyKVP021CompletionReceipt"))
        kvp021CompletionReceipt.set(rootProject.layout.buildDirectory.file(
            "reports/delivery/receipts/KVP-021-COMPLETE.receipt.json",
        ))
        reportFile.set(epochRevalidationReport)
    }

val verifyEpochRevalidationReportNegative =
    tasks.register<support.delivery.VerifyKvp022EpochRevalidationReportNegativeTask>(
        "verifyEpochRevalidationReportNegative",
    ) {
        group = "verification"
        description = "Rejects every fixed KVP-022 report, predecessor, and gate mutation."
        dependsOn(generateEpochRevalidationReport)
        reportFile.set(epochRevalidationReport)
        kvp021CompletionReceipt.set(generateEpochRevalidationReport.flatMap(
            support.delivery.GenerateKvp022EpochRevalidationReportTask::kvp021CompletionReceipt,
        ))
    }

fun support.delivery.Kvp022EpochRevalidationGateTask.configureEpochRevalidationGate(
    command: support.delivery.Kvp022GateCommand,
    evidencePath: String,
) {
    group = "verification"
    testClassesDirs = defaultTest.get().testClassesDirs
    classpath = defaultTest.get().classpath
    filter.includeTestsMatching(command.selectorPattern)
    filter.setFailOnNoMatchingTests(true)
    dependsOn(verifyEpochRevalidationReportNegative)
    repositoryRootPath.set(rootProject.layout.projectDirectory.asFile.absolutePath)
    declaredCommand.set(command.declaredCommand)
    gateEvidenceFile.set(layout.buildDirectory.file(evidencePath))
    doFirst { beginGateExecution() }
    doLast { completeGateExecution() }
    inputs.file(epochRevalidationReport).withPathSensitivity(PathSensitivity.NONE)
    systemProperty(
        "kast.ide.epoch.revalidation.report",
        epochRevalidationReport.get().asFile.absolutePath,
    )
    systemProperty(
        "kast.ide.epoch.revalidation.kvp021.receipt",
        rootProject.layout.buildDirectory.file(
            "reports/delivery/receipts/KVP-021-COMPLETE.receipt.json",
        ).get().asFile.absolutePath,
    )
    systemProperty(
        "kast.ide.epoch.revalidation.gate.evidence",
        gateEvidenceFile.get().asFile.absolutePath,
    )
}

val epochRevalidationNegativeGate =
    tasks.register<support.delivery.Kvp022EpochRevalidationGateTask>(
        "epochRevalidationNegativeGate",
    ) {
        description = "Runs only the canonical KVP-022 negative selector at one exact head."
        configureEpochRevalidationGate(
            support.delivery.Kvp022GateCommand.RED,
            "reports/KVP-022-red-gate.json",
        )
    }

val epochRevalidationGate = tasks.register<support.delivery.Kvp022EpochRevalidationGateTask>(
    "epochRevalidationGate",
) {
    description = "Runs only the canonical KVP-022 GREEN selector at one exact head."
    configureEpochRevalidationGate(
        support.delivery.Kvp022GateCommand.GREEN,
        "reports/KVP-022-green-gate.json",
    )
    mustRunAfter(epochRevalidationNegativeGate, ":recordKVP022RedReceipt")
}

val readRuntimeReport = layout.buildDirectory.file("reports/KVP-023-read-runtime.json")
val generateReadRuntimeReport = tasks.register<support.delivery.GenerateKvp023ReadRuntimeReportTask>(
        "generateReadRuntimeReport",
    ) {
        group = "verification"
        description = "Generates the exact predecessor-bound KVP-023 read-runtime report."
        dependsOn(
            rootProject.tasks.named("verifyKVP009CompletionReceipt"),
            rootProject.tasks.named("verifyKVP016CompletionReceipt"),
            rootProject.tasks.named("verifyKVP022CompletionReceipt"),
        )
        kvp009CompletionReceipt.set(rootProject.layout.buildDirectory.file(
            "reports/delivery/receipts/KVP-009-COMPLETE.receipt.json",
        ))
        kvp016CompletionReceipt.set(rootProject.layout.buildDirectory.file(
            "reports/delivery/receipts/KVP-016-COMPLETE.receipt.json",
        ))
        kvp022CompletionReceipt.set(rootProject.layout.buildDirectory.file(
            "reports/delivery/receipts/KVP-022-COMPLETE.receipt.json",
        ))
        reportFile.set(readRuntimeReport)
    }

val verifyReadRuntimeReportNegative =
    tasks.register<support.delivery.VerifyKvp023ReadRuntimeReportNegativeTask>(
        "verifyReadRuntimeReportNegative",
    ) {
        group = "verification"
        description = "Rejects every fixed KVP-023 report, predecessor, and gate mutation."
        dependsOn(generateReadRuntimeReport)
        reportFile.set(readRuntimeReport)
        kvp009CompletionReceipt.set(generateReadRuntimeReport.flatMap(
            support.delivery.GenerateKvp023ReadRuntimeReportTask::kvp009CompletionReceipt,
        ))
        kvp016CompletionReceipt.set(generateReadRuntimeReport.flatMap(
            support.delivery.GenerateKvp023ReadRuntimeReportTask::kvp016CompletionReceipt,
        ))
        kvp022CompletionReceipt.set(generateReadRuntimeReport.flatMap(
            support.delivery.GenerateKvp023ReadRuntimeReportTask::kvp022CompletionReceipt,
        ))
    }
fun support.delivery.Kvp023ReadOnlyGraphGateTask.configureReadOnlyGraphGate(
    command: support.delivery.Kvp023GateCommand,
    evidencePath: String,
) {
    group = "verification"
    testClassesDirs = defaultTest.get().testClassesDirs
    classpath = defaultTest.get().classpath
    filter.includeTestsMatching(command.selectorPattern)
    filter.setFailOnNoMatchingTests(true)
    dependsOn(verifyReadRuntimeReportNegative)
    repositoryRootPath.set(rootProject.layout.projectDirectory.asFile.absolutePath)
    declaredCommand.set(command.declaredCommand)
    gateEvidenceFile.set(layout.buildDirectory.file(evidencePath))
    doFirst { beginGateExecution() }
    doLast { completeGateExecution() }
    inputs.file(readRuntimeReport).withPathSensitivity(PathSensitivity.NONE)
    systemProperty(
        "kast.ide.read.runtime.report",
        readRuntimeReport.get().asFile.absolutePath,
    )
}

val verifyReadOnlyGraphNegative = tasks.register<support.delivery.Kvp023ReadOnlyGraphGateTask>(
        "verifyReadOnlyGraphNegative",
    ) {
        description = "Runs only the canonical KVP-023 negative selector at one exact head."
        configureReadOnlyGraphGate(
            support.delivery.Kvp023GateCommand.RED,
            "reports/KVP-023-red-gate.json",
        )
    }
val verifyReadOnlyGraph = tasks.register<support.delivery.Kvp023ReadOnlyGraphGateTask>(
    "verifyReadOnlyGraph",
) {
    description = "Runs only the canonical KVP-023 positive selector at one exact head."
    configureReadOnlyGraphGate(
        support.delivery.Kvp023GateCommand.GREEN,
        "reports/KVP-023-green-gate.json",
    )
    mustRunAfter(defaultTest, verifyReadOnlyGraphNegative, ":recordKVP023RedReceipt")
}
apply(from = "gradle/kvp028-workspace-inspect.gradle.kts")
tasks.named("check") {
    dependsOn(verifySingleFlightReportNegative)
    dependsOn(verifyCancellableReadReportNegative)
    dependsOn(verifyEpochRevalidationReportNegative)
    dependsOn(cancellableReadNegativeGate)
    dependsOn(cancellableReadGate)
    dependsOn(epochRevalidationNegativeGate)
    dependsOn(epochRevalidationGate)
    dependsOn(verifyReadRuntimeReportNegative)
    dependsOn(verifyReadOnlyGraphNegative)
    dependsOn(verifyReadOnlyGraph)
    dependsOn("ideHostedWorkspaceInspectNegativeProof", "ideHostedWorkspaceInspectAcceptance")
}
