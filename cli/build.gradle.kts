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
    testImplementation(libs.json.schema.validator)
    // Preserve the version previously selected by the broker's direct Ktor dependency.
    constraints {
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.11.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    }
    implementation(project(":app-server"))
    implementation(libs.clikt.core)
    implementation(libs.bundles.coroutines)
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

val nativeTest by tasks.registering(Test::class) {
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

val codexIntegrationStartScripts by tasks.registering(CreateStartScripts::class) {
    applicationName = "kast-codex"
    mainClass = "io.github.amichne.kast.cli.KastCodexMain"
    outputDir = layout.buildDirectory.dir("codex-integration-scripts").get().asFile
    classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
    dependsOn(tasks.named("jar"))
}

distributions.main {
    contents {
        from(codexIntegrationStartScripts) {
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

val projectedMintlifyCallableReference = layout.buildDirectory.file(
    "generated/documentation/callables.openapi.json",
)
val publishedMintlifyCallableReference = rootProject.layout.projectDirectory.file(
    "docs/public/reference/callables.openapi.json",
)

val projectMintlifyCallableReference by tasks.registering(
    support.tasks.WriteJavaProcessOutputTask::class,
) {
    group = "documentation"
    description = "Projects the Mintlify reference for every public installed callable."
    dependsOn(tasks.named("classes"))
    classpath.from(sourceSets.main.get().runtimeClasspath)
    mainClass.set("io.github.amichne.kast.cli.MintlifyCallableReference")
    outputFile.set(projectedMintlifyCallableReference)
}

val generateMintlifyCallableReference by tasks.registering(
    support.tasks.WriteJavaProcessOutputTask::class,
) {
    group = "documentation"
    description = "Updates the checked-in Mintlify callable reference."
    dependsOn(tasks.named("classes"))
    classpath.from(sourceSets.main.get().runtimeClasspath)
    mainClass.set("io.github.amichne.kast.cli.MintlifyCallableReference")
    outputFile.set(publishedMintlifyCallableReference)
}

val verifyMintlifyCallableReference by tasks.registering(Exec::class) {
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
