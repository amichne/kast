import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip
import support.tasks.GenerateControlMetadataTask
import support.tasks.VerifyControlDistributionTask
import support.tasks.VerifySemanticRuntimeDistributionTask
import support.tasks.registerGeneratedBuildLogicSerializationVerification

plugins {
    base
    id("kast.architecture")
    id("kast.pr633-stack")
    id("kast.pr633-topology")
    id("kast.pr633-delivery")
    id("kast.vfs-passive-delivery")
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

registerGeneratedBuildLogicSerializationVerification()

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
val semanticRuntimeArchive by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Builds the independently acquired IntelliJ/K2 semantic runtime."
    dependsOn(":indexer:syncPortableDist")
    from(semanticRuntimeStage)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("kast-semantic-runtime-${project.version}-macos-aarch64.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    eachFile {
        if (relativePath.pathString == "kast-indexer") permissions { unix("755") }
    }
}

tasks.register("assembleKastSemanticRuntimeDist") {
    group = "distribution"
    description = "Assembles the independently installable semantic runtime archive."
    dependsOn(semanticRuntimeArchive)
}

val generatedControlMetadata = layout.buildDirectory.dir("generated/control-metadata")
val generatedOperationRegistry = project(":protocol:wire").layout.buildDirectory.file(
    "generated/operation-registry/operation-registry.json",
)
val generateKastControlMetadata by tasks.registering(GenerateControlMetadataTask::class) {
    group = "distribution"
    description = "Generates the exact runtime manifest and public schema resources."
    dependsOn(semanticRuntimeArchive, ":protocol:wire:generateOperationRegistry")
    runtimeArchive.set(semanticRuntimeArchive.flatMap(Zip::getArchiveFile))
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

val controlProductDirectory = layout.buildDirectory.dir("control-product")
val stageKastControlProduct by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stages the control-only Kast installation."
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
    description = "Rejects oversized or semantic-runtime-bearing control archives."
    dependsOn(assembleKastControlDist)
    controlDirectory.set(controlProductDirectory)
    controlArchive.set(assembleKastControlDist.flatMap(Tar::getArchiveFile))
    maximumArchiveBytes.set(64L * 1024L * 1024L)
    maximumInstalledBytes.set(128L * 1024L * 1024L)
}

apply(from = "distribution/release/ide-hosted-release.gradle.kts")

val verifyKastSemanticRuntimeDistLayout by tasks.registering(
    VerifySemanticRuntimeDistributionTask::class,
) {
    group = "verification"
    description = "Verifies the independently packaged semantic runtime layout."
    dependsOn(semanticRuntimeArchive, ":indexer:verifyPortableDistLayout")
    runtimeDirectory.set(semanticRuntimeStage)
}

tasks.register("verifyDistributionContent") {
    group = "verification"
    description = "Verifies control/runtime content separation and required artifact layouts."
    dependsOn(verifyKastControlDistLayout, verifyKastSemanticRuntimeDistLayout)
}

tasks.register("verifyDistributionSize") {
    group = "verification"
    description = "Enforces the control archive and installed-size ceilings."
    dependsOn(verifyKastControlDistLayout)
}

tasks.register("runtimeDeliveryMvpAcceptance") {
    group = "verification"
    description = "Proves the control-only install, exact cold acquisition, and warm reuse journey."
    dependsOn(
        ":distribution:contract:test",
        ":distribution:managed:test",
        ":cli:check",
        ":indexer:test",
        "verifyDistributionContent",
        "verifyDistributionSize",
        "installedProductTest",
    )
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
    description = "Executes the public target surface through only the staged installed product."
    dependsOn(stageInstalledProduct, semanticRuntimeArchive, assembleKastControlDist)
    inputs.dir(installedProductDirectory)
    inputs.file(assembleKastControlDist.flatMap(Tar::getArchiveFile))
    inputs.file(layout.projectDirectory.file("packaging/test-installed-product.sh"))
    inputs.file(layout.projectDirectory.file("packaging/topology_installed_acceptance.py"))
    inputs.file(layout.projectDirectory.file("packaging/topology_installed_support.py"))
    outputs.file(
        layout.buildDirectory.file("reports/installed-product/topology-installed-product.json"),
    )
    outputs.upToDateWhen { false }
    environment(
        "KAST_INSTALLED_PRODUCT",
        installedProductDirectory.get().asFile.absolutePath,
    )
    environment(
        "KAST_SEMANTIC_RUNTIME_ARCHIVE",
        semanticRuntimeArchive.get().archiveFile.get().asFile.absolutePath,
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

tasks.register<Exec>("enterpriseAcceptance") {
    group = "verification"
    description = "Proves the installed product against enterprise-scale and failure bounds."
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
        "verifyForbiddenEffects",
        "verifyKastModuleGraph",
        "verifyNoLegacyArchitecture",
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

val buildLogicTests = gradle.includedBuild("build-logic").task(":test")
val topologyAcceptanceChecks: Map<String, List<Any>> = mapOf(
    "topologyEnumerationAcceptance" to listOf(":topology:intellij:test"),
    "topologyCoverageAcceptance" to listOf(
        ":topology:contract:test",
        ":topology:build:test",
        ":protocol:registry:test",
        ":protocol:wire:test",
        ":cli:test",
    ),
    "topologyFailureAtomicityAcceptance" to listOf(
        ":topology:build:test",
        ":evidence:sqlite:test",
    ),
    "topologyRestartAcceptance" to listOf(":evidence:sqlite:test"),
    "topologyReuseAcceptance" to listOf(":topology:build:test", ":evidence:sqlite:test"),
    "topologyStalenessAcceptance" to listOf(
        ":topology:build:test",
        ":workspace:intellij:test",
        ":evidence:sqlite:test",
    ),
    "topologyRebuildRollbackAcceptance" to listOf(":evidence:sqlite:test"),
    "topologyGraphReadAcceptance" to listOf(
        ":topology:service:test",
        ":traversal:service:test",
        ":runtime:composition:test",
    ),
    "topologyDeterminismAcceptance" to listOf(":topology:service:test", ":evidence:sqlite:test"),
    "verifyTopologyAuthority" to listOf(
        buildLogicTests,
        "verifyKastModuleGraph",
        "verifyForbiddenEffects",
    ),
    "topologyScaleAcceptance" to listOf("enterpriseAcceptance"),
)

topologyAcceptanceChecks.forEach { (name, dependencies) ->
    tasks.register(name) {
        group = "verification"
        description = "Runs the $name proof ring."
        dependsOn(dependencies)
    }
}

tasks.register("topologyAcceptance") {
    group = "verification"
    description = "Proves the explicit generation-bound topology snapshot contract."
    dependsOn(topologyAcceptanceChecks.keys)
}
