import org.gradle.api.tasks.testing.Test

plugins {
    id("kast.runtime-serialization-app")
    id("kast.role.cli")
    id("org.graalvm.buildtools.native") version "1.1.14"
}

application {
    applicationName = "kast"
    mainClass = "io.github.amichne.kast.distribution.cli.KastManagementMainKt"
}

dependencies {
    implementation(project(":distribution:contract"))
    implementation(libs.clikt.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") { useJUnitPlatform() }

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
    kotlin.srcDir(layout.buildDirectory.dir("generated/sources/management-version/kotlin"))
}

tasks.named("compileKotlin") { dependsOn(generateVersion) }

graalvmNative {
    binaries {
        named("main") {
            imageName.set("kast")
            mainClass.set(application.mainClass)
            sharedLibrary.set(false)
            fallback.set(false)
            buildArgs.add("--no-fallback")
        }
    }
}
