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
    description = "Builds a clean staged indexer tree under indexer/build/portable-dist/indexer."
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
    description = "Assembles the separately published private sidecar admitted by its matched control."
    dependsOn(semanticRuntimeArchive)
}

val generatedControlMetadata = layout.buildDirectory.dir("generated/control-metadata")
val generatedOperationRegistry = project(":protocol:wire").layout.buildDirectory.file(
    "generated/operation-registry/operation-registry.json",
)
val generatedConfigurationCatalogue = project(":cli").layout.buildDirectory.file(
    "generated/configuration/configuration-schema.json",
)
val generateKastControlMetadata by tasks.registering(GenerateControlMetadataTask::class) {
    group = "distribution"
    description = "Generates the exact installed-IDE sidecar manifest and public schemas."
    dependsOn(semanticRuntimeArchive, ":protocol:wire:generateOperationRegistry", ":cli:generateConfigurationCatalogue")
    runtimeArchive.set(semanticRuntimeArchive.flatMap(Zip::getArchiveFile))
    runtimeDirectory.set(semanticRuntimeStage)
    licenseFile.set(layout.projectDirectory.file("LICENSE"))
    operationRegistryFile.set(generatedOperationRegistry)
    configurationCatalogueFile.set(generatedConfigurationCatalogue)
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
    from("packaging/installation-lifecycle.py") { into("share/kast") }
}

val assembleKastControlDist by tasks.registering(Tar::class) {
    group = "distribution"
    description = "Builds the public CLI, lifecycle, schema, broker, and wire-control archive."
    dependsOn(stageKastControlProduct)
    from(controlProductDirectory)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("kast-control-v${project.version}-macos-aarch64.tar.gz")
    compression = Compression.GZIP
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    eachFile {
        if (relativePath.pathString in setOf("bin/kast", "bin/kast-codex")) permissions { unix("755") }
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

val verifyKastSemanticRuntimeDistLayout by tasks.registering(VerifySemanticRuntimeDistributionTask::class) {
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
        require(configuredPrefix.isNotBlank()) { "kastLocalPrefix must name a non-blank installation prefix" }
        file(configuredPrefix).toPath().toAbsolutePath().normalize().toFile()
    }
    .orElse(
        providers.systemProperty("user.home")
            .map { userHome -> file(userHome).resolve(".local") },
    )
val localProductDirectory = localInstallPrefix.map { it.resolve("share/kast/current") }
val localLauncherFile = localInstallPrefix.map { it.resolve("bin/kast") }
val localJavaHome = providers.systemProperty("java.home").map { configuredHome ->
    file(configuredHome).toPath().toRealPath().toFile()
}
val localJavaExecutable = localJavaHome.map { home -> home.resolve("bin/java") }

tasks.register<Exec>("installLocal") {
    group = "distribution"
    description = "Installs a version-owned Kast product (-Pversion=x.y.z) under ~/.local or -PkastLocalPrefix."
    doNotTrackState("The installed prefix contains live service sockets and is mutated by the versioned installer.")
    dependsOn(stageKastControlProduct, semanticRuntimeArchive)
    inputs.dir(controlProductDirectory)
    inputs.file(semanticRuntimeArchive.flatMap(Zip::getArchiveFile))
    inputs.files("packaging/install-local.sh", "install.sh")
    inputs.property("localInstallPrefix", localInstallPrefix.map { it.absolutePath })
    inputs.property("localJavaHome", localJavaHome.map { it.absolutePath })
    inputs.property("localJavaExecutable", localJavaExecutable.map { it.absolutePath })
    environment("KAST_LOCAL_PREFIX", localInstallPrefix.get().absolutePath)
    environment("KAST_LOCAL_CONTROL_PRODUCT", controlProductDirectory.get().asFile.absolutePath)
    environment(
        "KAST_LOCAL_RUNTIME_ARCHIVE",
        semanticRuntimeArchive.get().archiveFile.get().asFile.absolutePath,
    )
    environment("KAST_LOCAL_JAVA_HOME", localJavaHome.get().absolutePath)
    environment("KAST_LOCAL_JAVA_EXECUTABLE", localJavaExecutable.get().absolutePath)
    commandLine("bash", layout.projectDirectory.file("packaging/install-local.sh"))
}

val installedProductTest = tasks.register<Exec>("installedProductTest") {
    group = "verification"
    description = "Executes sidecar metadata and fail-closed demand through the staged product."
    dependsOn(stageInstalledProduct, semanticRuntimeArchive, assembleKastControlDist)
    inputs.dir(installedProductDirectory)
    inputs.file(assembleKastControlDist.flatMap(Tar::getArchiveFile))
    inputs.file(layout.projectDirectory.file("packaging/test-installed-product.sh"))
    inputs.files("packaging/acceptance_environment.py", "packaging/run-installed-product.py")
    outputs.file(layout.buildDirectory.file("reports/installed-product/topology-installed-product.json"))
    outputs.upToDateWhen { false }
    environment("KAST_INSTALLED_PRODUCT", installedProductDirectory.get().asFile.absolutePath)
    environment("KAST_CONTROL_ARCHIVE", assembleKastControlDist.get().archiveFile.get().asFile.absolutePath)
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

val installedCodexHostTest = tasks.register<Exec>("installedCodexHostTest") {
    group = "verification"
    description = "Exercises the staged private facade and stdio compatibility host against Codex."
    dependsOn(stageInstalledProduct)
    inputs.dir(installedProductDirectory)
    inputs.file(layout.projectDirectory.file("packaging/test-installed-codex-host.py"))
    inputs.file("packaging/acceptance_environment.py")
    outputs.file(layout.buildDirectory.file("reports/installed-product/codex-host.json"))
    outputs.upToDateWhen { false }
    commandLine(
        "python3",
        layout.projectDirectory.file("packaging/test-installed-codex-host.py"),
        installedProductDirectory.get().asFile.absolutePath,
        layout.projectDirectory.asFile.absolutePath,
        layout.buildDirectory.file("reports/installed-product/codex-host.json")
            .get().asFile.absolutePath,
    )
}

val installedTwoWorkspaceTest = tasks.register<Exec>("installedTwoWorkspaceTest") {
    group = "verification"
    description = "Runs two independent installed Gradle workers through semantic reconnect, stop and reset."
    dependsOn(stageInstalledProduct, semanticRuntimeArchive, ":app-server:writeInstalledWorkspaceHarnessClasspath")
    // Only one expensive fixture; the selected input profile is recorded in its report.
    inputs.dir(installedProductDirectory)
    inputs.file(semanticRuntimeArchive.flatMap(Zip::getArchiveFile))
    inputs.files("packaging/test-model-input-startup.py", "packaging/run-two-workspace-acceptance.py",
        "packaging/acceptance_idea.py", "packaging/acceptance_environment.py",
        "packaging/installed_acceptance_product.py", "gradle/wrapper/gradle-wrapper.properties",
        "gradle/wrapper/gradle-wrapper.jar", "gradlew")
    val profile = providers.environmentVariable("KAST_ACCEPTANCE_PROFILE").orElse("local-8g")
    inputs.property("acceptanceProfile", profile)
    inputs.property("ideaHome", providers.environmentVariable("KAST_ACCEPTANCE_IDEA_HOME").orElse("pinned-download"))
    outputs.file(layout.buildDirectory.file("reports/installed-product/two-workspace-runtime.json"))
    outputs.upToDateWhen { false }
    commandLine("python3", layout.projectDirectory.file("packaging/run-two-workspace-acceptance.py"),
        "--product", installedProductDirectory.get().asFile.absolutePath,
        "--runtime", semanticRuntimeArchive.get().archiveFile.get().asFile.absolutePath,
        "--idea-cache", layout.buildDirectory.dir("acceptance-inputs").get().asFile.absolutePath,
        "--harness-classpath-file", project(":app-server").layout.buildDirectory.file("acceptance/installed-workspace-harness.classpath").get().asFile.absolutePath,
        "--profile", profile.get(),
        "--report", layout.buildDirectory.file("reports/installed-product/two-workspace-runtime.json")
            .get().asFile.absolutePath)
}

val testCheckoutInstaller = tasks.register<Exec>("testCheckoutInstaller") {
    group = "verification"
    description = "Verifies checkout and release bootstrap boundaries without touching machine state."
    inputs.files("install.sh", "packaging/install-checkout.sh", "packaging/test-install-checkout.py")
    commandLine("python3", layout.projectDirectory.file("packaging/test-install-checkout.py"))
}

val installerEntrypointTest = tasks.register<Exec>("installerEntrypointTest") {
    group = "verification"
    description = "Pins the documented Bash -c installer invocation and argument delivery contract."
    inputs.files("install.sh", "README.md", "docs/public/start.mdx",
        "docs/public/reference/compatibility.mdx", "packaging/test-installer-entrypoint.py")
    commandLine("python3", layout.projectDirectory.file("packaging/test-installer-entrypoint.py"))
}

val isolatedAcceptanceEnvironmentTest = tasks.register<Exec>("isolatedAcceptanceEnvironmentTest") {
    group = "verification"
    description = "Proves installed fixtures isolate homes, environment, products and owned processes."
    inputs.files("packaging/acceptance_environment.py", "packaging/test-acceptance-environment.py",
        "packaging/test-model-input-startup.py", "packaging/installed_acceptance_product.py", "packaging/run-installed-product.py")
    commandLine("python3", layout.projectDirectory.file("packaging/test-acceptance-environment.py"))
}

val installerRemovalTest = tasks.register<Exec>("installerRemovalTest") {
    group = "verification"
    inputs.files("install.sh", "packaging/test-installer-removal.py")
    commandLine("python3", layout.projectDirectory.file("packaging/test-installer-removal.py"))
}

val acceptanceIdeaInputTest = tasks.register<Exec>("acceptanceIdeaInputTest") {
    group = "verification"
    description = "Checks exact IDEA input admission without downloading or starting an IDE."
    inputs.files("packaging/acceptance_idea.py", "packaging/test-acceptance-idea.py",
        "packaging/run-two-workspace-acceptance.py")
    commandLine("python3", layout.projectDirectory.file("packaging/test-acceptance-idea.py"))
}

val installationLifecycleTest = tasks.register<Exec>("installationLifecycleTest") {
    group = "verification"
    description = "Proves explicit installation reset preserves configuration and rejects unresolved ownership."
    inputs.files("packaging/installation-lifecycle.py", "packaging/test-installation-lifecycle.py")
    commandLine("python3", layout.projectDirectory.file("packaging/test-installation-lifecycle.py"))
}

val localInstallationTest = tasks.register<Exec>("localInstallationTest") {
    group = "verification"
    inputs.files("packaging/install-local.sh", "packaging/test-install-local.py")
    commandLine("python3", layout.projectDirectory.file("packaging/test-install-local.py"))
}

val productBuildGate by tasks.registering {
    group = "verification"
    description = "Builds every module and verifies deterministic contracts, architecture and packaging without runtime qualification."
    dependsOn(
        "check",
        isolatedAcceptanceEnvironmentTest,
        acceptanceIdeaInputTest,
        installationLifecycleTest,
        localInstallationTest,
        installerRemovalTest,
        installedProductTest,
        testCheckoutInstaller,
        installerEntrypointTest,
        "verifyKastArchitecture",
    )
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}

tasks.register("runtimeQualification") {
    group = "verification"
    description = "Explicit installed Codex and two-workspace runtime qualification; excluded from routine CI and release gates."
    dependsOn(installedCodexHostTest, installedTwoWorkspaceTest, ":app-server:generateCodexHostIntegrationManifest")
}

installedTwoWorkspaceTest.configure {
    mustRunAfter("check", isolatedAcceptanceEnvironmentTest, acceptanceIdeaInputTest,
        installationLifecycleTest, localInstallationTest, installerRemovalTest,
        installedProductTest, testCheckoutInstaller, installedCodexHostTest,
        "verifyKastArchitecture")
}

subprojects.forEach { owner ->
    owner.plugins.withId("base") {
        productBuildGate.configure { dependsOn(owner.tasks.named("check")) }
        installedTwoWorkspaceTest.configure { mustRunAfter(owner.tasks.named("check")) }
    }
}

val configurationIngressTest = tasks.register<Exec>("configurationIngressTest") {
    group = "verification"
    inputs.files("packaging/configuration_ingress.py", "packaging/test-configuration-ingress.py")
    commandLine("python3", layout.projectDirectory.file("packaging/test-configuration-ingress.py"))
}

val verifyConfigurationIngress = tasks.register<Exec>("verifyConfigurationIngress") {
    group = "verification"
    description = "Rejects undeclared Kast input references and ambient reads outside named ingress owners."
    dependsOn(":cli:generateConfigurationCatalogue", configurationIngressTest)
    inputs.file(generatedConfigurationCatalogue)
    inputs.files("build-policy/configuration-ingress.json", "packaging/configuration_ingress.py", "packaging/configuration-schema.json")
    inputs.files(fileTree(layout.projectDirectory) {
        include("**/src/main/**/*.kt", "**/src/main/**/*.kts", "**/src/gradleTooling/**/*.kt",
            "**/src/main/scripts/**", "*.kts", "install.sh", "packaging/*.sh", "packaging/*.py")
        exclude("**/build/**", "**/.gradle/**")
    })
    commandLine("python3", layout.projectDirectory.file("packaging/configuration_ingress.py"),
        "--root", layout.projectDirectory, "--schema", generatedConfigurationCatalogue.get().asFile,
        "--policy", layout.projectDirectory.file("build-policy/configuration-ingress.json"),
        "--snapshot", layout.projectDirectory.file("packaging/configuration-schema.json"))
}

val knowledgeBaseTest = tasks.register<Exec>("knowledgeBaseTest") {
    group = "verification"
    description = "Exercises repository knowledge-base validation behavior."
    inputs.files(
        layout.projectDirectory.file(".github/scripts/code_kb.py"),
        layout.projectDirectory.file(".github/scripts/test_code_kb.py"),
    )
    commandLine("python3", layout.projectDirectory.file(".github/scripts/test_code_kb.py"))
}

val verifyKnowledgeBase = tasks.register<Exec>("verifyKnowledgeBase") {
    group = "verification"
    description = "Strictly validates the source-bound OKF repository knowledge base."
    dependsOn(knowledgeBaseTest)
    inputs.dir(layout.projectDirectory.dir("knowledge"))
    inputs.file(layout.projectDirectory.file(".github/scripts/code_kb.py"))
    commandLine(
        "python3",
        layout.projectDirectory.file(".github/scripts/code_kb.py"),
        "check",
        "--repo",
        layout.projectDirectory,
        "--docs",
        "knowledge",
        "--strict",
    )
}

tasks.register<Exec>("knowledgeImpact") {
    group = "help"
    description = "Reports knowledge concepts affected by current working-tree changes."
    doNotTrackState("The report intentionally observes the live Git working tree.")
    commandLine(
        "python3",
        layout.projectDirectory.file(".github/scripts/code_kb.py"),
        "impact",
        "--repo",
        layout.projectDirectory,
        "--docs",
        "knowledge",
        "--from-git",
    )
}

val hostObservationTest = tasks.register<Exec>("hostObservationTest") {
    group = "verification"
    description = "Checks the launch-free experimental host-observation controller."
    inputs.dir(layout.projectDirectory.dir("experiments/host-observation"))
    commandLine("python3", "-m", "unittest", "discover", "-s", "experiments/host-observation", "-p", "test_*.py")
}

tasks.named("check") { dependsOn(verifyConfigurationIngress, verifyKnowledgeBase, hostObservationTest) }
