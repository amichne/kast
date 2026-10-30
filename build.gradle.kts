import support.consumeProjectOutput
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.api.tasks.testing.Test
import support.tasks.idea.selectedIdeaProductInfo
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
val hostedContractFiles = consumeProjectOutput("hostedContractFiles", ":protocol:wire", "hostedContractElements")
val contractFile = layout.file(hostedContractFiles.flatMap { it.elements }.map { it.single().asFile })
val registryFiles = consumeProjectOutput("operationRegistryFiles", ":protocol:wire", "operationRegistryElements")
val generatedOperationRegistry = layout.file(registryFiles.flatMap { it.elements }.map { it.single().asFile })
val catalogueFiles = consumeProjectOutput("configurationCatalogueFiles", ":cli", "configurationCatalogueElements")
val generatedConfigurationCatalogue = layout.file(catalogueFiles.flatMap { it.elements }.map { it.single().asFile })
val providerFiles = consumeProjectOutput("providerCatalogueFiles", ":cli", "providerCatalogueElements")
val generatedProviderCatalogue = layout.file(providerFiles.flatMap { it.elements }.map { it.single().asFile })
val cliInstallation = consumeProjectOutput("cliInstallation", ":cli", "cliInstallationElements")
val managementExecutable = consumeProjectOutput("managementExecutable", ":distribution:cli", "managementExecutableElements")
val queryExecutableFiles = consumeProjectOutput("queryExecutable", ":cli", "queryExecutableElements")

val publicLauncherTestClasses = consumeProjectOutput("publicLauncherTestClasses", ":cli", "publicLauncherTestClassesElements")
val publicLauncherTestRuntime = consumeProjectOutput("publicLauncherTestRuntime", ":cli", "publicLauncherTestRuntimeElements")

abstract class PublicLauncherArguments : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val executable: org.gradle.api.file.RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val queryExecutable: org.gradle.api.file.RegularFileProperty

    override fun asArguments(): Iterable<String> =
        listOf(
            "-Dkast.publicLauncher=${executable.get().asFile.absolutePath}",
            "-Dkast.queryExecutable=${queryExecutable.get().asFile.absolutePath}",
        )
}

val publicLauncherTest = tasks.register<Test>("publicLauncherTest") {
    description = "Exercises the public native launcher and query payload with a local Unix socket peer."
    group = "verification"
    testClassesDirs = files(publicLauncherTestClasses)
    classpath = files(publicLauncherTestRuntime)
    useJUnitPlatform { includeTags("public-launcher") }
    binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/publicLauncherTest/binary"))
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/publicLauncherTest"))
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/publicLauncherTest"))
    jvmArgumentProviders.add(objects.newInstance<PublicLauncherArguments>().apply {
        executable.set(layout.file(managementExecutable.flatMap { it.elements }.map { it.single().asFile }))
        queryExecutable.set(layout.file(queryExecutableFiles.flatMap { it.elements }.map { it.single().asFile }))
    })
}

val generatedControlMetadata = layout.buildDirectory.dir("generated/control-metadata")
tasks.named("generateKastDocumentation") {
    dependsOn(":distribution:cli:generateManagementVersion")
}
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
    ideaBuild.set(libs.versions.ide.host.build)
    kotlinPluginBuild.set(libs.versions.ide.kotlin.plugin.build)
    outputDirectory.set(generatedControlMetadata)
}

val controlProductDirectory = layout.buildDirectory.dir("control-product")
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
    dependsOn(":cli:installDist", generateKastControlMetadata, generateKastAgentTools)
    into(controlProductDirectory)
    from(cliInstallation) {
        exclude("bin/cli", "bin/kast.bat")
    }
    from(queryExecutableFiles) {
        into("bin")
        rename { "kast-query" }
        filePermissions { unix("755") }
    }
    from(generatedControlMetadata) {
        into("share/kast")
    }
    from(agentToolsDirectory) {
        into("share/kast/agent-tools")
    }
    from(managementExecutable) {
        into("share/kast/libexec")
        rename { "kast-management" }
    }
    from("install.sh") {
        into("share/kast")
    }
    from("distribution/cli/lifecycle-fence-v1") { into("share/kast") }
    from("distribution/cli/public-reset-v1") { into("share/kast") }
    from("distribution/cli/reset-fence-v1") { into("share/kast") }
    from("packaging/install-completion-v1") { into("share/kast") }
    from("distribution/contract/src/main/resources/management/reset-inventory-observation.schema.json") { into("share/kast/schema") }
    from("distribution/contract/src/main/resources/management/management-lifecycle.schema.json") { into("share/kast/schema") }
    from("distribution/cli/one-shot-observation-v1") {
        into("share/kast")
    }
    from(listOf("packaging/installation-lifecycle.py", "packaging/installation-recovery.py", "packaging/prune-prior-installations.py", "packaging/codex-mcp-registration.py", "packaging/host-installation.py")) { into("share/kast") }
}

val controlArchivePlatform = providers.gradleProperty("kastControlPlatform").zip(providers.gradleProperty("kastNativeExecutable").orElse("")) { platform, executable ->
    require(platform == "macos-aarch64") { "Imported native archives must target macos-aarch64" }
    require(executable.isNotBlank()) { "An imported platform needs a native executable" }
    platform
}.zip(providers.gradleProperty("kastQueryNativeExecutable").orElse("")) { platform, queryExecutable ->
    require(queryExecutable.isNotBlank()) { "An imported platform needs a query executable" }
    platform
}.orElse(providers.systemProperty("os.name").zip(providers.systemProperty("os.arch")) { os, arch ->
    when {
        os.startsWith("Mac") && arch in setOf("aarch64", "arm64") -> "macos-aarch64"
        os == "Linux" && arch in setOf("amd64", "x86_64") -> "linux-x86_64"
        else -> throw GradleException("Control archive requires macOS Apple silicon or Linux x86_64")
    }
})

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
                "bin/kast-tool-rpc",
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
    qualifiedQueryExecutable.set(layout.file(queryExecutableFiles.flatMap { it.elements }.map { it.single().asFile }))
    targetPlatform.set(controlArchivePlatform)
    maximumEntries.set(controlDistributionMaximumEntries)
    maximumArchiveBytes.set(64L * 1024L * 1024L)
    maximumInstalledBytes.set(128L * 1024L * 1024L)
}

val releaseSourceRevision = providers.gradleProperty("kastSourceRevision").orElse(providers.exec {
    commandLine("git", "rev-parse", "HEAD")
    workingDir(rootDir)
}.standardOutput.asText.map(String::trim))
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
    companionArtifacts.from(layout.projectDirectory.file("packaging/host-installation.py"))
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


apply(from = "distribution/release/plugin-release.gradle.kts")

tasks.register("verifyDistributionContent") {
    group = "verification"
    description = "Verifies control/sidecar separation and required artifact layouts."
    dependsOn(verifyKastControlDistLayout, ":runtime:hosted:hostedPlugin", ":app-server:verifyReleaseRuntimeAdmission")
}

val canonicalLocalInstallPrefix = providers.environmentVariable("HOME")
    .map { userHome ->
        require(userHome.isNotBlank() && java.io.File(userHome).isAbsolute) { "HOME must name an absolute user directory" }
        file(userHome).toPath().toAbsolutePath().normalize().resolve(".local").toFile()
    }
val localInstallPrefix = providers.gradleProperty("kastLocalPrefix")
    .map { configuredPrefix ->
        require(configuredPrefix.isNotBlank()) { "kastLocalPrefix must name a non-blank installation prefix" }
        val prefix = file(configuredPrefix).toPath().toAbsolutePath().normalize().toFile()
        require(prefix == canonicalLocalInstallPrefix.get()) { "kastLocalPrefix must equal HOME/.local; alternate installations are unsupported" }
        prefix
    }
    .orElse(canonicalLocalInstallPrefix)
val localJavaHome = providers.systemProperty("java.home").map { configuredHome ->
    file(configuredHome).toPath().toRealPath().toFile()
}
val localJavaExecutable = localJavaHome.map { home -> home.resolve("bin/java") }

val localHostedIdeaBuild = providers.gradleProperty("hostedIdeaHome").map { home ->
    val metadata = providers.fileContents(layout.projectDirectory.file(selectedIdeaProductInfo(file(home)).absolutePath)).asText.get()
    (groovy.json.JsonSlurper().parseText(metadata) as Map<*, *>)["buildNumber"] as String
}.orElse(libs.versions.ide.host.build)
val localHostedPluginArchive = localHostedIdeaBuild.map { build ->
    val releaseLine = build.substringBefore('.')
    layout.projectDirectory.file("runtime/hosted/build/distributions/kast-ide-hosted-v${hostedPluginVersion.get()}-idea-$releaseLine.zip")
}

tasks.register<Exec>("installLocal") {
    group = "distribution"
    description = "Installs a control/host pair (-PcontrolVersion, -PhostedPluginVersion) in the sole HOME/.local/share/kast installation."
    doNotTrackState("The installed prefix contains live service sockets and is mutated by the installer.")
    dependsOn(stageKastControlProduct, ":runtime:hosted:hostedPlugin", "generateHostReleaseRecord")
    inputs.dir(controlProductDirectory)
    inputs.file(localHostedPluginArchive)
    inputs.files("packaging/install-local.sh", "install.sh")
    inputs.property("localInstallPrefix", localInstallPrefix.map { it.absolutePath })
    environment("KAST_LOCAL_PREFIX", localInstallPrefix.get().absolutePath)
    environment("KAST_LOCAL_CONTROL_PRODUCT", controlProductDirectory.get().asFile.absolutePath)
    environment("KAST_LOCAL_HOSTED_PLUGIN_ARCHIVE", localHostedPluginArchive.get().asFile.absolutePath)
    environment("KAST_LOCAL_HOST_RELEASE_RECORD", layout.buildDirectory.file("generated/host-release/kast-host-release-v${hostedPluginVersion.get()}.json").get().asFile.absolutePath)
    providers.gradleProperty("hostedIdeaHome").orNull?.let { environment("KAST_INSTALL_IDEA_HOME", it) }
    commandLine("bash", layout.projectDirectory.file("packaging/install-local.sh"))
}

val installedProductTest = tasks.register<Exec>("installedProductTest") {
    group = "verification"
    description = "Verifies assembled artifacts, staging, and native Host/SQLite/full cleanup in an owned HOME."
    dependsOn(stageKastControlProduct, assembleKastControlDist, "generateHostReleaseRecord")
    inputs.dir(controlProductDirectory)
    inputs.file(assembleKastControlDist.flatMap(Tar::getArchiveFile))
    inputs.file(hostedPluginArchive)
    val hostRecord = layout.buildDirectory.file("generated/host-release/kast-host-release-v${hostedPluginVersion.get()}.json")
    inputs.file(hostRecord)
    inputs.files("install.sh", "packaging/installer_fixture.py", "packaging/run-installed-product.py")
    inputs.files(
        "evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteLiveChangePlanStore.kt",
        "evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteMutationRecoveryDatabase.kt",
        "evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteLiveChangeReceiptStore.kt",
    )
    outputs.upToDateWhen { false }
    environment("KAST_INSTALLED_PRODUCT", controlProductDirectory.get().asFile.absolutePath)
    environment("KAST_CONTROL_ARCHIVE", assembleKastControlDist.get().archiveFile.get().asFile.absolutePath)
    val archive = hostedPluginArchive
    doFirst { environment("KAST_HOSTED_PLUGIN_ARCHIVE", archive.get().asFile.absolutePath) }
    environment("KAST_HOST_RELEASE_RECORD", hostRecord.get().asFile.absolutePath)
    environment("KAST_ACCEPTANCE_JAVA_EXECUTABLE", localJavaExecutable.get().absolutePath)
    commandLine("python3", layout.projectDirectory.file("packaging/run-installed-product.py"))
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

val offlineInstallerChecks = setOf(
    "testCheckoutInstaller", "installerEntrypointTest", "installerFixtureTest", "installerRemovalTest",
    "installationSystemPythonTest", "installationRecoveryTest", "installationLifecycleTest",
    "priorInstallationCleanupTest", "localInstallationTest",
)

val offlineInstallerTools = providers.exec {
    workingDir(layout.projectDirectory)
    setEnvironment(validationEnvironment.get())
    commandLine(validationExecutable("python3").get().asFile, "-c",
        "import json, sys; sys.path.insert(0, 'packaging'); from installer_fixture import admitted_tools; print(json.dumps({name: str(path) for name, path in admitted_tools().items()}))")
}.standardOutput.asText.map { Json.decodeFromString<Map<String, String>>(it) }

tasks.withType<VerificationReportTask>().configureEach {
    if (name in offlineInstallerChecks) {
        sourceFiles.from(fileTree("packaging") { include("*.py", "*.sh") })
        sourceFiles.from("build.gradle.kts", "distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/ControlDistributionLimits.kt")
        inputs.property("installerToolPaths", offlineInstallerTools)
        runtimeFiles.from("/bin/bash", offlineInstallerTools.map { tools -> tools.values.map { path -> java.io.File(path) } })
    }
}

val localPluginReloadTest = tasks.register<VerificationReportTask>("localPluginReloadTest") {
    group = "verification"
    description = "Examines local plugin replacement admission and atomic directory exchange."
    inputs.files("packaging/reload_hosted.py", "packaging/reload_hosted.py.lock", "packaging/test_reload_hosted.py")
    executableFile.set(validationExecutable("uv"))
    arguments.set(listOf("run", "--locked", "--script", layout.projectDirectory.file("packaging/reload_hosted.py").asFile.absolutePath, "--self-test"))
}
tasks.named("check") { dependsOn(localPluginReloadTest) }

val testCheckoutInstaller = tasks.register<VerificationReportTask>("testCheckoutInstaller") {
    group = "verification"
    description = "Verifies checkout and release bootstrap boundaries without touching machine state."
    inputs.files("install.sh", "packaging/install-checkout.sh", "packaging/test-install-checkout.py")
    executableFile.set(validationExecutable("python3"))
    arguments.set(listOf(layout.projectDirectory.file("packaging/test-install-checkout.py").asFile.absolutePath))
}

val installerEntrypointTest = tasks.register<VerificationReportTask>("installerEntrypointTest") {
    group = "verification"
    description = "Pins the documented Bash -c installer invocation and argument delivery contract."
    inputs.files("install.sh", "README.md", "docs/public/start.mdx", "docs/public/reference/installation.mdx",
        "docs/public/reference/compatibility.mdx", "packaging/test-installer-entrypoint.py")
    executableFile.set(validationExecutable("python3"))
    arguments.set(listOf(layout.projectDirectory.file("packaging/test-installer-entrypoint.py").asFile.absolutePath))
}

val installerFixtureTest = tasks.register<VerificationReportTask>("installerFixtureTest") {
    group = "verification"
    description = "Proves installer fixtures isolate their home and remove only owned roots."
    inputs.files("packaging/installer_fixture.py", "packaging/test-installer-fixture.py")
    executableFile.set(validationExecutable("python3"))
    arguments.set(listOf(layout.projectDirectory.file("packaging/test-installer-fixture.py").asFile.absolutePath))
}

val installerRemovalTest = tasks.register<VerificationReportTask>("installerRemovalTest") {
    group = "verification"
    inputs.files("install.sh", "packaging/test-installer-removal.py")
    executableFile.set(validationExecutable("python3"))
    arguments.set(listOf(layout.projectDirectory.file("packaging/test-installer-removal.py").asFile.absolutePath))
}

val installationSystemPythonTest = tasks.register<VerificationReportTask>("installationSystemPythonTest") {
    group = "verification"
    description = "Proves offline recovery works with macOS system Python, independently of the development interpreter."
    inputs.files("packaging/installation-recovery.py", "packaging/installation-lifecycle.py", "packaging/test-installation-recovery.py")
    executableFile.set(layout.file(providers.provider { java.io.File("/usr/bin/python3") }))
    arguments.set(listOf(layout.projectDirectory.file("packaging/test-installation-recovery.py").asFile.absolutePath))
}

val installationRecoveryTest = tasks.register<VerificationReportTask>("installationRecoveryTest") {
    group = "verification"
    inputs.files("packaging/installation-recovery.py", "packaging/test-installation-recovery.py")
    executableFile.set(validationExecutable("python3"))
    arguments.set(listOf(layout.projectDirectory.file("packaging/test-installation-recovery.py").asFile.absolutePath))
}

val installationLifecycleTest = tasks.register<VerificationReportTask>("installationLifecycleTest") {
    group = "verification"
    description = "Proves explicit installation reset preserves configuration and rejects unresolved ownership."
    inputs.files("packaging/installation-lifecycle.py", "packaging/test-installation-lifecycle.py")
    executableFile.set(validationExecutable("python3"))
    arguments.set(listOf(layout.projectDirectory.file("packaging/test-installation-lifecycle.py").asFile.absolutePath))
}

val priorInstallationCleanupTest = tasks.register<VerificationReportTask>("priorInstallationCleanupTest") {
    group = "verification"
    description = "Proves exact user review and selected-installation protection for historical cleanup."
    inputs.files("packaging/prune-prior-installations.py", "packaging/installation-lifecycle.py",
        "packaging/installation-recovery.py", "packaging/test-prune-prior-installations.py")
    executableFile.set(validationExecutable("python3"))
    arguments.set(listOf(layout.projectDirectory.file("packaging/test-prune-prior-installations.py").asFile.absolutePath))
}

val localInstallationTest = tasks.register<VerificationReportTask>("localInstallationTest") {
    group = "verification"
    inputs.files("packaging/install-local.sh", "packaging/test-install-local.py")
    executableFile.set(validationExecutable("python3"))
    arguments.set(listOf(layout.projectDirectory.file("packaging/test-install-local.py").asFile.absolutePath))
}

val productGateVersionTest = tasks.register<Exec>("productGateVersionTest") {
    group = "verification"
    description = "Proves routine gates refresh published release authority before invoking Gradle."
    inputs.files(
        "distribution/release/resolve_version.py",
        "distribution/release/run_product_gate.py",
        "distribution/release/test_run_product_gate.py",
    )
    commandLine("python3", layout.projectDirectory.file("distribution/release/test_run_product_gate.py"))
}

val documentationMarkdownDependencies = tasks.register<Exec>("documentationMarkdownDependencies") {
    group = "verification"
    description = "Installs the locked CommonMark parser without package scripts."
    inputs.files("docs/tooling/markdown/package.json", "docs/tooling/markdown/package-lock.json", "mise.toml")
    outputs.dir("docs/tooling/markdown/node_modules")
    inputs.files(validationExecutable("node"), validationExecutable("npm"))
    inputs.property("validationEnvironment", validationEnvironment)
    environment("PATH", providers.environmentVariable("PATH").get())
    val emptyConfig = layout.buildDirectory.file("documentation/npm-global-empty")
    environment("NPM_CONFIG_USERCONFIG", "/dev/null")
    environment("NPM_CONFIG_GLOBALCONFIG", emptyConfig.get().asFile.absolutePath)
    environment("NPM_CONFIG_REGISTRY", "https://registry.npmjs.org")
    environment("NPM_CONFIG_CACHE", layout.buildDirectory.dir("documentation/npm-cache").get().asFile.absolutePath)
    doFirst {
        emptyConfig.get().asFile.apply { parentFile.mkdirs(); writeText("") }
    }
    commandLine(validationExecutable("npm").get().asFile, "ci", "--ignore-scripts", "--engine-strict", "--prefix", "docs/tooling/markdown")
}

val documentationEnvironment = layout.buildDirectory.dir("documentation/python")
val documentationPythonVersion = providers.fileContents(layout.projectDirectory.file(".python-version")).asText.map { it.trim() }
val documentationPython = providers.exec {
    commandLine(validationExecutable("uv").get().asFile, "python", "find", "--system", documentationPythonVersion.get())
}.standardOutput.asText.map { it.trim() }

val documentationPythonDependencies = tasks.register<Exec>("documentationPythonDependencies") {
    group = "verification"
    description = "Prepares the locked Python runtime for documentation checks."
    inputs.files("docs/tooling/pyproject.toml", "docs/tooling/uv.lock", ".python-version", validationExecutable("uv"))
    inputs.file(documentationPython)
    inputs.property("validationEnvironment", validationEnvironment)
    outputs.dir(documentationEnvironment)
    setEnvironment(validationEnvironment.get())
    environment("UV_PROJECT_ENVIRONMENT", documentationEnvironment.get().asFile.absolutePath)
    commandLine(validationExecutable("uv").get().asFile, "sync", "--locked", "--project", "docs/tooling", "--python", documentationPython.get())
}

val verifyDocumentationMarkdown = tasks.register<VerificationReportTask>("verifyDocumentationMarkdown") {
    group = "verification"
    description = "Examines the typed CommonMark adapter."
    dependsOn(documentationMarkdownDependencies)
    inputs.files("docs/tooling/markdown/parse.ts", "docs/tooling/markdown/tsconfig.json", "docs/tooling/markdown/package.json", "docs/tooling/markdown/package-lock.json")
    executableFile.set(validationExecutable("npm"))
    arguments.set(listOf("run", "check", "--prefix", "docs/tooling/markdown"))
}

val documentationToolingTest = tasks.register<VerificationReportTask>("documentationToolingTest") {
    group = "verification"
    description = "Exercises local glossary grammar, scope and projection rejection."
    dependsOn(verifyDocumentationMarkdown, documentationPythonDependencies)
    inputs.files(fileTree("docs/tooling") { exclude(".venv/**", "__pycache__/**", ".pytest_cache/**", "markdown/node_modules/**") })
    inputs.files("docs/glossary.json", "docs/GLOSSARY.md", "docs/public/glossary.mdx")
    inputs.dir("config/vale/styles/Kast")
    executableFile.set(validationExecutable("uv"))
    arguments.set(listOf("run", "--locked", "--project", "docs/tooling", "pytest", "docs/tooling"))
}

val verifyPublicKnowledge = tasks.register<VerificationReportTask>("verifyPublicKnowledge") {
    group = "verification"
    description = "Rejects public OKF projection drift and missing tracked attribution."
    dependsOn(documentationMarkdownDependencies, documentationPythonDependencies)
    doNotTrackState("Attribution examines current Git tracking and the source bytes cited by OpenWiki Claims.")
    executableFile.set(validationExecutable("uv"))
    arguments.set(listOf("run", "--locked", "--project", "docs/tooling", "python", "docs/tooling/okf_public.py", "--check"))
}

val verifyDocumentationGlossary = tasks.register<VerificationReportTask>("verifyDocumentationGlossary") {
    group = "verification"
    description = "Rejects glossary projection drift and rejected forms in current prose."
    dependsOn(documentationMarkdownDependencies, documentationPythonDependencies)
    inputs.files(fileTree("docs/tooling") { exclude(".venv/**", "__pycache__/**", ".pytest_cache/**", "markdown/node_modules/**") })
    inputs.files("docs/glossary.json", "docs/GLOSSARY.md", "README.md", "AGENTS.md")
    inputs.files(fileTree("docs") { include("**/*.md", "**/*.mdx", "**/*.txt"); exclude("**/.venv/**", "**/build/**", "**/node_modules/**") })
    inputs.dir("config/vale/styles/Kast")
    val scopedGuides = fileTree(rootDir) { include("**/AGENTS.md"); exclude("**/build/**", "**/.gradle/**", "**/.venv/**", "**/node_modules/**") }
    inputs.files(scopedGuides)
    executableFile.set(validationExecutable("uv"))
    arguments.set(listOf("run", "--locked", "--project", "docs/tooling", "python", "docs/tooling/check_glossary.py",
        "docs/glossary.json", "README.md", "docs"))
    arguments.addAll(scopedGuides.files.map { it.relativeTo(rootDir).path }.sorted())
    arguments.addAll("--check-render", "docs/GLOSSARY.md", "--check-public-render", "docs/public/glossary.mdx", "--check-vale", "config/vale/styles/Kast")
}

tasks.withType<VerificationReportTask>().configureEach {
    if (name in setOf("verifyDocumentationMarkdown", "documentationToolingTest", "verifyDocumentationGlossary", "verifyPublicKnowledge")) {
        runtimeFiles.from(validationExecutable("node"), validationExecutable("uv"), validationExecutable("npm"))
        runtimeFiles.from("docs/tooling/markdown/node_modules")
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

tasks.named("check") { dependsOn(verifyDocumentationGlossary, documentationToolingTest, verifyPublicKnowledge) }

val productBuildGate = tasks.register("productBuildGate") {
    group = "verification"
    description = "Builds every module and verifies deterministic contracts, architecture and packaging without runtime qualification."
    dependsOn(
        "check",
        productGateVersionTest,
        installerFixtureTest,
        installationLifecycleTest,
        priorInstallationCleanupTest,
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
        installationLifecycleTest,
        priorInstallationCleanupTest,
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
        exclude("**/build/**", "**/.gradle/**", "**/node_modules/**")
    })
    val root = layout.projectDirectory
    val catalogue = generatedConfigurationCatalogue
    executable("python3")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(root.file("packaging/configuration_ingress.py").asFile.absolutePath,
            "--root", root.asFile.absolutePath, "--schema", catalogue.get().asFile.absolutePath,
            "--policy", root.file("build-policy/configuration-ingress.json").asFile.absolutePath,
            "--snapshot", root.file("packaging/configuration-schema.json").asFile.absolutePath)
    })
}

val pythonTestEnvironment = layout.buildDirectory.dir("python-tests/env")
val pythonTestExecutable = pythonTestEnvironment.map { it.file("bin/python3").asFile.absolutePath }
val preparePythonTestEnvironment = tasks.register<Exec>("preparePythonTestEnvironment") {
    group = "verification"
    description = "Provisions the pinned Python dependencies used by the routine test gate."
    inputs.file(layout.projectDirectory.file("experiments/host-observation/requirements-test.txt"))
    inputs.file(layout.projectDirectory.file("packaging/prepare-python-test-environment.py"))
    commandLine("python3", layout.projectDirectory.file("packaging/prepare-python-test-environment.py"))
}

val knowledgeParserClasspath = files(
    JsonContractScanRequest::class.java.protectionDomain.codeSource.location,
    jsonContractParser,
)

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
    description = "Validates OpenWiki links, source paths, and exact declaration citations."
    inputs.files(knowledgeParserClasspath)
    environment("KAST_KNOWLEDGE_PARSER_CLASSPATH", knowledgeParserClasspath.asPath)
    dependsOn(knowledgeBaseTest)
    inputs.dir(layout.projectDirectory.dir("openwiki"))
    inputs.file(layout.projectDirectory.file(".github/scripts/code_kb.py"))
    commandLine(
        pythonTestExecutable.get(),
        layout.projectDirectory.file(".github/scripts/code_kb.py"),
        "check",
        "--repo",
        layout.projectDirectory,
        "--docs",
        "openwiki",
        "--strict",
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
    description = "Checks the launch-free experimental host-observation controller."
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

// Inventory accounting and successful traces remain separate admission obligations.
val semanticInventoryGate = tasks.register<Exec>("verifyKotlinSemanticInventory") {
    group = "verification"
    description = "Examines the pinned FIR population and classified evidence without claiming trace completion."
    dependsOn(":relation:intellij:extractRelationKotlinPlugin", ":runtime:hosted:semanticCoverageFixtureTest")
    workingDir(semanticCoverageDirectory.dir("tooling"))
    val fixtureHome = providers.gradleProperty("nativeFixtureIdeaHome")
        .map { file(it) }.getOrElse(layout.projectDirectory.dir("relation/intellij/build/native-test/idea").asFile)
    val pluginRoot = fixtureHome.resolve("plugins/Kotlin/lib")
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
