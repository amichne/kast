import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

plugins {
    id("kast.runtime-serialization-app")
    id("kast.role.cli")
}

val repositoryRoot = project.isolated.rootProject.projectDirectory

application {
    applicationName = "kast"
    mainClass = "io.github.amichne.kast.cli.KastCliMainKt"
}

dependencies {
    testImplementation(testFixtures(project(":app-server")))
    testImplementation(testFixtures(project(":protocol:wire")))
    testImplementation(project(":query:protocol"))
    testImplementation(testFixtures(project(":query:protocol")))
    implementation(libs.json.schema.validator)
    implementation(libs.mcp.kotlin.core)
    // Preserve the version previously selected by the broker's direct Ktor dependency.
    constraints {
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    }
    implementation(project(":app-server"))
    implementation(libs.clikt.core)
    implementation(libs.coroutines.core)
    runtimeOnly(libs.logback.classic)
    implementation(project(":distribution:contract"))
    implementation(project(":distribution:managed"))
    implementation(project(":kernel"))
    implementation(project(":protocol:contract"))
    implementation(project(":protocol:registry"))
    implementation(project(":protocol:wire"))
}

tasks.named<Test>("test") {
    inputs
        .files(File(repositoryRoot.asFile, "copilot/extension.mjs"), File(repositoryRoot.asFile, "pi/extension.ts"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(File(repositoryRoot.asFile, "pi/mcp-extension.ts")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src/test/js").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("src/main/js/query-session.mjs").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("src/main/js/query-delivery.mjs").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("src/main/js/query-delivery-contract.mjs").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("src/main/resources/query-delivery.schema.json").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .files(
            File(repositoryRoot.asFile, "experiments/host-observation/pi-fixtures/query-question.schema.json"),
            File(repositoryRoot.asFile, "experiments/host-observation/pi-fixtures/query-qualification.schema.json"),
            File(repositoryRoot.asFile, "experiments/host-observation/pi-fixtures/query-result.schema.json"),
        )
        .withPathSensitivity(PathSensitivity.RELATIVE)
    useJUnitPlatform {
        excludeTags("native")
        excludeTags("public-launcher")
    }
}

val nativeTest =
    tasks.register<Test>("nativeTest") {
        description = "Runs native UDS CLI boundary tests."
        group = "verification"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform {
            includeTags("native")
        }
    }

tasks.named("check") {
    dependsOn(nativeTest)
}

// Product integration consumes test outputs without introducing a production module dependency.
val publicLauncherTestOutputs = sourceSets.test.get()

configurations.consumable("publicLauncherTestClassesElements") {
    outgoing.artifacts(provider { publicLauncherTestOutputs.output.classesDirs.files.toList() }) {
        builtBy(publicLauncherTestOutputs.output.classesDirs)
    }
}

configurations.consumable("publicLauncherTestRuntimeElements") {
    outgoing.artifacts(provider { publicLauncherTestOutputs.runtimeClasspath.files.toList() }) {
        builtBy(publicLauncherTestOutputs.runtimeClasspath)
    }
}

val queryDeliveryContractTest =
    tasks.register<Exec>("queryDeliveryContractTest") {
        group = "verification"
        description = "Verifies the portable outer delivery contract independently of RPC effects."
        inputs.files("src/main/js/query-delivery-contract.mjs", "src/test/js/query-delivery-contract.test.mjs")
        inputs.file(File(repositoryRoot.asFile, "experiments/host-observation/pi_evaluation_delivery.mjs"))
        inputs.file(File(repositoryRoot.asFile, "experiments/host-observation/pi-fixtures/query-delivery-cases.json"))
        workingDir(repositoryRoot.asFile)
        commandLine("node", "--test", "cli/src/test/js/query-delivery-contract.test.mjs")
    }

tasks.named("check") {
    dependsOn(queryDeliveryContractTest)
}

val queryDeliverySchemaTest =
    tasks.register<Exec>("queryDeliverySchemaTest") {
        group = "verification"
        description = "Verifies the outer delivery authority with an independent JSON Schema validator."
        dependsOn(":preparePythonTestEnvironment")
        inputs.files("src/main/resources/query-delivery.schema.json")
        inputs.files(
            File(repositoryRoot.asFile, "packaging/query_delivery_contract.py"),
            File(repositoryRoot.asFile, "packaging/test-query-delivery-contract.py"),
        )
        inputs.file(File(repositoryRoot.asFile, "experiments/host-observation/pi-fixtures/query-delivery-cases.json"))
        workingDir(repositoryRoot.asFile)
        commandLine(
            repositoryRoot.file("build/python-tests/env/bin/python3").asFile,
            "packaging/test-query-delivery-contract.py",
        )
    }

tasks.named("check") {
    dependsOn(queryDeliverySchemaTest)
}

val codexIntegrationStartScripts =
    tasks.register<CreateStartScripts>("codexIntegrationStartScripts") {
        applicationName = "kast-codex"
        mainClass = "io.github.amichne.kast.cli.KastCodexMain"
        outputDir = layout.buildDirectory.dir("codex-integration-scripts").get().asFile
        classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
        dependsOn(tasks.named("jar"))
    }

val mcpStartScripts =
    tasks.register<CreateStartScripts>("mcpStartScripts") {
        applicationName = "kast-mcp"
        mainClass = "io.github.amichne.kast.cli.mcp.KastMcpMain"
        outputDir = layout.buildDirectory.dir("mcp-scripts").get().asFile
        classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
        dependsOn(tasks.named("jar"))
    }

val toolRpcStartScripts =
    tasks.register<CreateStartScripts>("toolRpcStartScripts") {
        applicationName = "kast-tool-rpc"
        mainClass = "io.github.amichne.kast.cli.rpc.KastToolRpcMain"
        outputDir = layout.buildDirectory.dir("tool-rpc-scripts").get().asFile
        classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
        dependsOn(tasks.named("jar"))
    }

val publicQueryStartScripts =
    tasks.register<CreateStartScripts>("publicQueryStartScripts") {
        applicationName = "kast-query"
        mainClass = "io.github.amichne.kast.cli.publicshell.KastPublicCliMain"
        outputDir = layout.buildDirectory.dir("public-query-scripts").get().asFile
        classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
        dependsOn(tasks.named("jar"))
    }

val daemonStartScripts =
    tasks.register<CreateStartScripts>("daemonStartScripts") {
        applicationName = "kast-daemon"
        optsEnvironmentVar = "KAST_OPTS"
        executableDir = "share/kast/libexec"
        mainClass = "io.github.amichne.kast.cli.KastDaemonMain"
        outputDir = layout.buildDirectory.dir("daemon-scripts").get().asFile
        classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
        dependsOn(tasks.named("jar"))
    }

val serviceStartScripts =
    tasks.register<CreateStartScripts>("serviceStartScripts") {
        applicationName = "kast-service"
        optsEnvironmentVar = "KAST_OPTS"
        executableDir = "share/kast/libexec"
        mainClass = "io.github.amichne.kast.cli.KastServiceMain"
        outputDir = layout.buildDirectory.dir("service-scripts").get().asFile
        classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
        dependsOn(tasks.named("jar"))
    }

distributions.main {
    contents {
        from(publicQueryStartScripts) {
            into("bin")
            exclude("*.bat")
            filePermissions { unix("755") }
        }
        from(serviceStartScripts) {
            into("share/kast/libexec")
            exclude("*.bat")
            filePermissions { unix("755") }
        }
        from(daemonStartScripts) {
            into("share/kast/libexec")
            exclude("*.bat")
            filePermissions { unix("755") }
        }
        from(codexIntegrationStartScripts) {
            into("bin")
            exclude("*.bat")
            filePermissions { unix("755") }
        }
        from(mcpStartScripts) {
            into("bin")
            exclude("*.bat")
            filePermissions { unix("755") }
        }
        from(toolRpcStartScripts) {
            into("bin")
            exclude("*.bat")
            filePermissions { unix("755") }
        }
        from(repositoryRoot.file("copilot/task-extension.mjs")) {
            into("share/kast/adapters/copilot")
            rename { "extension.mjs" }
        }
        from(repositoryRoot.file("pi/mcp-extension.ts")) {
            into("share/kast/adapters/pi")
            rename { "extension.ts" }
        }
    }
}

// Final pure projection assembles declarations with values from protocol and broker owners.
tasks.register<support.tasks.WriteJavaProcessOutputTask>("generateConfigurationCatalogue") {
    group = "build"
    description = "Generates the installed configuration schema from typed owner declarations."
    dependsOn(tasks.named("classes"))
    classpath.from(sourceSets.main.get().runtimeClasspath)
    mainClass.set("io.github.amichne.kast.cli.InstalledConfigurationSchema")
    outputFile.set(layout.buildDirectory.file("generated/configuration/configuration-schema.json"))
}

val projectedMintlifyCallableReference = layout.buildDirectory.file("generated/documentation/callables.openapi.json")
val publishedMintlifyCallableReference = repositoryRoot.file("docs/public/reference/callables.openapi.json")

val projectMintlifyCallableReference =
    tasks.register<support.tasks.WriteJavaProcessOutputTask>("projectMintlifyCallableReference") {
        group = "documentation"
        description = "Projects the Mintlify reference for every public installed callable."
        dependsOn(tasks.named("classes"))
        classpath.from(sourceSets.main.get().runtimeClasspath)
        mainClass.set("io.github.amichne.kast.cli.MintlifyCallableReference")
        outputFile.set(projectedMintlifyCallableReference)
    }

val generateMintlifyCallableReference =
    tasks.register<support.tasks.WriteJavaProcessOutputTask>("generateMintlifyCallableReference") {
        group = "documentation"
        description = "Updates the checked-in Mintlify callable reference."
        dependsOn(tasks.named("classes"))
        classpath.from(sourceSets.main.get().runtimeClasspath)
        mainClass.set("io.github.amichne.kast.cli.MintlifyCallableReference")
        outputFile.set(publishedMintlifyCallableReference)
    }

val verifyMintlifyCallableReference =
    tasks.register<Exec>("verifyMintlifyCallableReference") {
        group = "verification"
        description = "Rejects drift in the checked-in Mintlify callable reference; regenerate on failure."
        dependsOn(projectMintlifyCallableReference)
        mustRunAfter(generateMintlifyCallableReference)
        inputs.file(projectedMintlifyCallableReference)
        inputs.file(publishedMintlifyCallableReference)
        commandLine(
            "cmp",
            "-s",
            projectedMintlifyCallableReference.get().asFile.absolutePath,
            publishedMintlifyCallableReference.asFile.absolutePath,
        )
    }

tasks.named("check") {
    dependsOn(verifyMintlifyCallableReference)
}

// Pure schema projection at build time; App Server never launches Kast for qualification.
tasks.register<support.tasks.WriteJavaProcessOutputTask>("generateProviderCatalog") {
    group = "build"
    dependsOn(tasks.named("classes"))
    classpath.from(sourceSets.main.get().runtimeClasspath)
    mainClass.set("io.github.amichne.kast.cli.PackagedProviderCatalog")
    outputFile.set(layout.buildDirectory.file("generated/provider/provider-catalog.json"))
}

val queryPresentationTest =
    tasks.register<Exec>("queryPresentationTest") {
        group = "verification"
        description = "Verifies compact query text against pinned authoritative row and evidence oracles."
        inputs.files("src/main/js/query-delivery.mjs", "src/test/js/query-presentation.test.mjs")
        inputs.files("src/main/js/query-session.mjs", "src/test/js/query-session.test.mjs")
        inputs.file("src/test/js/tool-rpc-rejection.test.mjs")
        inputs.file(File(repositoryRoot.asFile, "copilot/extension.mjs"))
        inputs.file("src/test/resources/query-presentation-native.json")
        inputs.file("src/test/resources/query-compact-graph-pages.json")
        inputs.file("src/test/resources/query-compact-symbol-graph-pages.json")
        inputs.file(File(repositoryRoot.asFile, "experiments/host-observation/pi-fixtures/query-delivery-cases.json"))
        workingDir(repositoryRoot.asFile)
        commandLine(
            "node",
            "--experimental-vm-modules",
            "--test",
            "cli/src/test/js/query-presentation.test.mjs",
            "cli/src/test/js/query-session.test.mjs",
            "cli/src/test/js/tool-rpc-rejection.test.mjs",
        )
    }

tasks.named("check") {
    dependsOn(queryPresentationTest)
}

// The packaged host adapters share this Python task service over unchanged tool RPC.
val taskAccessTest =
    tasks.register<Exec>("taskAccessTest") {
        group = "verification"
        description = "Exercises task admission, MCP sessions, cancellation, and Copilot registration."
        inputs
            .files(
                fileTree(repositoryRoot.dir("packaging/task-access")) {
                    include("*.py", "*.mjs", "*.toml", "*.lock", ".python-version")
                }
            )
            .withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.dir(repositoryRoot.dir("agent-tools/skills/kast/scripts")).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs
            .file(repositoryRoot.file("agent-tools/references/query-schema.json"))
            .withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(repositoryRoot.file("copilot/task-extension.mjs")).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(repositoryRoot.file("pi/mcp-extension.ts")).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(repositoryRoot.file("agent-tools/.mcp.json")).withPathSensitivity(PathSensitivity.RELATIVE)
        workingDir(repositoryRoot.dir("packaging/task-access"))
        commandLine("uv", "run", "--locked", "pytest")
    }

tasks.named("check") { dependsOn(taskAccessTest) }

configurations.consumable("configurationCatalogueElements") {
    outgoing.artifact(
        tasks.named<support.tasks.WriteJavaProcessOutputTask>("generateConfigurationCatalogue").flatMap {
            it.outputFile
        }
    )
}

configurations.consumable("providerCatalogueElements") {
    outgoing.artifact(
        tasks.named<support.tasks.WriteJavaProcessOutputTask>("generateProviderCatalog").flatMap { it.outputFile }
    )
}

configurations.consumable("cliInstallationElements") {
    outgoing.artifact(tasks.named<Sync>("installDist").map { it.destinationDir })
}
