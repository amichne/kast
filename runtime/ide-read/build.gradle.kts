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

dependencies {
    implementation(project(":workspace:contract"))
    testImplementation(catalog.findLibrary("serialization-json").get())
}

private val workspaceContractFriendPath =
    project(":workspace:contract").layout.buildDirectory.dir("classes/kotlin/main")

tasks.named<KotlinCompile>("compileTestKotlin") {
    dependsOn(":workspace:contract:compileKotlin")
    compilerOptions.freeCompilerArgs.add(
        workspaceContractFriendPath.map { directory ->
            "-Xfriend-paths=${directory.asFile.absolutePath}"
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

tasks.named<Test>("test") {
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
    doFirst {
        systemProperty(
            "kast.ide.single.flight.expected.head",
            singleFlightExpectedHead.get(),
        )
    }
}

tasks.named("check") {
    dependsOn(verifySingleFlightReportNegative)
}
