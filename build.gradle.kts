import support.consumeProjectOutput
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.api.tasks.testing.Test
import support.tasks.idea.selectedIdeaProductInfo
import support.tasks.nativefixtures.SharedNativeFixtureIdea
import conventions.registerJsonContractVerification
import conventions.jsoncontracts.JsonContractScanRequest
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip
import support.tasks.GenerateComponentReleaseTask
import support.tasks.ReleaseComponent
import support.tasks.GenerateControlMetadataTask
import support.tasks.GenerateAgentToolsTask
import support.tasks.VerificationReportTask
import support.tasks.VerifyControlDistributionTask
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

plugins {
    base
    id("kast.architecture")
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
// Parser dependencies are resolved only for the isolated verification process, never the KGP classloader.
val jsonContractParser = configurations.create("jsonContractParser") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(jsonContractParser.name, "org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
    add(jsonContractParser.name, libs.serialization.json)
}

registerJsonContractVerification(
    files(
        JsonContractScanRequest::class.java.protectionDomain.codeSource.location,
        jsonContractParser,
    )
)

// Shared libraries retain the source version: a control rebuild cannot rename host dependencies.
val controlVersion = providers.gradleProperty("controlVersion").orElse(provider { version.toString() })
val hostedPluginVersion = providers.gradleProperty("hostedPluginVersion").orElse(provider { version.toString() })
extra["controlVersion"] = controlVersion.get()
extra["hostedPluginVersion"] = hostedPluginVersion.get()
val modulePaths = subprojects.filter { it.buildFile.isFile }.map { it.path }
val hostedPluginFiles = consumeProjectOutput("hostedPluginFiles", ":runtime:hosted", "hostedPluginElements")
val hostedPluginArchive = layout.file(hostedPluginFiles.flatMap { it.elements }.map { it.single().asFile })
val hosted263PluginFiles = consumeProjectOutput("hosted263PluginFiles", ":runtime:hosted", "hosted263PluginElements")
val hosted263PluginArchive = layout.file(hosted263PluginFiles.flatMap { it.elements }.map { it.single().asFile })
val hostedContractFiles = consumeProjectOutput("hostedContractFiles", ":protocol:wire", "hostedContractElements")
val contractFile = layout.file(hostedContractFiles.flatMap { it.elements }.map { it.single().asFile })
val registryFiles = consumeProjectOutput("operationRegistryFiles", ":protocol:wire", "operationRegistryElements")
val generatedOperationRegistry = layout.file(registryFiles.flatMap { it.elements }.map { it.single().asFile })
val catalogueFiles = consumeProjectOutput("configurationCatalogueFiles", ":cli", "configurationCatalogueElements")
val generatedConfigurationCatalogue = layout.file(catalogueFiles.flatMap { it.elements }.map { it.single().asFile })
val providerFiles = consumeProjectOutput("providerCatalogueFiles", ":cli", "providerCatalogueElements")
val generatedProviderCatalogue = layout.file(providerFiles.flatMap { it.elements }.map { it.single().asFile })
val cliInstallation = consumeProjectOutput("cliInstallation", ":cli", "controlLayoutElements")
val controlLibraryFiles = consumeProjectOutput("controlLibraryFiles", ":cli", "controlLibraryElements")
val frontendCatalogFiles = consumeProjectOutput("frontendCatalogFiles", ":cli", "frontendCatalogElements")

val publicLauncherTestClasses = consumeProjectOutput("publicLauncherTestClasses", ":cli", "publicLauncherTestClassesElements")
val publicLauncherTestRuntime = consumeProjectOutput("publicLauncherTestRuntime", ":cli", "publicLauncherTestRuntimeElements")

abstract class PublicLauncherArguments : CommandLineArgumentProvider {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val product: org.gradle.api.file.DirectoryProperty

    override fun asArguments(): Iterable<String> =
        listOf(
            "-Dkast.controlProduct=${product.get().asFile.absolutePath}",
        )
}

// CI qualifies the exact assembled Control product; locally the staged product is exercised.
val importedControlProduct = providers.gradleProperty("kastControlProduct")
val publicLauncherTest = tasks.register<Test>("publicLauncherTest") {
    description = "Exercises the Control executable, its JVM entry, and a local Unix socket peer."
    if (!importedControlProduct.isPresent) dependsOn("stageKastControlProduct")
    group = "verification"
    testClassesDirs = files(publicLauncherTestClasses)
    classpath = files(publicLauncherTestRuntime)
    useJUnitPlatform { includeTags("public-launcher") }
    binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/publicLauncherTest/binary"))
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/publicLauncherTest"))
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/publicLauncherTest"))
    jvmArgumentProviders.add(objects.newInstance<PublicLauncherArguments>().apply {
        product.set(
            layout.dir(
                importedControlProduct
                    .map { File(it).also { path -> require(path.isAbsolute) { "kastControlProduct must be absolute" } } }
                    .orElse(tasks.named<Sync>("stageKastControlProduct").map { it.destinationDir })
            )
        )
    })
}

val generatedControlMetadata = layout.buildDirectory.dir("generated/control-metadata")
val releaseSourceRevision = providers.gradleProperty("kastSourceRevision").orElse(providers.exec {
    commandLine("git", "rev-parse", "HEAD")
    workingDir(rootDir)
}.standardOutput.asText.map(String::trim))
val releaseSourceTree = providers.exec {
    commandLine("git", "rev-parse", "HEAD^{tree}")
    workingDir(rootDir)
}.standardOutput.asText.map(String::trim)
val generateKastControlMetadata = tasks.register<GenerateControlMetadataTask>("generateKastControlMetadata") {
    group = "distribution"
    description = "Generates the control required hosted contract and public schemas."
    dependsOn(":protocol:wire:generateHostedContract", ":protocol:wire:generateOperationRegistry", ":cli:generateConfigurationCatalogue", ":cli:generateProviderCatalog")
    hostedContractFile.set(contractFile)
    licenseFile.set(layout.projectDirectory.file("LICENSE"))
    operationRegistryFile.set(generatedOperationRegistry)
    configurationCatalogueFile.set(generatedConfigurationCatalogue)
    providerCatalogueFile.set(generatedProviderCatalogue)
    productVersion.set(controlVersion)
    sourceRevision.set(releaseSourceRevision)
    sourceTree.set(releaseSourceTree)
    ideaBuild.set(libs.versions.ide.host.build)
    kotlinPluginBuild.set(libs.versions.ide.kotlin.plugin.build)
    outputDirectory.set(generatedControlMetadata)
}

val controlProductDirectory = layout.buildDirectory.dir("control-product")
// The Control executable cross-compiles, so any host assembles any target's archive.
val controlArchivePlatform = providers.gradleProperty("kastControlPlatform").map { platform ->
    require(platform in setOf("macos-aarch64", "macos-x86_64", "linux-aarch64", "linux-x86_64")) {
        "The Control archive needs a supported target platform"
    }
    platform
}.orElse(providers.systemProperty("os.name").zip(providers.systemProperty("os.arch")) { os, arch ->
    when {
        os.startsWith("Mac") && arch in setOf("aarch64", "arm64") -> "macos-aarch64"
        os.startsWith("Mac") && arch in setOf("amd64", "x86_64") -> "macos-x86_64"
        os == "Linux" && arch in setOf("aarch64", "arm64") -> "linux-aarch64"
        os == "Linux" && arch in setOf("amd64", "x86_64") -> "linux-x86_64"
        else -> throw GradleException("Control archive requires macOS or Linux on AArch64 or x86-64")
    }
})
// The cgo-free Control executable for the target platform. It runs the JVM entry from the installation's lib.
val controlExecutableFile = layout.buildDirectory.file("control-executable/kast-management")
val buildControlExecutable = tasks.register<Exec>("buildControlExecutable") {
    group = "build"
    description = "Builds the Control executable for the target platform from the Go module."
    val (goos, goarch) = when (controlArchivePlatform.get()) {
        "macos-aarch64" -> "darwin" to "arm64"
        "macos-x86_64" -> "darwin" to "amd64"
        "linux-aarch64" -> "linux" to "arm64"
        else -> "linux" to "amd64"
    }
    inputs.files(fileTree("manager") { include("**/*.go", "go.mod", "go.sum") })
    inputs.property("target", "$goos/$goarch")
    outputs.file(controlExecutableFile)
    workingDir("manager")
    executable("go")
    args("build", "-trimpath", "-buildvcs=false", "-ldflags=-s -w", "-o", controlExecutableFile.get().asFile.absolutePath, "./cmd/kast")
    environment(mapOf("CGO_ENABLED" to "0", "GOOS" to goos, "GOARCH" to goarch))
}

val installerSupportDirectory = layout.buildDirectory.dir("generated/installer-support")
val generateInstallerSupport = tasks.register<Exec>("generateInstallerSupport") {
    group = "distribution"
    description = "Projects installer support from the bootstrap generator's inventory."
    inputs.files(fileTree("packaging") { include("composed_bundle.py", "managed_headless.py", "internal/*/*.py", "internal/*/*.sh", "internal/*/*.lock", "*.lock", "*-v1") })
    outputs.dir(installerSupportDirectory)
    commandLine("python3", "packaging/internal/bootstrap/generate_bootstrap.py", "--support-directory",
        installerSupportDirectory.get().asFile.absolutePath)
}
val agentToolsDirectory = layout.buildDirectory.dir("generated/agent-tools")
val generateKastAgentTools = tasks.register<GenerateAgentToolsTask>("generateKastAgentTools") {
    group = "distribution"
    description = "Validates and versions session context, MCP plugins, and the marketplace."
    dependsOn(":app-server:verifyPublicQueryGeneration")
    sourceDirectory.set(layout.projectDirectory.dir("agent-tools"))
    marketplaceFile.set(layout.projectDirectory.file(".agents/plugins/marketplace.json"))
    canonicalQueryExamplesFile.set(layout.projectDirectory.file(
        "agent-tools/references/query-examples.json",
    ))
    productVersion.set(controlVersion)
    outputDirectory.set(agentToolsDirectory)
}
val stageKastControlProduct = tasks.register<Sync>("stageKastControlProduct") {
    group = "distribution"
    description = "Stages the Kast control installation for the existing IDE."
    dependsOn(generateKastControlMetadata, generateKastAgentTools)
    dependsOn(generateInstallerSupport)
    into(controlProductDirectory)
    from(cliInstallation)
    from(generatedControlMetadata) {
        into("share/kast")
    }
    from(agentToolsDirectory) {
        into("share/kast/agent-tools")
    }
    from(buildControlExecutable) { into("share/kast/libexec") }
    from("manager/THIRD_PARTY_NOTICES.txt") { into("share/kast/licenses/frontend") }
    from(controlLibraryFiles) { into("lib") }
    from(frontendCatalogFiles) { into("share/kast") }
    from(installerSupportDirectory) { into("share/kast") }
    from("distribution/cli/lifecycle-fence-v1") { into("share/kast") }
    from("distribution/cli/public-reset-v1") { into("share/kast") }
    from("distribution/cli/reset-fence-v1") { into("share/kast") }
    from("distribution/contract/src/main/resources/management/reset-inventory-observation.schema.json") { into("share/kast/schema") }
    from("distribution/contract/src/main/resources/management/management-lifecycle.schema.json") { into("share/kast/schema") }
    from("distribution/cli/one-shot-observation-v1") {
        into("share/kast")
    }
}

val assembleKastControlDist = tasks.register<Tar>("assembleKastControlDist") {
    group = "distribution"
    description = "Builds the private service, lifecycle, schema, broker, and wire-control archive."
    dependsOn(stageKastControlProduct)
    from(controlProductDirectory)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set(controlArchivePlatform.zip(controlVersion) { platform, version ->
        "kast-control-v$version-$platform.tar.gz"
    })
    compression = Compression.GZIP
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    eachFile {
        if (relativePath.pathString in setOf(
                "bin/kast",
                "bin/kast-codex",
                "bin/kast-mcp",
                "bin/kast-query",
                "share/kast/libexec/kast-daemon",
                "share/kast/libexec/kast-service",
                "share/kast/libexec/kast-management",
            )
        ) permissions { unix("755") }
    }
}

val controlDistributionMaximumEntries = 16_384

val verifyKastControlDistLayout = tasks.register<VerifyControlDistributionTask>("verifyKastControlDistLayout") {
    group = "verification"
    description = "Rejects oversized or semantic-payload-bearing control archives."
    dependsOn(assembleKastControlDist)
    controlDirectory.set(controlProductDirectory)
    controlArchive.set(assembleKastControlDist.flatMap(Tar::getArchiveFile))
    qualifiedControlExecutable.set(controlExecutableFile)
    targetPlatform.set(controlArchivePlatform)
    maximumEntries.set(controlDistributionMaximumEntries)
    maximumArchiveBytes.set(96L * 1024L * 1024L)
    maximumInstalledBytes.set(192L * 1024L * 1024L)
}

tasks.register<GenerateComponentReleaseTask>("generateControlReleaseRecord") {
    dependsOn(assembleKastControlDist, "assembleKastAgentPlugin", "assembleKastMarketplace", ":protocol:wire:generateHostedContract")
    component.set(ReleaseComponent.CONTROL)
    releaseVersion.set(controlVersion)
    sourceRevision.set(releaseSourceRevision)
    archive.set(assembleKastControlDist.flatMap(Tar::getArchiveFile))
    hostedContractFile.set(contractFile)
    companionArtifacts.from(
        tasks.named<Zip>("assembleKastAgentPlugin").flatMap(Zip::getArchiveFile),
        tasks.named<Zip>("assembleKastMarketplace").flatMap(Zip::getArchiveFile),
    )
    outputDirectory.set(layout.buildDirectory.dir("generated/control-release"))
}
tasks.register<GenerateComponentReleaseTask>("generateHostReleaseRecord") {
    dependsOn(":runtime:hosted:hostedPlugin", ":protocol:wire:generateHostedContract")
    component.set(ReleaseComponent.HOST)
    releaseVersion.set(hostedPluginVersion)
    sourceRevision.set(releaseSourceRevision)
    archive.set(hostedPluginArchive)
    hostedContractFile.set(contractFile)
    val metadataBaseDirectory = layout.projectDirectory.asFile
    ideaReleaseLine.set(providers.gradleProperty("hostedIdeaHome").map { home ->
        val metadataPath = selectedIdeaProductInfo(java.io.File(home))
        val metadataFile = if (metadataPath.isAbsolute) metadataPath else java.io.File(metadataBaseDirectory, metadataPath.path)
        val metadata = groovy.json.JsonSlurper().parse(metadataFile) as Map<*, *>
        (metadata["buildNumber"] as String).substringBefore('.')
    }.orElse(libs.versions.ide.host.build.map { it.substringBefore('.') }))
    outputDirectory.set(layout.buildDirectory.dir("generated/host-release"))
}
tasks.register<GenerateComponentReleaseTask>("generateHost263ReleaseRecord") {
    component.set(ReleaseComponent.HOST)
    releaseVersion.set(hostedPluginVersion)
    sourceRevision.set(releaseSourceRevision)
    archive.set(hosted263PluginArchive)
    hostedContractFile.set(contractFile)
    ideaReleaseLine.set("263")
    outputDirectory.set(layout.buildDirectory.dir("generated/host-release-263"))
}


apply(from = "distribution/release/plugin-release.gradle.kts")

tasks.register("verifyDistributionContent") {
    group = "verification"
    description = "Verifies control/sidecar separation and required artifact layouts."
    dependsOn(verifyKastControlDistLayout, ":runtime:hosted:hostedPlugin", ":app-server:verifyReleaseRuntimeAdmission")
}

val installedProductTest = tasks.register<Exec>("installedProductTest") {
    group = "verification"
    description = "Verifies complete qualified products through native installed lifecycle acceptance."
    val candidate = providers.gradleProperty("kastAcceptanceCandidate")
    val baseline = providers.gradleProperty("kastAcceptanceBaseline")
    val pythonArchive = providers.gradleProperty("kastAcceptancePythonArchive")
    val dependency = providers.gradleProperty("kastAcceptanceDependency")
    val target = providers.gradleProperty("kastAcceptanceTarget")
    val evidence = providers.gradleProperty("kastAcceptanceEvidence")
    val receipt = providers.gradleProperty("kastAcceptanceReceipt")
    inputs.dir(candidate)
    inputs.dir(baseline)
    inputs.file(pythonArchive)
    inputs.file(dependency)
    inputs.property("target", target)
    inputs.property("evidence", evidence)
    inputs.property("receipt", receipt)
    inputs.files(fileTree("packaging") { include("composed_bundle.py", "managed_headless.py", "internal/*/*.py", "tests/*.py", "*.toml", "*.lock") })
    inputs.files(fileTree(".github/scripts/release") { include("*.py") })
    outputs.upToDateWhen { false }
    commandLine("uv", "run", "--locked", "--project", layout.projectDirectory.dir("packaging").asFile.absolutePath,
        "python3", layout.projectDirectory.file("packaging/internal/verification/run-installed-product.py").asFile.absolutePath)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--candidate", candidate.get(), "--baseline", baseline.get(),
            "--private-python-archive", pythonArchive.get(), "--private-dependency", dependency.get(),
            "--target", target.get(), "--evidence", evidence.get(), "--receipt", receipt.get(),
        )
    })
}

// Resolve tools from this invocation, rather than the daemon's original process environment.
fun validationExecutable(command: String) = layout.file(providers.environmentVariable("PATH").map { searchPath ->
    searchPath.split(java.io.File.pathSeparator)
        .map { java.io.File(it, command) }
        .firstOrNull { it.isFile && it.canExecute() }
        ?.absoluteFile ?: throw GradleException("Validation executable is unavailable: $command")
})

val validationEnvironment = providers.environmentVariable("PATH").zip(providers.environmentVariable("HOME")) { path, home ->
    mapOf("PATH" to path, "HOME" to home, "LC_ALL" to "C", "PYTHONDONTWRITEBYTECODE" to "1")
}

tasks.withType<VerificationReportTask>().configureEach {
    repositoryDirectory.set(layout.projectDirectory)
    environmentVariables.putAll(validationEnvironment)
    reportFile.set(layout.buildDirectory.file("reports/validation/$name.txt"))
    sourceFiles.from("mise.toml", ".python-version")
}

val frontendTest = tasks.register<VerificationReportTask>("frontendTest") {
    group = "verification"
    description = "Verifies the Go frontend module, formatting, analysis, race tests, and cgo-free release targets."
    sourceFiles.from(fileTree("manager") { exclude("**/__pycache__/**") })
    runtimeFiles.from(frontendCatalogFiles)
    executableFile.set(validationExecutable("uv"))
    arguments.set(frontendCatalogFiles.flatMap { it.elements }.map {
        listOf("run", "--locked", "--script", "manager/check", "gate", "--catalog", it.single().asFile.absolutePath)
    })
}
tasks.named("check") { dependsOn(frontendTest) }

val offlineInstallerChecks = setOf(
    "testCheckoutInstaller", "installerEntrypointTest", "installerFixtureTest", "installerRemovalTest",
    "installationSystemPythonTest", "installationRecoveryTest", "localInstallationTest",
)

val packagingTestModulePath = listOf(
    "packaging/internal/bootstrap", "packaging/internal/composition", "packaging/internal/installation",
    "packaging/internal/product", "packaging/internal/runtime",
    "packaging/internal/query", "packaging/internal/verification", "packaging/internal/configuration",
    "packaging", "packaging/tests",
)
    .joinToString(java.io.File.pathSeparator) { layout.projectDirectory.dir(it).asFile.absolutePath }

val offlineInstallerTools = providers.exec {
    workingDir(layout.projectDirectory)
    setEnvironment(validationEnvironment.get())
    environment("PYTHONPATH", packagingTestModulePath)
    commandLine(validationExecutable("python3").get().asFile, "-c",
        "import json; from installer_fixture import admitted_tools; print(json.dumps({name: str(path) for name, path in admitted_tools().items()}))")
}.standardOutput.asText.map { Json.decodeFromString<Map<String, String>>(it) }

tasks.withType<VerificationReportTask>().configureEach {
    if (name in offlineInstallerChecks) {
        environmentVariables.put("PYTHONPATH", packagingTestModulePath)
        sourceFiles.from(fileTree("packaging") { include("composed_bundle.py", "managed_headless.py", "internal/*/*.py", "internal/*/*.sh", "tests/*.py") })
        sourceFiles.from("build.gradle.kts", "distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/ControlDistributionLimits.kt")
        inputs.property("installerToolPaths", offlineInstallerTools)
        runtimeFiles.from("/bin/bash", offlineInstallerTools.map { tools -> tools.values.map { path -> java.io.File(path) } })
    }
}

val managedInstallationTest = tasks.register<VerificationReportTask>("managedInstallationTest") {
    group = "verification"
    description = "Proves immutable selection, managed replacement, and recorded recovery."
    sourceFiles.from(fileTree("packaging") { include("composed_bundle.py", "managed_headless.py", "internal/*/*.py", "internal/*/*.sh", "tests/*.py", "pyproject.toml", "uv.lock") })
    sourceFiles.from("install.sh", "packaging/internal/bootstrap/bootstrap.sh.in")
    executableFile.set(validationExecutable("uv"))
    arguments.set(listOf("run", "--locked", "--project", "packaging", "pytest",
        "packaging/tests/test-managed-installation.py", "packaging/tests/test-managed-release.py",
        "packaging/tests/test-managed-build-record.py", "packaging/tests/test-managed-bootstrap.py",
        "packaging/tests/test-managed-headless.py", "packaging/tests/test-managed-invocation-state.py",
        "packaging/tests/test_composition_graph.py", "packaging/tests/test_composition_index.py",
        "packaging/tests/test_composition_descriptors.py", "packaging/tests/test_composition_host.py",
        "packaging/tests/test_composition_bundle.py", "packaging/tests/test_composition_release.py",
        "packaging/tests/test_runtime_archive.py", "packaging/tests/test_composition_platform.py",
        "packaging/tests/test_local_product_install.py", "packaging/tests/test_harness_launchd.py", "-q"))
}

val developerPublicationTest = tasks.register<VerificationReportTask>("developerPublicationTest") {
    group = "verification"
    description = "Proves qualified publication and concurrent discovery admission."
    sourceFiles.from(fileTree(".github/scripts/release") { include("*.py", "pyproject.toml", "uv.lock") })
    sourceFiles.from(".github/scripts/release/publish-release.sh", ".github/scripts/release/admit-source.sh")
    sourceFiles.from(fileTree(".github/scripts/ci") { include("*.py") })
    executableFile.set(validationExecutable("uv"))
    arguments.set(listOf("run", "--locked", "--project", ".github/scripts/release", "pytest",
        ".github/scripts/release/test_build_candidate.py",
        ".github/scripts/release/test_candidate_admission.py",
        ".github/scripts/release/test_developer_catalog.py",
        ".github/scripts/release/test_product_candidate.py",
        ".github/scripts/release/test_product_producer.py",
        ".github/scripts/release/test_developer_publication.py", "-q"))
}

tasks.named("check") { dependsOn(managedInstallationTest, developerPublicationTest) }

val testCheckoutInstaller = tasks.register<VerificationReportTask>("testCheckoutInstaller") {
    group = "verification"
    description = "Verifies retired entrypoints and canonical product admission boundaries."
    inputs.files("install.sh", "packaging/internal/bootstrap/generate_bootstrap.py", "packaging/tests/test-install-checkout.py")
    executableFile.set(validationExecutable("python3"))
    arguments.set(listOf(layout.projectDirectory.file("packaging/tests/test-install-checkout.py").asFile.absolutePath))
}

val installerEntrypointTest = tasks.register<VerificationReportTask>("installerEntrypointTest") {
    group = "verification"
    description = "Pins the documented Bash -c installer invocation and argument delivery contract."
    inputs.files("install.sh", "README.md", "docs/public/start.mdx", "docs/public/reference/installation.mdx",
        "docs/public/reference/compatibility.mdx", "packaging/tests/test-installer-entrypoint.py")
    sourceFiles.from("packaging/pyproject.toml", "packaging/uv.lock")
    executableFile.set(validationExecutable("uv"))
    arguments.set(listOf("run", "--locked", "--project", "packaging", "python",
        layout.projectDirectory.file("packaging/tests/test-installer-entrypoint.py").asFile.absolutePath))
}

val installerFixtureTest = tasks.register<VerificationReportTask>("installerFixtureTest") {
    group = "verification"
    description = "Proves installer fixtures isolate their home and remove only owned roots."
    inputs.files("packaging/tests/installer_fixture.py", "packaging/tests/test-installer-fixture.py")
    executableFile.set(validationExecutable("python3"))
    arguments.set(listOf(layout.projectDirectory.file("packaging/tests/test-installer-fixture.py").asFile.absolutePath))
}

val installerRemovalTest = tasks.register<VerificationReportTask>("installerRemovalTest") {
    group = "verification"
    inputs.files("install.sh", "packaging/tests/test-installer-removal.py")
    sourceFiles.from("packaging/pyproject.toml", "packaging/uv.lock")
    executableFile.set(validationExecutable("uv"))
    arguments.set(listOf("run", "--locked", "--project", "packaging", "python",
        layout.projectDirectory.file("packaging/tests/test-installer-removal.py").asFile.absolutePath))
}

val installationSystemPythonTest = tasks.register<VerificationReportTask>("installationSystemPythonTest") {
    group = "verification"
    description = "Proves offline recovery works with macOS system Python, independently of the development interpreter."
    inputs.files("packaging/internal/installation/installation-recovery.py", "packaging/internal/installation/installation-lifecycle.py", "packaging/internal/installation/managed_update_journal.py", "packaging/tests/test-installation-recovery.py")
    executableFile.set(layout.file(providers.provider { java.io.File("/usr/bin/python3") }))
    arguments.set(listOf(layout.projectDirectory.file("packaging/tests/test-installation-recovery.py").asFile.absolutePath))
}

val installationRecoveryTest = tasks.register<VerificationReportTask>("installationRecoveryTest") {
    group = "verification"
    inputs.files("packaging/internal/installation/installation-recovery.py", "packaging/internal/installation/installation-lifecycle.py", "packaging/internal/installation/managed_update_journal.py", "packaging/tests/test-installation-recovery.py")
    executableFile.set(validationExecutable("python3"))
    arguments.set(listOf(layout.projectDirectory.file("packaging/tests/test-installation-recovery.py").asFile.absolutePath))
}

val localInstallationTest = tasks.register<VerificationReportTask>("localInstallationTest") {
    group = "verification"
    inputs.files("packaging/install-local.sh", "packaging/tests/test-install-local.py")
    executableFile.set(validationExecutable("python3"))
    arguments.set(listOf(layout.projectDirectory.file("packaging/tests/test-install-local.py").asFile.absolutePath))
}

val productGateVersionTest = tasks.register<Exec>("productGateVersionTest") {
    group = "verification"
    description = "Proves local gates bind clean source to staged build identity."
    inputs.files(
        ".github/scripts/release/admit-source.sh",
        "distribution/release/run_product_gate.py",
        "distribution/release/test_run_product_gate.py",
    )
    commandLine("python3", layout.projectDirectory.file("distribution/release/test_run_product_gate.py"))
}

val documentationDependencyEnvironment = providers.provider {
    validationEnvironment.get() + support.tasks.DependencyBootstrapEnvironment.network { name ->
        providers.environmentVariable(name).orNull
    }
}

val documentationMarkdownDependencies = tasks.register<Exec>("documentationMarkdownDependencies") {
    group = "verification"
    description = "Installs the locked CommonMark parser without package scripts."
    inputs.files("docs/internal/tooling/markdown/package.json", "docs/internal/tooling/markdown/package-lock.json", "mise.toml")
    outputs.dir("docs/internal/tooling/markdown/node_modules")
    inputs.files(validationExecutable("node"), validationExecutable("npm"))
    inputs.property("validationEnvironment", validationEnvironment)
    setEnvironment(documentationDependencyEnvironment.get())
    val emptyConfig = layout.buildDirectory.file("documentation/npm-global-empty")
    environment("NPM_CONFIG_USERCONFIG", "/dev/null")
    environment("NPM_CONFIG_GLOBALCONFIG", emptyConfig.get().asFile.absolutePath)
    environment("NPM_CONFIG_REGISTRY", "https://registry.npmjs.org")
    environment("NPM_CONFIG_CACHE", layout.buildDirectory.dir("documentation/npm-cache").get().asFile.absolutePath)
    doFirst {
        emptyConfig.get().asFile.apply { parentFile.mkdirs(); writeText("") }
    }
    commandLine(validationExecutable("npm").get().asFile, "ci", "--ignore-scripts", "--engine-strict", "--prefix", "docs/internal/tooling/markdown")
}

val documentationEnvironment = layout.buildDirectory.dir("documentation/python")
val documentationPythonVersion = providers.fileContents(layout.projectDirectory.file(".python-version")).asText.map { it.trim() }
val documentationPython = providers.exec {
    commandLine(validationExecutable("uv").get().asFile, "python", "find", "--system", documentationPythonVersion.get())
}.standardOutput.asText.map { it.trim() }

val documentationPythonDependencies = tasks.register<Exec>("documentationPythonDependencies") {
    group = "verification"
    description = "Prepares the locked Python runtime for documentation checks."
    inputs.files("docs/internal/tooling/pyproject.toml", "docs/internal/tooling/uv.lock", ".python-version", validationExecutable("uv"))
    inputs.file(documentationPython)
    inputs.property("validationEnvironment", validationEnvironment)
    outputs.dir(documentationEnvironment)
    setEnvironment(documentationDependencyEnvironment.get())
    environment("UV_PROJECT_ENVIRONMENT", documentationEnvironment.get().asFile.absolutePath)
    commandLine(validationExecutable("uv").get().asFile, "sync", "--locked", "--project", "docs/internal/tooling", "--python", documentationPython.get())
}

val verifyDocumentationMarkdown = tasks.register<VerificationReportTask>("verifyDocumentationMarkdown") {
    group = "verification"
    description = "Examines the typed CommonMark adapter."
    dependsOn(documentationMarkdownDependencies)
    inputs.files("docs/internal/tooling/markdown/parse.ts", "docs/internal/tooling/markdown/tsconfig.json", "docs/internal/tooling/markdown/package.json", "docs/internal/tooling/markdown/package-lock.json")
    executableFile.set(validationExecutable("npm"))
    arguments.set(listOf("run", "check", "--prefix", "docs/internal/tooling/markdown"))
}

val documentationToolingTest = tasks.register<VerificationReportTask>("documentationToolingTest") {
    group = "verification"
    description = "Exercises local glossary grammar, scope and projection rejection."
    dependsOn(verifyDocumentationMarkdown, documentationPythonDependencies)
    inputs.files(fileTree("docs/internal") { exclude("**/.venv/**", "**/__pycache__/**", "**/.pytest_cache/**", "**/.ruff_cache/**", "**/node_modules/**") })
    inputs.files("docs/glossary.json", "docs/GLOSSARY.md", "docs/public/glossary.mdx",
        ".agents/repository-surfaces.json", "bin/kast-repo")
    inputs.dir("config/vale/styles/Kast")
    executableFile.set(validationExecutable("uv"))
    arguments.set(listOf("run", "--locked", "--project", "docs/internal/tooling", "pytest", "-c", "docs/internal/tooling/pyproject.toml", "docs/internal/tooling", "docs/internal/tests"))
}

val verifyPublicKnowledge = tasks.register<VerificationReportTask>("verifyPublicKnowledge") {
    group = "verification"
    description = "Rejects public OKF projection drift and missing tracked attribution."
    dependsOn(documentationMarkdownDependencies, documentationPythonDependencies)
    doNotTrackState("Attribution examines current Git tracking and the source bytes cited by OpenWiki Claims.")
    executableFile.set(validationExecutable("uv"))
    arguments.set(listOf("run", "--locked", "--project", "docs/internal/tooling", "python", "docs/internal/tooling/okf_public.py", "--check"))
}

// Source qualification rejects broken integrity and reports current evidence.
// Publication separately needs a complete current assessment for its exact inputs.
val verifyKnowledgeClaims = tasks.register<VerificationReportTask>("verifyKnowledgeClaims") {
    group = "verification"
    description = "Rejects broken Claim integrity and reports pending evidence review."
    dependsOn(documentationMarkdownDependencies)
    doNotTrackState("Claim currentness examines the live Git revision and every cited source file.")
    executableFile.set(validationExecutable("node"))
    arguments.set(listOf("docs/internal/tooling/markdown/evidence-assess.ts", "--root", rootDir.absolutePath, "--scope", "canonical"))
    runtimeFiles.from("docs/internal/tooling/markdown/node_modules")
}

val verifyDocumentationGlossary = tasks.register<VerificationReportTask>("verifyDocumentationGlossary") {
    group = "verification"
    description = "Rejects glossary projection drift and rejected forms in current prose."
    dependsOn(documentationMarkdownDependencies, documentationPythonDependencies)
    inputs.files(fileTree("docs/internal") { exclude("**/.venv/**", "**/__pycache__/**", "**/.pytest_cache/**", "**/.ruff_cache/**", "**/node_modules/**") })
    inputs.files("docs/glossary.json", "docs/GLOSSARY.md", "README.md", "AGENTS.md")
    inputs.files(fileTree("docs") { include("**/*.md", "**/*.mdx", "**/*.txt"); exclude("**/.venv/**", "**/build/**", "**/node_modules/**") })
    inputs.dir("config/vale/styles/Kast")
    val scopedGuides = fileTree(rootDir) { include("**/AGENTS.md"); exclude("**/build/**", "**/.gradle/**", "**/.venv/**", "**/node_modules/**") }
    inputs.files(scopedGuides)
    executableFile.set(validationExecutable("uv"))
    arguments.set(listOf("run", "--locked", "--project", "docs/internal/tooling", "python", "docs/internal/tooling/check_glossary.py",
        "docs/glossary.json", "README.md", "docs"))
    arguments.addAll(scopedGuides.files.map { it.relativeTo(rootDir).path }.sorted())
    arguments.addAll("--check-render", "docs/GLOSSARY.md", "--check-public-render", "docs/public/glossary.mdx", "--check-vale", "config/vale/styles/Kast")
}

tasks.withType<VerificationReportTask>().configureEach {
    if (name in setOf("verifyDocumentationMarkdown", "documentationToolingTest", "verifyDocumentationGlossary", "verifyPublicKnowledge")) {
        runtimeFiles.from(validationExecutable("node"), validationExecutable("uv"), validationExecutable("npm"))
        runtimeFiles.from("docs/internal/tooling/markdown/node_modules")
        if (name != "verifyDocumentationMarkdown") {
            runtimeFiles.from(documentationPython, documentationEnvironment)
            environmentVariables.put("UV_PROJECT_ENVIRONMENT", documentationEnvironment.get().asFile.absolutePath)
        }
    }
}

val selectedProse = providers.gradleProperty("kastProsePaths").map { raw ->
    val paths = raw.split(',')
    require(paths.isNotEmpty() && paths.none(String::isBlank) && paths.distinct().size == paths.size) {
        "kastProsePaths needs distinct repository-relative files separated by commas."
    }
    paths.forEach { value ->
        val path = java.nio.file.Path.of(value)
        require(!path.isAbsolute && path.none { it.toString() == ".." } && value.substringAfterLast('.') in setOf("md", "mdx", "txt")) {
            "kastProsePaths rejects this path: $value"
        }
    }
    paths
}

tasks.register<VerificationReportTask>("verifyScopedProse") {
    group = "verification"
    description = "Examines explicitly selected repository prose with the pinned Vale policy."
    executableFile.set(validationExecutable("vale"))
    arguments.set(selectedProse.map { listOf("--no-global", "--config=.vale.ini", "--output=JSON") + it })
    sourceFiles.from(selectedProse, ".vale.ini", "config/vale")
}

tasks.named("check") { dependsOn(verifyDocumentationGlossary, documentationToolingTest, verifyPublicKnowledge, verifyKnowledgeClaims) }

val productBuildGate = tasks.register("productBuildGate") {
    group = "verification"
    description = "Builds every module and verifies deterministic contracts, architecture and packaging without runtime qualification."
    dependsOn(
        "check",
        productGateVersionTest,
        installerFixtureTest,
        installationRecoveryTest,
        installationSystemPythonTest,
        localInstallationTest,
        installerRemovalTest,
        installedProductTest,
        testCheckoutInstaller,
        installerEntrypointTest,
        verifyKastControlDistLayout,
        "verifyKastArchitecture",
        ":app-server:verifyReleaseRuntimeAdmission",
    )
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}

productBuildGate.configure {
    dependsOn(modulePaths.map { "$it:check" })
    dependsOn(publicLauncherTest)
}

val sourceValidationGate = tasks.register("sourceValidationGate") {
    group = "verification"
    description = "Examines source, semantic fixtures, and contracts without release assembly or native compilation."
    dependsOn(
        "check",
        productGateVersionTest,
        installerFixtureTest,
        installationRecoveryTest,
        installationSystemPythonTest,
        localInstallationTest,
        installerRemovalTest,
        testCheckoutInstaller,
        installerEntrypointTest,
        "verifyKastArchitecture",
    )
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}

val verifyVerificationGraph = tasks.register<Exec>("verifyVerificationGraph") {
    group = "verification"
    description = "Rejects a stale snapshot of production, test, fixture, and generated-contract consumers."
    dependsOn("generateVerificationGraph")
    inputs.files("config/ci/verification-graph.json", ".github/scripts/ci/verification_plan.py")
    inputs.file(layout.buildDirectory.file("reports/ci/verification-graph.json"))
    commandLine("python3", ".github/scripts/ci/verification_plan.py", "--check-generated",
        layout.buildDirectory.file("reports/ci/verification-graph.json").get().asFile)
}
tasks.named("check") { dependsOn(verifyVerificationGraph) }

sourceValidationGate.configure {
    dependsOn(modulePaths.flatMap { listOf("$it:scopedCheck", "$it:check") })
}

// A qualified candidate owns these source proofs in the same Gradle invocation.
productBuildGate.configure { dependsOn(sourceValidationGate) }

val configurationIngressTest = tasks.register<Exec>("configurationIngressTest") {
    group = "verification"
    environment("PYTHONPATH", packagingTestModulePath)
    inputs.files("packaging/internal/configuration/configuration_ingress.py", "packaging/tests/test-configuration-ingress.py")
    commandLine("python3", layout.projectDirectory.file("packaging/tests/test-configuration-ingress.py"))
}

val verifyConfigurationIngress = tasks.register<Exec>("verifyConfigurationIngress") {
    group = "verification"
    description = "Rejects undeclared Kast input references and ambient reads outside named ingress owners."
    dependsOn(":cli:generateConfigurationCatalogue", configurationIngressTest)
    inputs.file(generatedConfigurationCatalogue)
    inputs.files("config/configuration-ingress.json", "packaging/internal/configuration/configuration_ingress.py", "packaging/configuration-schema.json")
    inputs.files(fileTree(layout.projectDirectory) {
        include("**/src/main/**/*.kt", "**/src/main/**/*.kts", "**/src/gradleTooling/**/*.kt",
            "**/src/main/scripts/**", "*.kts", "install.sh", "packaging/internal/*/*.sh", "packaging/internal/*/*.py",
            "packaging/composed_bundle.py", "packaging/managed_headless.py")
        exclude("**/build/**", "**/.gradle/**", "**/node_modules/**")
    })
    val root = layout.projectDirectory
    val catalogue = generatedConfigurationCatalogue
    executable("python3")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(root.file("packaging/internal/configuration/configuration_ingress.py").asFile.absolutePath,
            "--root", root.asFile.absolutePath, "--schema", catalogue.get().asFile.absolutePath,
            "--policy", root.file("config/configuration-ingress.json").asFile.absolutePath,
            "--snapshot", root.file("packaging/configuration-schema.json").asFile.absolutePath)
    })
}

val pythonTestEnvironment = layout.buildDirectory.dir("python-tests/env")
val pythonTestExecutable = pythonTestEnvironment.map { it.file("bin/python3").asFile.absolutePath }
val preparePythonTestEnvironment = tasks.register<Exec>("preparePythonTestEnvironment") {
    group = "verification"
    description = "Provisions the pinned Python dependencies used by the routine test gate."
    inputs.files("config/python/pyproject.toml", "config/python/uv.lock", "config/python/uv.toml")
    inputs.file(layout.projectDirectory.file("packaging/internal/verification/prepare-python-test-environment.py"))
    commandLine("python3", layout.projectDirectory.file("packaging/internal/verification/prepare-python-test-environment.py"))
}

val knowledgeParserClasspath = files(
    JsonContractScanRequest::class.java.protectionDomain.codeSource.location,
    jsonContractParser,
)

val prepareKnowledgeParser = tasks.register("prepareKnowledgeParser") {
    group = "verification"
    description = "Publishes the isolated Kotlin PSI parser classpath for documentation checks."
    inputs.files(knowledgeParserClasspath)
    val output = layout.buildDirectory.file("reports/documentation/knowledge-parser-classpath.txt")
    val parserPath = knowledgeParserClasspath.asPath
    outputs.file(output)
    doLast {
        output.get().asFile.apply { parentFile.mkdirs(); writeText(parserPath) }
    }
}

verifyKnowledgeClaims.configure {
    dependsOn(prepareKnowledgeParser, documentationPythonDependencies)
    executableFile.set(validationExecutable("uv"))
    val assessment = layout.buildDirectory.file("reports/documentation/knowledge-assessment.json")
    outputs.file(assessment)
    arguments.set(listOf("run", "--locked", "--project", "docs/internal/tooling", "python", "docs/internal/tooling/knowledge_check.py",
        "--report", assessment.get().asFile.absolutePath))
    environmentVariables.put("KAST_KNOWLEDGE_PARSER_CLASSPATH", knowledgeParserClasspath.asPath)
    runtimeFiles.from(documentationPython, documentationEnvironment, validationExecutable("java"))
    environmentVariables.put("UV_PROJECT_ENVIRONMENT", documentationEnvironment.get().asFile.absolutePath)
}

val knowledgeBaseTest = tasks.register<Exec>("knowledgeBaseTest") {
    group = "verification"
    dependsOn(preparePythonTestEnvironment)
    description = "Exercises repository knowledge-base validation behavior."
    inputs.files(knowledgeParserClasspath)
    environment("KAST_KNOWLEDGE_PARSER_CLASSPATH", knowledgeParserClasspath.asPath)
    inputs.files(
        layout.projectDirectory.file(".github/scripts/code_kb.py"),
        layout.projectDirectory.file(".github/scripts/test_code_kb.py"),
    )
    commandLine(pythonTestExecutable.get(), layout.projectDirectory.file(".github/scripts/test_code_kb.py"))
}

val verifyKnowledgeBase = tasks.register<Exec>("verifyKnowledgeBase") {
    group = "verification"
    dependsOn(preparePythonTestEnvironment)
    description = "Rejects malformed OpenWiki records and reports current declaration drift."
    inputs.files(knowledgeParserClasspath)
    environment("KAST_KNOWLEDGE_PARSER_CLASSPATH", knowledgeParserClasspath.asPath)
    dependsOn(knowledgeBaseTest)
    inputs.dir(layout.projectDirectory.dir("openwiki"))
    inputs.file(layout.projectDirectory.file(".github/scripts/code_kb.py"))
    commandLine(
        pythonTestExecutable.get(),
        layout.projectDirectory.file(".github/scripts/code_kb.py"),
        "check-source-integrity",
        "--repo",
        layout.projectDirectory,
        "--docs",
        "openwiki",
        "--strict",
        "--format",
        "json",
    )
}

tasks.register<Exec>("knowledgeImpact") {
    group = "help"
    dependsOn(preparePythonTestEnvironment)
    description = "Reports knowledge concepts affected by current working-tree changes."
    doNotTrackState("The report intentionally observes the live Git working tree.")
    commandLine(
        pythonTestExecutable.get(),
        layout.projectDirectory.file(".github/scripts/code_kb.py"),
        "impact",
        "--repo",
        layout.projectDirectory,
        "--docs",
        "openwiki",
        "--from-git",
    )
}

val hostObservationTest = tasks.register<Exec>("hostObservationTest") {
    dependsOn(preparePythonTestEnvironment)
    group = "verification"
    description = "Checks the semantic campaign, agent efficiency, and log visibility experiment code."
    inputs.dir(layout.projectDirectory.dir("experiments/host-observation"))
    commandLine(pythonTestExecutable.get(), "-m", "unittest", "discover", "-s", "experiments/host-observation", "-p", "test_*.py")
}

val componentReleaseTest = tasks.register<Exec>("componentReleaseTest") {
    dependsOn(preparePythonTestEnvironment)
    group = "verification"
    description = "Proves strict component release contracts, inventory ownership, and byte admission."
    inputs.files("distribution/release/component_release.py", "distribution/release/test_component_release.py",
        "distribution/release/component-release.schema.json", "distribution/release/resolve_version.py",
        "distribution/release/test_resolve_version.py",
        "protocol/contract/src/main/resources/ide-hosted/hosted-endpoint.schema.json")
    commandLine(pythonTestExecutable.get(), "-m", "unittest", "discover", "-s", "distribution/release", "-p", "test_component_release.py")
}

tasks.named("check") { dependsOn(verifyConfigurationIngress, verifyKnowledgeBase, hostObservationTest, componentReleaseTest) }

val semanticCoverageDirectory = layout.projectDirectory.dir("config/kotlin-semantic-coverage")
val semanticInputGate = tasks.register<Exec>("verifyKotlinSemanticInputs") {
    group = "verification"
    description = "Rejects drift in authored semantic resources and reviewed fixture carriers."
    workingDir(semanticCoverageDirectory.dir("tooling"))
    commandLine("uv", "run", "--locked", "--script", "inventory.py", "inputs",
        "--repository-root", layout.projectDirectory,
        "--reviewed-carriers", semanticCoverageDirectory.file("reviewed-fixture-carriers.tsv"))
}

val nativeFixtureIdea = SharedNativeFixtureIdea.select(project, libs.versions.idea.platform.build.get())
// Inventory accounting and successful traces remain separate admission obligations.
val semanticInventoryGate = tasks.register<Exec>("verifyKotlinSemanticInventory") {
    group = "verification"
    description = "Examines the pinned FIR population and classified evidence without claiming trace completion."
    dependsOn(nativeFixtureIdea.contents, ":runtime:hosted:semanticCoverageFixtureTest")
    workingDir(semanticCoverageDirectory.dir("tooling"))
    val pluginRoot = nativeFixtureIdea.home.get().asFile.resolve("plugins/Kotlin/lib")
    commandLine("uv", "run", "--locked", "--script", "inventory.py", "audit", "--require-element-correspondence",
        "--plugin-jar", pluginRoot.resolve("kotlin-plugin.jar"),
        "--compiler-common-jar", pluginRoot.resolve("kotlinc.kotlin-compiler-common.jar"),
        "--javap", java.nio.file.Path.of(System.getProperty("java.home"), "bin", "javap"),
        "--required-cases", semanticCoverageDirectory.file("inventory-evidence-cases.tsv"),
        "--junit", layout.projectDirectory.file("runtime/hosted/build/test-results/semanticCoverageFixtureTest/TEST-io.github.amichne.kast.runtime.hosted.NativeScopedCacheManagerTraceTest.xml"))
}
val semanticTraceGate = tasks.register<Exec>("verifyKotlinSemanticCoverage") {
    group = "verification"
    description = "Requires the exact semantic case population and successful assertion results."
    dependsOn(":relation:intellij:semanticCoverageFixtureTest", ":runtime:hosted:semanticCoverageFixtureTest",
        ":symbol:intellij:semanticCoverageFixtureTest")
    workingDir(semanticCoverageDirectory.dir("tooling"))
    commandLine("uv", "run", "--locked", "--script", "inventory.py", "execution",
        "--required-cases", semanticCoverageDirectory.file("cases.tsv"),
        "--junit", layout.projectDirectory.dir("relation/intellij/build/test-results/semanticCoverageFixtureTest"),
        "--junit", layout.projectDirectory.dir("runtime/hosted/build/test-results/semanticCoverageFixtureTest"),
        "--junit", layout.projectDirectory.dir("symbol/intellij/build/test-results/semanticCoverageFixtureTest"))
}
tasks.named("check") { dependsOn(semanticInputGate, semanticInventoryGate, semanticTraceGate) }

val selectedSemanticOwners = providers.gradleProperty("kastSemanticOwners").map { raw ->
    val owners = raw.split(',')
    require(owners.isNotEmpty() && owners.distinct().size == owners.size && owners.all {
        it in setOf(":relation:intellij", ":runtime:hosted", ":symbol:intellij")
    }) { "kastSemanticOwners must name distinct semantic fixture owners" }
    owners
}.orElse(emptyList())

tasks.register<Exec>("verifySelectedKotlinSemanticCoverage") {
    group = "verification"
    description = "Requires every case of each selected semantic fixture owner with exact report accounting."
    dependsOn(selectedSemanticOwners.get().map { "$it:semanticCoverageFixtureTest" })
    workingDir(semanticCoverageDirectory.dir("tooling"))
    val selectedManifest = providers.gradleProperty("kastSemanticSelection")
        .map { file(it) }.orElse(layout.buildDirectory.file("reports/ci/source/missing-selection.tsv").map { it.asFile })
    inputs.file(selectedManifest)
    val reports = selectedSemanticOwners.map { owners -> owners.map { owner ->
        layout.projectDirectory.dir(owner.removePrefix(":").replace(':', '/') + "/build/test-results/semanticCoverageFixtureTest")
    } }
    inputs.files(reports)
    commandLine(listOf("uv", "run", "--locked", "--script", "inventory.py", "execution", "--required-cases", selectedManifest.get()) +
        reports.get().flatMap { listOf("--junit", it.asFile) })
}
