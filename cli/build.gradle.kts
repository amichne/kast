import org.gradle.api.tasks.testing.Test

plugins {
    id("kast.runtime-serialization-app")
    id("kast.role.cli")
}

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
    useJUnitPlatform {
        excludeTags("native")
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
val publishedMintlifyCallableReference =
    rootProject.layout.projectDirectory.file("docs/public/reference/callables.openapi.json")

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
