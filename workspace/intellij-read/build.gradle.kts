import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

plugins {
    id("kast.kotlin-serialization")
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
}

val defaultTest = tasks.named<Test>("test")

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
    dependsOn(characterizeEpochNegative)
    dependsOn(characterizeEpoch)
}
