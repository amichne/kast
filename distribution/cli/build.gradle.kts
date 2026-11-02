import org.gradle.api.tasks.testing.Test

plugins {
    id("kast.runtime-serialization-app")
    id("kast.role.cli")
}

val repositoryRoot = project.isolated.rootProject.projectDirectory

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

// Recovery never executes an unqualified script from the installation being erased.
tasks.named<ProcessResources>("processResources") {
    from(repositoryRoot.file("install.sh")) {
        into("management")
        rename { "bootstrap-install.sh" }
    }
}
