import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip
import support.tasks.GenerateControlMetadataTask
import support.tasks.VerifyControlDistributionTask
import support.tasks.VerifySemanticRuntimeDistributionTask

plugins {
    base
    id("kast.architecture")
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
group = providers.gradleProperty("GROUP").get()
val gitDescribeVersion: Provider<String> = providers.exec {
    commandLine("git", "describe", "--tags", "--match", "v*", "--long", "--always")
    workingDir(rootDir)
    isIgnoreExitValue = true
}.standardOutput.asText.map { raw ->
    // raw: v0.6.3-7-gb8c186d (tag-distance-sha) or a bare sha when no tags exist
    val trimmed = raw.trim()
    val regex = Regex("""^v?(\d+\.\d+\.\d+)-(\d+)-g([0-9a-f]+)$""")
    regex.matchEntire(trimmed)?.let { m ->
        val base = m.groupValues[1]
        val distance = m.groupValues[2].toInt()
        val sha = m.groupValues[3]
        if (distance == 0) base else "$base-${m.groupValues[2]}-g$sha"
    } ?: trimmed.removePrefix("v").ifEmpty { "0.0.0-unknown" }
}
version = providers.gradleProperty("version")
    .orElse(providers.gradleProperty("VERSION"))
    .orElse(gitDescribeVersion)
    .get()

subprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.register("stageIndexerDist") {
    group = "distribution"
    description =
        "Builds a clean staged indexer tree under indexer/build/portable-dist/indexer."
    dependsOn(":indexer:syncPortableDist")
}

tasks.register("buildIndexerPortableZip") {
    group = "distribution"
    description = "Builds the versioned portable indexer zip under indexer/build/distributions."
    dependsOn(":indexer:portableDistZip")
}

val installedProductDirectory = layout.buildDirectory.dir("installed-product")

val semanticRuntimeStage = project(":indexer").layout.buildDirectory.dir("portable-dist/indexer")
val semanticRuntimeArchiveName = "kast-semantic-runtime-${project.version}-macos-aarch64.zip"
val semanticRuntimeArchive by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Builds the small private sidecar payload without an IDEA distribution."
    dependsOn(":indexer:syncPortableDist")
    from(semanticRuntimeStage)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set(semanticRuntimeArchiveName)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    eachFile {
        if (relativePath.pathString == "kast-indexer") permissions { unix("755") }
    }
}

tasks.register("assembleKastSemanticRuntimeDist") {
    group = "distribution"
    description = "Assembles the independently installable private sidecar payload."
    dependsOn(semanticRuntimeArchive)
}

val generatedControlMetadata = layout.buildDirectory.dir("generated/control-metadata")
val generatedOperationRegistry = project(":protocol:wire").layout.buildDirectory.file(
    "generated/operation-registry/operation-registry.json",
)
val generateKastControlMetadata by tasks.registering(
    GenerateControlMetadataTask::class,
) {
    group = "distribution"
    description = "Generates the exact installed-IDE sidecar manifest and public schemas."
    dependsOn(semanticRuntimeArchive, ":protocol:wire:generateOperationRegistry")
    runtimeArchive.set(semanticRuntimeArchive.flatMap(Zip::getArchiveFile))
    runtimeDirectory.set(semanticRuntimeStage)
    licenseFile.set(layout.projectDirectory.file("LICENSE"))
    operationRegistryFile.set(generatedOperationRegistry)
    productVersion.set(project.version.toString())
    ideaBuild.set(libs.versions.ide.host.build)
    kotlinPluginBuild.set(libs.versions.ide.kotlin.plugin.build)
    runtimeBaseUrl.set(
        providers.environmentVariable("KAST_RUNTIME_BASE_URL")
            .orElse("https://github.com/amichne/kast/releases/download/v${project.version}"),
    )
    outputDirectory.set(generatedControlMetadata)
}

val controlProductDirectory = layout.buildDirectory.dir("control-product")
val stageKastControlProduct by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stages the plugin-free sidecar Kast control installation."
    dependsOn(":cli:installDist", generateKastControlMetadata)
    into(controlProductDirectory)
    from(project(":cli").layout.buildDirectory.dir("install/kast")) {
        exclude("bin/cli", "bin/kast.bat")
    }
    from(generatedControlMetadata) {
        into("share/kast")
    }
}

val assembleKastControlDist by tasks.registering(Tar::class) {
    group = "distribution"
    description = "Builds the default control-only Kast archive."
    dependsOn(stageKastControlProduct)
    from(controlProductDirectory)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("kast-control-v${project.version}-macos-aarch64.tar.gz")
    compression = Compression.GZIP
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    eachFile {
        if (relativePath.pathString == "bin/kast") permissions { unix("755") }
    }
}

val verifyKastControlDistLayout by tasks.registering(VerifyControlDistributionTask::class) {
    group = "verification"
    description = "Rejects oversized or semantic-payload-bearing control archives."
    dependsOn(assembleKastControlDist)
    controlDirectory.set(controlProductDirectory)
    controlArchive.set(assembleKastControlDist.flatMap(Tar::getArchiveFile))
    maximumArchiveBytes.set(64L * 1024L * 1024L)
    maximumInstalledBytes.set(128L * 1024L * 1024L)
}

apply(from = "distribution/release/sidecar-release.gradle.kts")

val verifyKastSemanticRuntimeDistLayout by tasks.registering(
    VerifySemanticRuntimeDistributionTask::class,
) {
    group = "verification"
    description = "Verifies the private sidecar payload contains no IDEA distribution."
    dependsOn(semanticRuntimeArchive, ":indexer:verifyPortableDistLayout")
    runtimeDirectory.set(semanticRuntimeStage)
}

tasks.register("verifyDistributionContent") {
    group = "verification"
    description = "Verifies control/sidecar separation and required artifact layouts."
    dependsOn(verifyKastControlDistLayout, verifyKastSemanticRuntimeDistLayout)
}

val stageInstalledProduct by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stages the control-only installed Kotlin product."
    dependsOn(stageKastControlProduct)
    into(installedProductDirectory)
    from(controlProductDirectory)
}

val localInstallPrefix = providers.gradleProperty("kastLocalPrefix")
    .map { configuredPrefix ->
        require(configuredPrefix.isNotBlank()) {
            "kastLocalPrefix must name a non-blank installation prefix"
        }
        file(configuredPrefix).toPath().toAbsolutePath().normalize().toFile()
    }
    .orElse(
        providers.systemProperty("user.home")
            .map { userHome -> file(userHome).resolve(".local") },
    )

val localProductDirectory = localInstallPrefix.map { it.resolve("share/kast/local") }
val localLauncherFile = localInstallPrefix.map { it.resolve("bin/kast") }
tasks.register<Exec>("installLocal") {
    group = "distribution"
    description =
        "Installs one coherent Kast product under ~/.local, or -PkastLocalPrefix."
    dependsOn(stageKastControlProduct, semanticRuntimeArchive)
    inputs.dir(controlProductDirectory)
    inputs.file(semanticRuntimeArchive.flatMap(Zip::getArchiveFile))
    inputs.file(layout.projectDirectory.file("packaging/install-local.sh"))
    inputs.property("localInstallPrefix", localInstallPrefix.map { it.absolutePath })
    outputs.dir(localProductDirectory)
    outputs.file(localLauncherFile)
    outputs.upToDateWhen { false }
    environment(
        "KAST_LOCAL_PREFIX",
        localInstallPrefix.get().absolutePath,
    )
    environment(
        "KAST_LOCAL_CONTROL_PRODUCT",
        controlProductDirectory.get().asFile.absolutePath,
    )
    environment(
        "KAST_LOCAL_RUNTIME_ARCHIVE",
        semanticRuntimeArchive.get().archiveFile.get().asFile.absolutePath,
    )
    commandLine("bash", layout.projectDirectory.file("packaging/install-local.sh"))
}

val installedProductTest = tasks.register<Exec>("installedProductTest") {
    group = "verification"
    description = "Executes sidecar metadata and fail-closed demand through the staged product."
    dependsOn(stageInstalledProduct, semanticRuntimeArchive, assembleKastControlDist)
    inputs.dir(installedProductDirectory)
    inputs.file(assembleKastControlDist.flatMap(Tar::getArchiveFile))
    inputs.file(layout.projectDirectory.file("packaging/test-installed-product.sh"))
    outputs.file(
        layout.buildDirectory.file("reports/installed-product/topology-installed-product.json"),
    )
    outputs.upToDateWhen { false }
    environment(
        "KAST_INSTALLED_PRODUCT",
        installedProductDirectory.get().asFile.absolutePath,
    )
    environment(
        "KAST_CONTROL_ARCHIVE",
        assembleKastControlDist.get().archiveFile.get().asFile.absolutePath,
    )
    environment(
        "KAST_SEMANTIC_RUNTIME_ARCHIVE",
        semanticRuntimeArchive.get().archiveFile.get().asFile.absolutePath,
    )
    environment("KAST_PROJECT_ROOT", layout.projectDirectory.asFile.absolutePath)
    environment(
        "KAST_INSTALLED_REPORT_DIRECTORY",
        layout.buildDirectory.dir("reports/installed-product").get().asFile.absolutePath,
    )
    commandLine("bash", layout.projectDirectory.file("packaging/test-installed-product.sh"))
}

tasks.register<Exec>("enterpriseAcceptance") {
    group = "verification"
    description = "Runs the installed sidecar enterprise fixture."
    dependsOn(
        installedProductTest,
        stageInstalledProduct,
        semanticRuntimeArchive,
        ":change:recovery:test",
        ":relation:contract:test",
        ":relation:intellij:test",
        ":relation:service:test",
        ":symbol:intellij:test",
        ":symbol:service:test",
        ":traversal:contract:test",
        ":traversal:service:test",
        ":workspace:service:test",
        "verifyKastArchitecture",
    )
    inputs.dir(installedProductDirectory)
    inputs.file(layout.projectDirectory.file("integration-tests/enterprise_acceptance.py"))
    inputs.file(layout.projectDirectory.file("benchmarks/enterprise-acceptance.json"))
    inputs.dir(layout.projectDirectory.dir("fixtures/enterprise-workspace"))
    outputs.upToDateWhen { false }
    commandLine(
        "python3",
        layout.projectDirectory.file("integration-tests/enterprise_acceptance.py"),
        "--product-root",
        installedProductDirectory.get().asFile.absolutePath,
        "--fixture",
        layout.projectDirectory.dir("fixtures/enterprise-workspace").asFile.absolutePath,
        "--thresholds",
        layout.projectDirectory.file("benchmarks/enterprise-acceptance.json").asFile.absolutePath,
        "--runtime-archive",
        semanticRuntimeArchive.get().archiveFile.get().asFile.absolutePath,
    )
}
