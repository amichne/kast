import conventions.registerJsonContractVerification
import conventions.jsoncontracts.JsonContractScanRequest
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip
import support.tasks.GenerateControlMetadataTask
import support.tasks.VerifyControlDistributionTask

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

val installedProductDirectory = layout.buildDirectory.dir("installed-product")
evaluationDependsOn(":runtime:hosted")
val hostedPluginArchive = project(":runtime:hosted").tasks.named<Zip>("hostedPlugin").flatMap(Zip::getArchiveFile)

val generatedControlMetadata = layout.buildDirectory.dir("generated/control-metadata")
val generatedOperationRegistry = project(":protocol:wire").layout.buildDirectory.file(
    "generated/operation-registry/operation-registry.json",
)
val generatedConfigurationCatalogue = project(":cli").layout.buildDirectory.file(
    "generated/configuration/configuration-schema.json",
)
val generateKastControlMetadata = tasks.register<GenerateControlMetadataTask>("generateKastControlMetadata") {
    group = "distribution"
    description = "Generates the existing-IDE plugin manifest and public schemas."
    dependsOn(":runtime:hosted:hostedPlugin", ":protocol:wire:generateOperationRegistry", ":cli:generateConfigurationCatalogue", ":cli:generateProviderCatalog")
    pluginArchive.set(hostedPluginArchive)
    licenseFile.set(layout.projectDirectory.file("LICENSE"))
    operationRegistryFile.set(generatedOperationRegistry)
    configurationCatalogueFile.set(generatedConfigurationCatalogue)
    providerCatalogueFile.set(project(":cli").layout.buildDirectory.file("generated/provider/provider-catalog.json"))
    productVersion.set(project.version.toString())
    ideaBuild.set(libs.versions.ide.host.build)
    kotlinPluginBuild.set(libs.versions.ide.kotlin.plugin.build)
    outputDirectory.set(generatedControlMetadata)
}

val controlProductDirectory = layout.buildDirectory.dir("control-product")
val stageKastControlProduct = tasks.register<Sync>("stageKastControlProduct") {
    group = "distribution"
    description = "Stages the Kast control installation for the existing IDE."
    dependsOn(":cli:installDist", generateKastControlMetadata)
    into(controlProductDirectory)
    from(project(":cli").layout.buildDirectory.dir("install/kast")) {
        exclude("bin/cli", "bin/kast.bat")
    }
    from(generatedControlMetadata) {
        into("share/kast")
    }
    from(listOf("packaging/installation-lifecycle.py", "packaging/installation-recovery.py", "packaging/prune-prior-installations.py", "packaging/codex-mcp-registration.py")) { into("share/kast") }
}

val assembleKastControlDist = tasks.register<Tar>("assembleKastControlDist") {
    group = "distribution"
    description = "Builds the private service, lifecycle, schema, broker, and wire-control archive."
    dependsOn(stageKastControlProduct)
    from(controlProductDirectory)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("kast-control-v${project.version}-macos-aarch64.tar.gz")
    compression = Compression.GZIP
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    eachFile {
        if (relativePath.pathString in setOf(
                "bin/kast",
                "bin/kast-codex",
                "bin/kast-mcp",
                "bin/kast-tool-rpc",
                "share/kast/libexec/kast-daemon",
                "share/kast/libexec/kast-service",
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
    maximumEntries.set(controlDistributionMaximumEntries)
    maximumArchiveBytes.set(64L * 1024L * 1024L)
    maximumInstalledBytes.set(128L * 1024L * 1024L)
}

apply(from = "distribution/release/plugin-release.gradle.kts")

tasks.register("verifyDistributionContent") {
    group = "verification"
    description = "Verifies control/sidecar separation and required artifact layouts."
    dependsOn(verifyKastControlDistLayout, ":runtime:hosted:hostedPlugin", ":app-server:verifyReleaseRuntimeAdmission")
}

val stageInstalledProduct = tasks.register<Sync>("stageInstalledProduct") {
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

val localHostedIdeaBuild = providers.gradleProperty("hostedIdeaHome").map { home ->
    val metadata = providers.fileContents(layout.projectDirectory.file("$home/Resources/product-info.json")).asText.get()
    (groovy.json.JsonSlurper().parseText(metadata) as Map<*, *>)["buildNumber"] as String
}.orElse(libs.versions.ide.host.build)
val localHostedPluginArchive = localHostedIdeaBuild.map { build ->
    val releaseLine = build.substringBefore('.')
    layout.projectDirectory.file("runtime/hosted/build/distributions/kast-ide-hosted-v${project.version}-idea-$releaseLine.zip")
}

tasks.register<Exec>("installLocal") {
    group = "distribution"
    description = "Installs a version-owned Kast product (-Pversion=x.y.z) under ~/.local or -PkastLocalPrefix."
    doNotTrackState("The installed prefix contains live service sockets and is mutated by the versioned installer.")
    dependsOn(stageKastControlProduct, ":runtime:hosted:hostedPlugin")
    inputs.dir(controlProductDirectory)
    inputs.file(localHostedPluginArchive)
    inputs.files("packaging/install-local.sh", "install.sh")
    inputs.property("localInstallPrefix", localInstallPrefix.map { it.absolutePath })
    inputs.property("localJavaHome", localJavaHome.map { it.absolutePath })
    inputs.property("localJavaExecutable", localJavaExecutable.map { it.absolutePath })
    environment("KAST_LOCAL_PREFIX", localInstallPrefix.get().absolutePath)
    environment("KAST_LOCAL_CONTROL_PRODUCT", controlProductDirectory.get().asFile.absolutePath)
    environment("KAST_LOCAL_HOSTED_PLUGIN_ARCHIVE", localHostedPluginArchive.get().asFile.absolutePath)
    providers.gradleProperty("hostedIdeaHome").orNull?.let { environment("KAST_INSTALL_IDEA_HOME", it) }
    environment("KAST_LOCAL_JAVA_HOME", localJavaHome.get().absolutePath)
    environment("KAST_LOCAL_JAVA_EXECUTABLE", localJavaExecutable.get().absolutePath)
    commandLine("bash", layout.projectDirectory.file("packaging/install-local.sh"))
}

val installedProductTest = tasks.register<Exec>("installedProductTest") {
    group = "verification"
    description = "Verifies plugin-only metadata and fail-closed IDE admission through the staged product."
    dependsOn(stageInstalledProduct, assembleKastControlDist)
    inputs.dir(installedProductDirectory)
    inputs.file(assembleKastControlDist.flatMap(Tar::getArchiveFile))
    inputs.file(hostedPluginArchive)
    inputs.file(layout.projectDirectory.file("packaging/test-installed-product.sh"))
    inputs.files("install.sh", "packaging/acceptance_environment.py", "packaging/run-installed-product.py")
    outputs.file(layout.buildDirectory.file("reports/installed-product/topology-installed-product.json"))
    outputs.upToDateWhen { false }
    environment("KAST_INSTALLED_PRODUCT", installedProductDirectory.get().asFile.absolutePath)
    environment("KAST_CONTROL_ARCHIVE", assembleKastControlDist.get().archiveFile.get().asFile.absolutePath)
    environment("KAST_HOSTED_PLUGIN_ARCHIVE", hostedPluginArchive.get().asFile.absolutePath)
    environment("KAST_ACCEPTANCE_JAVA_EXECUTABLE", localJavaExecutable.get().absolutePath)
    environment("KAST_PROJECT_ROOT", layout.projectDirectory.asFile.absolutePath)
    environment(
        "KAST_INSTALLED_REPORT_DIRECTORY",
        layout.buildDirectory.dir("reports/installed-product").get().asFile.absolutePath,
    )
    commandLine("bash", layout.projectDirectory.file("packaging/test-installed-product.sh"))
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
        "packaging/installed_acceptance_product.py", "packaging/run-installed-product.py")
    commandLine("python3", layout.projectDirectory.file("packaging/test-acceptance-environment.py"))
}

val installerRemovalTest = tasks.register<Exec>("installerRemovalTest") {
    group = "verification"
    inputs.files("install.sh", "packaging/test-installer-removal.py")
    commandLine("python3", layout.projectDirectory.file("packaging/test-installer-removal.py"))
}

val installationSystemPythonTest = tasks.register<Exec>("installationSystemPythonTest") {
    group = "verification"
    description = "Proves offline recovery works with macOS system Python, independently of the development interpreter."
    inputs.files("packaging/installation-recovery.py", "packaging/installation-lifecycle.py", "packaging/test-installation-recovery.py")
    commandLine("/usr/bin/python3", layout.projectDirectory.file("packaging/test-installation-recovery.py"))
}

val installationRecoveryTest = tasks.register<Exec>("installationRecoveryTest") {
    group = "verification"
    inputs.files("packaging/installation-recovery.py", "packaging/test-installation-recovery.py")
    commandLine("python3", layout.projectDirectory.file("packaging/test-installation-recovery.py"))
}

val installationLifecycleTest = tasks.register<Exec>("installationLifecycleTest") {
    group = "verification"
    description = "Proves explicit installation reset preserves configuration and rejects unresolved ownership."
    inputs.files("packaging/installation-lifecycle.py", "packaging/test-installation-lifecycle.py")
    commandLine("python3", layout.projectDirectory.file("packaging/test-installation-lifecycle.py"))
}

val priorInstallationCleanupTest = tasks.register<Exec>("priorInstallationCleanupTest") {
    group = "verification"
    description = "Proves exact user review and selected-installation protection for historical cleanup."
    inputs.files("packaging/prune-prior-installations.py", "packaging/installation-lifecycle.py",
        "packaging/installation-recovery.py", "packaging/test-prune-prior-installations.py")
    commandLine("python3", layout.projectDirectory.file("packaging/test-prune-prior-installations.py"))
}

val localInstallationTest = tasks.register<Exec>("localInstallationTest") {
    group = "verification"
    inputs.files("packaging/install-local.sh", "packaging/test-install-local.py")
    commandLine("python3", layout.projectDirectory.file("packaging/test-install-local.py"))
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

val productBuildGate = tasks.register("productBuildGate") {
    group = "verification"
    description = "Builds every module and verifies deterministic contracts, architecture and packaging without runtime qualification."
    dependsOn(
        "check",
        productGateVersionTest,
        isolatedAcceptanceEnvironmentTest,
        installationLifecycleTest,
        priorInstallationCleanupTest,
        installationRecoveryTest,
        installationSystemPythonTest,
        localInstallationTest,
        installerRemovalTest,
        installedProductTest,
        testCheckoutInstaller,
        installerEntrypointTest,
        "verifyKastArchitecture",
        ":app-server:verifyReleaseRuntimeAdmission",
    )
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}

subprojects.forEach { owner ->
    owner.plugins.withId("base") {
        productBuildGate.configure { dependsOn(owner.tasks.named("check")) }
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

val knowledgeParserClasspath = files(
    JsonContractScanRequest::class.java.protectionDomain.codeSource.location,
    jsonContractParser,
)

val knowledgeBaseTest = tasks.register<Exec>("knowledgeBaseTest") {
    group = "verification"
    description = "Exercises repository knowledge-base validation behavior."
    inputs.files(knowledgeParserClasspath)
    environment("KAST_KNOWLEDGE_PARSER_CLASSPATH", knowledgeParserClasspath.asPath)
    inputs.files(
        layout.projectDirectory.file(".github/scripts/code_kb.py"),
        layout.projectDirectory.file(".github/scripts/test_code_kb.py"),
    )
    commandLine("python3", layout.projectDirectory.file(".github/scripts/test_code_kb.py"))
}

val verifyKnowledgeBase = tasks.register<Exec>("verifyKnowledgeBase") {
    group = "verification"
    description = "Strictly validates the source-bound OKF repository knowledge base."
    inputs.files(knowledgeParserClasspath)
    environment("KAST_KNOWLEDGE_PARSER_CLASSPATH", knowledgeParserClasspath.asPath)
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

val pythonTestEnvironment = layout.buildDirectory.dir("python-tests/env")
val pythonTestExecutable = pythonTestEnvironment.map { it.file("bin/python3").asFile.absolutePath }
val preparePythonTestEnvironment = tasks.register<Exec>("preparePythonTestEnvironment") {
    group = "verification"
    description = "Provisions the pinned Python dependencies used by the routine test gate."
    inputs.file(layout.projectDirectory.file("experiments/host-observation/requirements-test.txt"))
    inputs.file(layout.projectDirectory.file("packaging/prepare-python-test-environment.py"))
    commandLine("python3", layout.projectDirectory.file("packaging/prepare-python-test-environment.py"))
}

val hostObservationTest = tasks.register<Exec>("hostObservationTest") {
    dependsOn(preparePythonTestEnvironment)
    group = "verification"
    description = "Checks the launch-free experimental host-observation controller."
    inputs.dir(layout.projectDirectory.dir("experiments/host-observation"))
    commandLine(pythonTestExecutable.get(), "-m", "unittest", "discover", "-s", "experiments/host-observation", "-p", "test_*.py")
}

tasks.named("check") { dependsOn(verifyConfigurationIngress, verifyKnowledgeBase, hostObservationTest) }
