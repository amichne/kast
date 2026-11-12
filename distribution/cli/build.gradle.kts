import org.gradle.api.tasks.testing.Test

plugins {
    `java-test-fixtures`
    id("kast.runtime-serialization-app")
    id("kast.role.cli")
}

group = "${providers.gradleProperty("GROUP").get()}.distribution"

base {
    archivesName.set("distribution-cli")
}

application {
    applicationName = "kast"
    mainClass = "io.github.amichne.kast.distribution.cli.KastManagementMainKt"
}

dependencies {
    implementation(project(":distribution:output"))
    testImplementation(libs.jtoon)
    implementation(project(":distribution:contract"))
    implementation(project(":distribution:managed"))
    implementation(project(":evidence:sqlite"))
    implementation(libs.clikt.core)
    testImplementation(libs.json.schema.validator)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testFixturesApi(project(":kernel"))
    testFixturesApi(project(":distribution:managed"))
    testFixturesImplementation(libs.serialization.json)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val generateVersion =
    tasks.register<Copy>("generateManagementVersion") {
        from("src/main/templates/ManagementVersion.kt.in")
        into(
            layout.buildDirectory.dir(
                "generated/sources/management-version/kotlin/io/github/amichne/kast/distribution/cli"
            )
        )
        val managementVersion = project.version.toString()
        inputs.property("managementVersion", managementVersion)
        expand("version" to managementVersion)
        rename { "ManagementVersion.kt" }
    }

kotlin.sourceSets.main {
    kotlin.srcDir(
        files(layout.buildDirectory.dir("generated/sources/management-version/kotlin")).builtBy(generateVersion)
    )
}

tasks.named("compileKotlin") { dependsOn(generateVersion) }
