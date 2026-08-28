import java.util.Base64
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import support.plugin.BuildStandalonePluginTask
import support.plugin.GenerateIdeHostCompatibilityReportTask

plugins {
    id("kast.kotlin-serialization")
    id("kast.role.ide-read-only")
}

group = "${rootProject.group}.ide"

base {
    archivesName.set("kast-ide-plugin")
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
    implementation(project(":protocol:contract"))
    implementation(project(":protocol:wire"))
    implementation(project(":runtime:ide-read"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:intellij-read"))
    compileOnly(workspaceIdeaLibraries)
    testImplementation(workspaceIdeaLibraries)
}

private val runtimeIdeReadFriendPath =
    project(":runtime:ide-read").layout.buildDirectory.dir("classes/kotlin/main")

tasks.named<KotlinCompile>("compileTestKotlin") {
    dependsOn(":runtime:ide-read:compileKotlin")
    compilerOptions.freeCompilerArgs.add(
        runtimeIdeReadFriendPath.map { directory ->
            "-Xfriend-paths=${directory.asFile.absolutePath}"
        },
    )
}

tasks.named<Jar>("jar") {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val buildPlugin by tasks.registering(BuildStandalonePluginTask::class) {
    group = "build"
    description = "Builds the deterministic standalone Kast IntelliJ plugin ZIP."
    dependsOn(tasks.named("jar"))
    payloadJars.from(tasks.named<Jar>("jar").flatMap(Jar::getArchiveFile))
    payloadJars.from(configurations.runtimeClasspath)
    pluginArchive.set(
        layout.buildDirectory.file("distributions/kast-ide-plugin-${project.version}.zip"),
    )
}

val operationRegistryArtifact = project(":protocol:wire").layout.buildDirectory.file(
    "generated/operation-registry/operation-registry.json",
)

val generateIdeHostCompatibilityReport by tasks.registering(
    GenerateIdeHostCompatibilityReportTask::class,
) {
    group = "build"
    description = "Generates the IDE-host compatibility metadata embedded in the plugin."
    dependsOn(":protocol:wire:generateOperationRegistry")
    ideBuild.set(libs.versions.ide.host.build)
    kotlinPluginBuild.set(libs.versions.ide.kotlin.plugin.build)
    kastPluginVersion.set(project.version.toString())
    runtimeProtocolIdentity.set("kast.ide-hosted.runtime.v1")
    operationRegistryFile.set(operationRegistryArtifact)
    reportFile.set(layout.buildDirectory.file("generated/ide-host-compatibility.json"))
}

val generatedCompatibilitySourceDirectory = layout.buildDirectory.dir(
    "generated/sources/ideHostCompatibility/kotlin",
)
val generatedCompatibilitySource = generatedCompatibilitySourceDirectory.map { directory ->
    directory.file(
        "io/github/amichne/kast/ide/compatibility/GeneratedIdeHostCompatibilityMetadata.kt",
    )
}
val compatibilityReportFile = generateIdeHostCompatibilityReport.flatMap(
    GenerateIdeHostCompatibilityReportTask::reportFile,
)
val generatedCompatibilitySourceText = providers.fileContents(compatibilityReportFile).asBytes.map {
    report ->
    val encoded = Base64.getEncoder().encodeToString(report)
    """
        package io.github.amichne.kast.ide.compatibility

        import java.nio.charset.StandardCharsets
        import java.util.Base64

        /** Build-generated host metadata embedded as class data, not an archive read. */
        internal object GeneratedIdeHostCompatibilityMetadata {
            val document: String = String(
                Base64.getDecoder().decode("$encoded"),
                StandardCharsets.UTF_8,
            )
        }
    """.trimIndent() + "\n"
}

val generateIdeHostCompatibilitySource by tasks.registering {
    group = "build"
    description = "Compiles IDE-host compatibility metadata into the plugin."
    dependsOn(generateIdeHostCompatibilityReport)
    inputs.file(compatibilityReportFile).withPathSensitivity(PathSensitivity.NONE)
    inputs.property("generatedSource", generatedCompatibilitySourceText)
    outputs.file(generatedCompatibilitySource)
    doLast {
        val output = outputs.files.singleFile
        output.parentFile.mkdirs()
        output.writeText(inputs.properties.getValue("generatedSource").toString())
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generatedCompatibilitySourceDirectory)
}

tasks.named<KotlinCompile>("compileKotlin") {
    dependsOn(generateIdeHostCompatibilitySource)
}

tasks.named<Jar>("sourcesJar") {
    dependsOn(generateIdeHostCompatibilitySource)
}

tasks.withType<Test>().configureEach {
    dependsOn(generateIdeHostCompatibilityReport)
    inputs.file(generateIdeHostCompatibilityReport.flatMap(
        GenerateIdeHostCompatibilityReportTask::reportFile,
    )).withPathSensitivity(PathSensitivity.NONE)
    systemProperty(
        "kast.ide.compatibility.report",
        generateIdeHostCompatibilityReport.flatMap(
            GenerateIdeHostCompatibilityReportTask::reportFile,
        ).get().asFile.absolutePath,
    )
    systemProperty("kast.ide.compatibility.plugin-version", project.version.toString())
}

tasks.named("assemble") {
    dependsOn(buildPlugin)
}
