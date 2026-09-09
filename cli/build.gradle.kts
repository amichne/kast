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
