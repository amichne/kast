import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip
import support.tasks.GenerateControlMetadataTask
import support.tasks.GenerateHostedControlMetadataTask
import support.tasks.VerifySemanticRuntimeDistributionTask
import support.hostedwriter.GenerateHostedWriterProgramTask
import support.hostedwriter.WriteHostedWriterReceiptTask

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

val hostedWriterReportDirectory = layout.buildDirectory.dir("reports/hosted-writer")
val generateHostedWriterProgram by tasks.registering(GenerateHostedWriterProgramTask::class) {
    group = "verification"
    description = "Projects the fixed hosted-writer proof graph to deterministic JSON."
    schemaFile.set(layout.projectDirectory.file("gradle/hosted-writer/program.schema.json"))
    outputFile.set(hostedWriterReportDirectory.map { it.file("program.json") })
}

tasks.register<WriteHostedWriterReceiptTask>("writeHostedWriterProgramReceipt") {
    group = "verification"
    description = "Writes the exact-head PROGRAM receipt for the fixed hosted-writer graph."
    dependsOn(generateHostedWriterProgram)
    gateId.set("PROGRAM")
    repositoryDirectory.set(layout.projectDirectory)
    programFile.set(generateHostedWriterProgram.flatMap { it.outputFile })
    schemaFile.set(layout.projectDirectory.file("gradle/hosted-writer/receipt.schema.json"))
    proofArtifacts.from(generateHostedWriterProgram.flatMap { it.outputFile })
    outputFile.set(hostedWriterReportDirectory.map { it.file("receipts/PROGRAM.json") })
}

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
val legacyIsolatedRuntimeFixtureArchive by tasks.registering(Zip::class) {
    group = "verification"
    description = "Builds the explicit non-default isolated-runtime compatibility fixture."
    dependsOn(":indexer:syncPortableDist")
    from(semanticRuntimeStage)
    destinationDirectory.set(layout.buildDirectory.dir("fixtures/isolated-runtime"))
    archiveFileName.set("kast-semantic-runtime-fixture-${project.version}-macos-aarch64.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    eachFile {
        if (relativePath.pathString == "kast-indexer") permissions { unix("755") }
    }
}

tasks.register("assembleLegacyIsolatedRuntimeFixture") {
    group = "verification"
    description = "Assembles the explicit non-default isolated-runtime compatibility fixture."
    dependsOn(legacyIsolatedRuntimeFixtureArchive)
}

val generatedControlMetadata = layout.buildDirectory.dir("generated/control-metadata")
val generatedOperationRegistry = project(":protocol:wire").layout.buildDirectory.file(
    "generated/operation-registry/operation-registry.json",
)
val generateLegacyIsolatedRuntimeFixtureMetadata by tasks.registering(
    GenerateControlMetadataTask::class,
) {
    group = "verification"
    description = "Generates metadata only for the explicit isolated-runtime fixture."
    dependsOn(legacyIsolatedRuntimeFixtureArchive, ":protocol:wire:generateOperationRegistry")
    runtimeArchive.set(legacyIsolatedRuntimeFixtureArchive.flatMap(Zip::getArchiveFile))
    runtimeDirectory.set(semanticRuntimeStage)
    licenseFile.set(layout.projectDirectory.file("LICENSE"))
    operationRegistryFile.set(generatedOperationRegistry)
    productVersion.set(project.version.toString())
    ideaBuild.set(libs.versions.idea.platform.build)
    kotlinPluginBuild.set(libs.versions.kotlin)
    runtimeBaseUrl.set(
        providers.environmentVariable("KAST_RUNTIME_BASE_URL")
            .orElse("https://github.com/amichne/kast/releases/download/v${project.version}"),
    )
    outputDirectory.set(generatedControlMetadata)
}

val generatedHostedControlMetadata = layout.buildDirectory.dir("generated/hosted-control-metadata")
val generateHostedControlMetadata by tasks.registering(GenerateHostedControlMetadataTask::class) {
    group = "distribution"
    description = "Generates exact IDE-hosted control metadata without isolated-runtime authority."
    dependsOn(":protocol:wire:generateOperationRegistry")
    licenseFile.set(layout.projectDirectory.file("LICENSE"))
    operationRegistryFile.set(generatedOperationRegistry)
    outputDirectory.set(generatedHostedControlMetadata)
}

val controlProductDirectory = layout.buildDirectory.dir("control-product")
val stageKastControlProduct by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stages the IDE-hosted Kast control installation."
    dependsOn(":cli:installDist", generateHostedControlMetadata)
    into(controlProductDirectory)
    from(project(":cli").layout.buildDirectory.dir("install/kast")) {
        exclude("bin/cli", "bin/kast.bat")
    }
    from(generatedHostedControlMetadata) {
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

apply(from = "distribution/release/ide-hosted-release.gradle.kts")

val verifyLegacyIsolatedRuntimeFixtureLayout by tasks.registering(
    VerifySemanticRuntimeDistributionTask::class,
) {
    group = "verification"
    description = "Verifies the explicit non-default isolated-runtime fixture layout."
    dependsOn(legacyIsolatedRuntimeFixtureArchive, ":indexer:verifyPortableDistLayout")
    runtimeDirectory.set(semanticRuntimeStage)
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

val installLocalControl by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Installs the current Kast control product into the local prefix."
    dependsOn(stageKastControlProduct)
    from(controlProductDirectory)
    into(localInstallPrefix.map { it.resolve("share/kast/control") })
}

val purgeLocalSemanticRuntime by tasks.registering(Delete::class) {
    group = "distribution"
    description = "Removes legacy semantic runtime payloads from the default local install."
    delete(localInstallPrefix.map { it.resolve("share/kast/runtime") })
}

val localLauncherContent = providers.provider {
    $$"""
        |#!/bin/sh
        |set -eu
        |
        |script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)"
        |install_prefix="$(CDPATH= cd -- "${script_dir}/.." && pwd -P)"
        |control_executable="${install_prefix}/share/kast/control/bin/kast"
        |
        |if [ ! -x "${control_executable}" ]; then
        |  echo "kast: installed control executable is missing: ${control_executable}" >&2
        |  exit 1
        |fi
        |
        |exec "${control_executable}" "$@"
        """.trimMargin()
}

val localLauncherFile = localInstallPrefix.map { it.resolve("bin/kast") }
val installLocalLauncher = tasks.register<Exec>("installLocalLauncher") {
    group = "distribution"
    description = "Installs the relocatable local Kast launcher."
    dependsOn(installLocalControl, purgeLocalSemanticRuntime)
    inputs.property("launcherContent", localLauncherContent)
    outputs.file(localLauncherFile)
    outputs.upToDateWhen { false }
    commandLine(
        "bash",
        "-c",
        $$"""
        |set -eu
        |launcher="$1"
        |launcher_content="$2"
        |launcher_directory="$(dirname -- "${launcher}")"
        |mkdir -p -- "${launcher_directory}"
        |temporary_launcher="$(mktemp "${launcher_directory}/.kast.XXXXXX")"
        |cleanup() {
        |  rm -f -- "${temporary_launcher}"
        |}
        |trap cleanup EXIT
        |printf '%s' "${launcher_content}" >"${temporary_launcher}"
        |chmod 755 "${temporary_launcher}"
        |mv -f -- "${temporary_launcher}" "${launcher}"
        |trap - EXIT
        """.trimMargin(),
        "install-local-launcher",
        localLauncherFile.get().absolutePath,
        localLauncherContent.get(),
    )
}

tasks.register("installLocal") {
    group = "distribution"
    description =
        "Installs Kast under ~/.local, or the prefix selected by -PkastLocalPrefix."
    dependsOn(installLocalLauncher)
}

val installedProductTest = tasks.register<Exec>("installedProductTest") {
    group = "verification"
    description = "Executes hosted metadata and fail-closed demand through the staged product."
    dependsOn(stageInstalledProduct, assembleKastControlDist)
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
    environment("KAST_PROJECT_ROOT", layout.projectDirectory.asFile.absolutePath)
    environment(
        "KAST_INSTALLED_REPORT_DIRECTORY",
        layout.buildDirectory.dir("reports/installed-product").get().asFile.absolutePath,
    )
    commandLine("bash", layout.projectDirectory.file("packaging/test-installed-product.sh"))
}

tasks.register<Exec>("legacyIsolatedRuntimeFixtureAcceptance") {
    group = "verification"
    description = "Runs the explicit non-default isolated-runtime enterprise fixture."
    dependsOn(
        installedProductTest,
        stageInstalledProduct,
        legacyIsolatedRuntimeFixtureArchive,
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
        legacyIsolatedRuntimeFixtureArchive.get().archiveFile.get().asFile.absolutePath,
    )
}
