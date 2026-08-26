import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("kast.kotlin-library")
    kotlin("plugin.serialization")
    id("kast.role.ide-read-only")
}

group = "${rootProject.group}.workspace"

base {
    archivesName.set("workspace-intellij-read")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideHostBuild = catalog.findVersion("ide-host-build").get().requiredVersion

val workspaceReadIdeaDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

private val extractedIdeaDistributionDirectory = objects.directoryProperty().apply {
    set(file(gradle.gradleUserHomeDir.resolve(
        "kast/workspace-intellij-read-idea-distributions/$ideHostBuild",
    )))
}

val extractWorkspaceReadIdeaDistribution by tasks.registering(ExtractIdeaDistributionTask::class) {
    archives.from(workspaceReadIdeaDistribution)
    ideaVersion.set(ideHostBuild)
    outputDirectory.set(extractedIdeaDistributionDirectory)
}

private fun extractedIdeaFiles(
    configure: ConfigurableFileTree.() -> Unit,
) = files(
    extractedIdeaDistributionDirectory.map { directory ->
        fileTree(directory) { configure() }
    },
).builtBy(extractWorkspaceReadIdeaDistribution)

private val ideaLibraries: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/lib/**/*.jar")
    exclude("**/plugins/**")
    exclude("**/lib/intellij.libraries.kotlinx.serialization.*.jar")
    exclude("**/lib/intellij.libraries.ktor.utils.jar")
}

private val kotlinPluginLibraries: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/Kotlin/lib/kotlin-plugin.jar")
    include("**/plugins/Kotlin/lib/kotlin-plugin-shared.jar")
}

dependencies {
    implementation(project(":protocol:contract"))
    implementation(project(":workspace:contract"))

    workspaceReadIdeaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideHostBuild@zip") {
        isTransitive = false
    }
    compileOnly(ideaLibraries)
    compileOnly(kotlinPluginLibraries)
    testImplementation(ideaLibraries)
    testImplementation(kotlinPluginLibraries)
    testImplementation(catalog.findLibrary("serialization-json").get())
}

private val workspaceContractFriendPath =
    project(":workspace:contract").layout.buildDirectory.dir("classes/kotlin/main")

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(":workspace:contract:compileKotlin")
    compilerOptions.freeCompilerArgs.add(
        workspaceContractFriendPath.map { directory ->
            "-Xfriend-paths=${directory.asFile.absolutePath}"
        },
    )
}

val projectAdmissionReport = layout.buildDirectory.file(
    "reports/KVP-014-project-admission.json",
)

val generateExistingProjectAdmissionReport =
    tasks.register<support.delivery.GenerateKvp014ProjectAdmissionReportTask>(
        "generateExistingProjectAdmissionReport",
    ) {
        group = "verification"
        description = "Generates the canonical KVP-014 existing-Project admission report."
        reportFile.set(projectAdmissionReport)
    }

val epochSignalLedgerReport = layout.buildDirectory.file(
    "reports/KVP-015-epoch-ledger.json",
)

val generateEpochSignalLedgerReport =
    tasks.register<support.delivery.GenerateKvp015EpochLedgerReportTask>(
        "generateEpochSignalLedgerReport",
    ) {
        group = "verification"
        description = "Generates the canonical KVP-015 epoch-signal ledger."
        reportFile.set(epochSignalLedgerReport)
    }

val detachedModelReport = layout.buildDirectory.file(
    "reports/KVP-016-detached-model.json",
)

val generateDetachedModelReport =
    tasks.register<support.delivery.GenerateKvp016DetachedModelReportTask>(
        "generateDetachedModelReport",
    ) {
        group = "verification"
        description = "Generates the canonical KVP-016 detached-model report."
        reportFile.set(detachedModelReport)
    }

val projectReadEpochReport = layout.buildDirectory.file(
    "reports/KVP-017-read-epoch.json",
)

val generateProjectReadEpochReport =
    tasks.register<support.delivery.GenerateKvp017ReadEpochReportTask>(
        "generateProjectReadEpochReport",
    ) {
        group = "verification"
        description = "Generates the canonical KVP-017 project-read epoch report."
        reportFile.set(projectReadEpochReport)
    }

val verifyProjectReadEpochReportNegative =
    tasks.register<support.delivery.VerifyKvp017ReadEpochReportNegativeTask>(
        "verifyProjectReadEpochReportNegative",
    ) {
        group = "verification"
        description = "Rejects every finite KVP-017 report mutation."
        dependsOn(generateProjectReadEpochReport)
        reportFile.set(projectReadEpochReport)
    }

tasks.register<support.architecture.gradle.VerifyNoHostedRepositoryWalkNegativeTask>(
    "verifyNoHostedRepositoryWalkNegative",
) {
    group = "verification"
    description = "Detects every fixed KVP-018 forbidden hosted-read JVM reference."
}

val verifyNoHostedRepositoryWalk =
    tasks.register<support.architecture.gradle.VerifyNoHostedRepositoryWalkTask>(
        "verifyNoHostedRepositoryWalk",
    ) {
        group = "verification"
        description = "Admits the complete KVP-018 hosted production class inventory."
        dependsOn("classes", ":verifyKVP016CompletionReceipt", ":verifyKVP017CompletionReceipt")
        compiledClassDirectories.from(sourceSets.named("main").map { it.output.classesDirs })
        requiredClassNames.set(listOf(
            "io/github/amichne/kast/workspace/intellij/read/AdmittedIdeProject.class",
            "io/github/amichne/kast/workspace/intellij/read/LiveDetachedModelCapture.class",
            "io/github/amichne/kast/workspace/intellij/read/LiveProjectReadEpochSource.class",
            "io/github/amichne/kast/workspace/intellij/read/RootFilteredProjectEpochVfsListener.class",
        ))
        val runtimeArtifacts = configurations.getByName("runtimeClasspath").incoming
        val projectArtifacts = runtimeArtifacts.artifactView {
            componentFilter { it is ProjectComponentIdentifier }
        }.artifacts
        val projectArtifactResults = projectArtifacts.resolvedArtifacts.map { artifacts ->
            artifacts
                .sortedBy { artifact ->
                    val component = artifact.id.componentIdentifier as ProjectComponentIdentifier
                    "${component.projectPath}|${artifact.file.name}"
                }
        }
        runtimeProjectArtifactIdentities.set(projectArtifactResults.map { artifacts ->
            artifacts.map { artifact ->
                val component = artifact.id.componentIdentifier as ProjectComponentIdentifier
                "${component.projectPath}|${artifact.file.name}"
            }
        })
        runtimeProjectArtifactFiles.from(projectArtifacts.artifactFiles)
        val externalArtifacts = runtimeArtifacts.artifactView {
            componentFilter { it !is ProjectComponentIdentifier }
        }.artifacts
        val externalArtifactResults = externalArtifacts.resolvedArtifacts.map { artifacts ->
            artifacts.sortedBy { it.file.name }
        }
        runtimeExternalArtifactIdentities.set(externalArtifactResults.map { artifacts ->
            artifacts.map { artifact ->
                val component = artifact.id.componentIdentifier
                val identity = when (component) {
                    is ModuleComponentIdentifier ->
                        "${component.group}:${component.module}:${component.version}"
                    else -> "UNSUPPORTED:${component.displayName}"
                }
                "$identity|${artifact.file.name}"
            }
        })
        runtimeExternalArtifactFiles.from(externalArtifacts.artifactFiles)
        kvp016CompletionReceipt.set(
            rootProject.layout.buildDirectory.file(
                "reports/delivery/receipts/KVP-016-COMPLETE.receipt.json",
            ),
        )
        kvp017CompletionReceipt.set(
            rootProject.layout.buildDirectory.file(
                "reports/delivery/receipts/KVP-017-COMPLETE.receipt.json",
            ),
        )
        reportFile.set(layout.buildDirectory.file("reports/KVP-018-no-walk.json"))
    }

val vfsPassiveReport = layout.buildDirectory.file(
    "reports/KVP-019-vfs-passive.json",
)

val generateVfsPassiveReport =
    tasks.register<support.delivery.GenerateKvp019VfsPassiveReportTask>(
        "generateVfsPassiveReport",
    ) {
        group = "verification"
        description = "Generates the exact predecessor-bound KVP-019 freshness report."
        dependsOn(rootProject.tasks.named("verifyKVP018CompletionReceipt"))
        kvp017CompletionReceipt.set(rootProject.layout.buildDirectory.file(
            "reports/delivery/receipts/KVP-017-COMPLETE.receipt.json",
        ))
        kvp018CompletionReceipt.set(rootProject.layout.buildDirectory.file(
            "reports/delivery/receipts/KVP-018-COMPLETE.receipt.json",
        ))
        reportFile.set(vfsPassiveReport)
    }

val verifyVfsPassiveReportNegative =
    tasks.register<support.delivery.VerifyKvp019VfsPassiveReportNegativeTask>(
        "verifyVfsPassiveReportNegative",
    ) {
        group = "verification"
        description = "Rejects every fixed KVP-019 report mutation."
        dependsOn(generateVfsPassiveReport)
        reportFile.set(vfsPassiveReport)
        kvp017CompletionReceipt.set(generateVfsPassiveReport.flatMap(
            support.delivery.GenerateKvp019VfsPassiveReportTask::kvp017CompletionReceipt,
        ))
        kvp018CompletionReceipt.set(generateVfsPassiveReport.flatMap(
            support.delivery.GenerateKvp019VfsPassiveReportTask::kvp018CompletionReceipt,
        ))
    }

tasks.withType<Test>().configureEach {
    exclude("**/EpochSignalApiContract.class")
    dependsOn(generateExistingProjectAdmissionReport)
    inputs.file(generateExistingProjectAdmissionReport.flatMap(
        support.delivery.GenerateKvp014ProjectAdmissionReportTask::reportFile,
    )).withPathSensitivity(PathSensitivity.NONE)
    systemProperty(
        "kast.existing.project.admission.report",
        generateExistingProjectAdmissionReport.flatMap(
            support.delivery.GenerateKvp014ProjectAdmissionReportTask::reportFile,
        ).get().asFile.absolutePath,
    )
    dependsOn(generateEpochSignalLedgerReport)
    inputs.file(generateEpochSignalLedgerReport.flatMap(
        support.delivery.GenerateKvp015EpochLedgerReportTask::reportFile,
    )).withPathSensitivity(PathSensitivity.NONE)
    systemProperty(
        "kast.ide.epoch.ledger.report",
        generateEpochSignalLedgerReport.flatMap(
            support.delivery.GenerateKvp015EpochLedgerReportTask::reportFile,
        ).get().asFile.absolutePath,
    )
    dependsOn(generateDetachedModelReport)
    inputs.file(generateDetachedModelReport.flatMap(
        support.delivery.GenerateKvp016DetachedModelReportTask::reportFile,
    )).withPathSensitivity(PathSensitivity.NONE)
    systemProperty(
        "kast.ide.detached.model.report",
        generateDetachedModelReport.flatMap(
            support.delivery.GenerateKvp016DetachedModelReportTask::reportFile,
        ).get().asFile.absolutePath,
    )
    dependsOn(generateProjectReadEpochReport)
    dependsOn(verifyProjectReadEpochReportNegative)
    inputs.file(generateProjectReadEpochReport.flatMap(
        support.delivery.GenerateKvp017ReadEpochReportTask::reportFile,
    )).withPathSensitivity(PathSensitivity.NONE)
    systemProperty(
        "kast.ide.project.read.epoch.report",
        generateProjectReadEpochReport.flatMap(
            support.delivery.GenerateKvp017ReadEpochReportTask::reportFile,
        ).get().asFile.absolutePath,
    )
}

val defaultTest = tasks.named<Test>("test")

defaultTest.configure {
    mustRunAfter(verifyVfsPassiveReportNegative)
    inputs.file(vfsPassiveReport).optional().withPathSensitivity(PathSensitivity.NONE)
    systemProperty(
        "kast.ide.vfs.passive.report",
        vfsPassiveReport.get().asFile.absolutePath,
    )
    systemProperty(
        "kast.ide.vfs.passive.kvp017.receipt",
        rootProject.layout.buildDirectory.file(
            "reports/delivery/receipts/KVP-017-COMPLETE.receipt.json",
        ).get().asFile.absolutePath,
    )
    systemProperty(
        "kast.ide.vfs.passive.kvp018.receipt",
        rootProject.layout.buildDirectory.file(
            "reports/delivery/receipts/KVP-018-COMPLETE.receipt.json",
        ).get().asFile.absolutePath,
    )
}

val characterizeEpochNegative = tasks.register<Test>("characterizeEpochNegative") {
    group = "verification"
    description = "Rejects incomplete or forbidden KVP-015 epoch-signal ledgers."
    testClassesDirs = defaultTest.get().testClassesDirs
    classpath = defaultTest.get().classpath
    setScanForTestClasses(false)
    include("**/EpochSignalCharacterizationNegativeTest.class")
}

val characterizeEpoch = tasks.register<Test>("characterizeEpoch") {
    group = "verification"
    description = "Characterizes the complete supported KVP-015 epoch-signal ledger."
    testClassesDirs = defaultTest.get().testClassesDirs
    classpath = defaultTest.get().classpath
    setScanForTestClasses(false)
    include("**/EpochSignalCharacterizationTest.class")
}

tasks.named("check") {
    dependsOn(generateExistingProjectAdmissionReport)
    dependsOn(generateEpochSignalLedgerReport)
    dependsOn(generateDetachedModelReport)
    dependsOn(generateProjectReadEpochReport)
    dependsOn(verifyProjectReadEpochReportNegative)
    dependsOn(generateVfsPassiveReport)
    dependsOn(verifyVfsPassiveReportNegative)
    dependsOn(characterizeEpochNegative)
    dependsOn(characterizeEpoch)
    dependsOn("verifyNoHostedRepositoryWalkNegative")
}
