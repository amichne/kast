import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

plugins {
    id("kast.runtime-serialization-app")
    id("kast.role.cli")
}

val repositoryRoot = project.isolated.rootProject.projectDirectory
val jqEngineMetadata =
    tasks.register<WriteProperties>("jqEngineMetadata") {
        destinationFile.set(layout.buildDirectory.file("generated/jq/jq-engine.properties"))
        property("version", libs.versions.jackson.jq.get())
    }

tasks.named<ProcessResources>("processResources") {
    from(jqEngineMetadata)
}

val mcpResultSchemaDirectory = layout.buildDirectory.dir("generated/mcp-result-schemas")
val mcpResultSchemas =
    tasks.register<support.tasks.WriteJavaProcessOutputTask>("generateMcpResultSchemas") {
        group = "build"
        // The generator consumes compiled owners and ordinary resources, never its own generated output.
        classpath.from(sourceSets.main.get().output.classesDirs)
        classpath.from(tasks.named<ProcessResources>("processResources").map { it.destinationDir })
        classpath.from(configurations.runtimeClasspath)
        mainClass.set("io.github.amichne.kast.cli.mcp.McpResultSchemas")
        outputFile.set(layout.buildDirectory.file("generated/mcp-result-schema-archive/mcp-result-schemas.zip"))
    }
val mcpResultSchemaResources =
    tasks.register<Sync>("unpackMcpResultSchemas") {
        from(zipTree(mcpResultSchemas.flatMap { it.outputFile }))
        into(mcpResultSchemaDirectory)
    }

sourceSets.main {
    output.dir(mapOf("builtBy" to mcpResultSchemaResources), mcpResultSchemaDirectory)
}

application {
    applicationName = "kast"
    mainClass = "io.github.amichne.kast.cli.KastCliMainKt"
}

dependencies {
    implementation(platform(libs.jackson2.bom))
    implementation(libs.jackson.jq)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
    implementation(project(":distribution:cli"))
    implementation(project(":distribution:output"))
    testImplementation(libs.jtoon)
    testImplementation(libs.ktoon)
    testImplementation(testFixtures(project(":app-server")))
    testImplementation(testFixtures(project(":distribution:managed")))
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
    // PublicSkillCommandDriftTest parses every command that the bundled guidance teaches.
    inputs
        .files(
            File(repositoryRoot.asFile, "agent-tools/skills/kast/SKILL.md"),
            File(repositoryRoot.asFile, "agent-tools/skills/kast/public-cli.md"),
        )
        .withPathSensitivity(PathSensitivity.RELATIVE)
    useJUnitPlatform {
        excludeTags("native")
        excludeTags("public-launcher")
    }
}

val udsTest =
    tasks.register<Test>("udsTest") {
        description = "Runs native UDS CLI boundary tests on the JVM."
        group = "verification"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform {
            includeTags("native")
        }
    }

tasks.named("check") {
    dependsOn(udsTest)
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

// The Control JVM entry's libraries. The catalog lists them in this order, relative to the installation.
val controlLibraries =
    tasks.named<Jar>("jar").flatMap { jar ->
        jar.archiveFile.zip(configurations.runtimeClasspath.flatMap { it.elements }) { entry, runtime ->
            listOf(entry.asFile) + runtime.map { it.asFile }
        }
    }

// Control executable metadata, projected by the public shell owner. Never starts a coordinator.
val generateFrontendCatalog =
    tasks.register<support.tasks.WriteJavaProcessOutputTask>("generateFrontendCatalog") {
        group = "build"
        dependsOn(tasks.named("classes"))
        classpath.from(sourceSets.main.get().runtimeClasspath)
        mainClass.set("io.github.amichne.kast.cli.publicshell.PublicFrontendCatalog")
        arguments.set(controlLibraries.map { files -> files.map { "lib/${it.name}" } })
        outputFile.set(layout.buildDirectory.file("generated/frontend/frontend.json"))
    }

val assembleControlLibraries =
    tasks.register<Sync>("assembleControlLibraries") {
        group = "build"
        description = "Assembles the Control JVM entry's libraries for the installation's lib directory."
        into(layout.buildDirectory.dir("control-libraries"))
        from(tasks.named("jar"))
        from(configurations.runtimeClasspath)
    }

configurations.consumable("controlLibraryElements") {
    outgoing.artifact(assembleControlLibraries.map { it.destinationDir })
}

configurations.consumable("configurationCatalogueElements") {
    outgoing.artifact(
        tasks.named<support.tasks.WriteJavaProcessOutputTask>("generateConfigurationCatalogue").flatMap {
            it.outputFile
        }
    )
}

configurations.consumable("frontendCatalogElements") {
    outgoing.artifact(generateFrontendCatalog.flatMap { it.outputFile })
}

configurations.consumable("providerCatalogueElements") {
    outgoing.artifact(
        tasks.named<support.tasks.WriteJavaProcessOutputTask>("generateProviderCatalog").flatMap { it.outputFile }
    )
}

configurations.consumable("cliInstallationElements") {
    outgoing.artifact(tasks.named<Sync>("installDist").map { it.destinationDir })
}

val assembleControlLayout =
    tasks.register<Sync>("assembleControlLayout") {
        into(layout.buildDirectory.dir("control-layout"))
        from("src/main/control-launchers") { filePermissions { unix("755") } }
        from("jvm-control-v1") { into("share/kast") }
    }

configurations.consumable("controlLayoutElements") {
    outgoing.artifact(assembleControlLayout.map { it.destinationDir })
}
