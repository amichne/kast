import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test

plugins {
    id("kast.standalone-serialization-app")
    alias(libs.plugins.graalvm.native)
}

val nativeConfigDir = layout.projectDirectory.dir(
    "src/main/resources/META-INF/native-image/io.github.amichne.kast/kast-cli",
)

application {
    mainClass = "io.github.amichne.kast.cli.CliMainKt"
}

dependencies {
    api(project(":analysis-api"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("kast")
            mainClass.set("io.github.amichne.kast.cli.CliMainKt")
            sharedLibrary.set(false)
            configurationFileDirectories.from(nativeConfigDir)
            buildArgs.addAll(
                "--no-fallback",
                "--initialize-at-build-time=kotlin.DeprecationLevel",
                "-H:+ReportExceptionStackTraces",
            )
        }
    }
}

tasks.named<Test>("test") {
    dependsOn(":kast:writeWrapperScript")
    systemProperty(
        "kast.wrapper",
        project(":kast").layout.buildDirectory.file("scripts/kast").get().asFile.absolutePath,
    )
}

val stageNativeRuntimeLibs by tasks.registering(Sync::class) {
    dependsOn(":kast:syncRuntimeLibs")
    from(project(":kast").layout.buildDirectory.dir("runtime-libs"))
    into(layout.buildDirectory.dir("native/nativeCompile/runtime-libs"))
}

tasks.named("nativeCompile").configure {
    finalizedBy(stageNativeRuntimeLibs)
}
