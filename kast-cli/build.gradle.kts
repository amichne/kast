import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.JavaExec
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("kast.standalone-serialization-app")
    alias(libs.plugins.graalvm.native)
}

val nativeConfigDir = layout.projectDirectory.dir(
    "src/main/resources/META-INF/native-image/io.github.amichne.kast/kast-cli",
)
val packagedSkillSourceDir = rootProject.layout.projectDirectory.dir(".agents/skills/kast")
val embeddedSkillFiles = listOf(
    "SKILL.md",
    "agents/openai.yaml",
    "references/cloud-setup.md",
    "references/command-reference.md",
    "references/troubleshooting.md",
    "references/wrapper-openapi.yaml",
    "scripts/find-symbol-offset.py",
    "scripts/kast-callers.sh",
    "scripts/kast-common.sh",
    "scripts/kast-diagnostics.sh",
    "scripts/kast-plan-utils.py",
    "scripts/kast-rename.sh",
    "scripts/kast-references.sh",
    "scripts/kast-resolve.sh",
    "scripts/kast-scaffold.sh",
    "scripts/kast-workspace-files.sh",
    "scripts/kast-write-and-validate.sh",
    "scripts/resolve-kast.sh",
    "scripts/validate-wrapper-json.sh",
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
    metadataRepository {
        enabled.set(false)
    }
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

val syncPackagedSkillResources by tasks.registering(Sync::class) {
    from(packagedSkillSourceDir) {
        include(embeddedSkillFiles)
        into("packaged-skill")
    }
    into(layout.buildDirectory.dir("generated/packaged-skill-resources"))
    includeEmptyDirs = false
}

tasks.named<ProcessResources>("processResources") {
    from(syncPackagedSkillResources)
}

tasks.named<Test>("test") {
    dependsOn(tasks.named("writeWrapperScript"))
    dependsOn(":backend-standalone:syncRuntimeLibs")
    systemProperty(
        "kast.wrapper",
        layout.buildDirectory.file("scripts/kast-cli").get().asFile.absolutePath,
    )
    systemProperty(
        "kast.runtime-libs",
        project(":backend-standalone").layout.buildDirectory.dir("runtime-libs").get().asFile.absolutePath,
    )
    // The wrapper script spawns a daemon that needs backend-standalone jars on its classpath.
    // KAST_RUNTIME_LIBS propagates through the subprocess chain to ProcessLauncher.
    environment(
        "KAST_RUNTIME_LIBS",
        project(":backend-standalone").layout.buildDirectory.dir("runtime-libs").get().asFile.absolutePath,
    )
}

tasks.register<JavaExec>("generateWrapperOpenApiSchema") {
    group = "documentation"
    description = "Generate the packaged kast wrapper OpenAPI document from serialized model shapes."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.amichne.kast.cli.WrapperOpenApiDocumentKt")
    args(rootProject.layout.projectDirectory.file(".agents/skills/kast/references/wrapper-openapi.yaml").asFile.absolutePath)
}

val stageNativeRuntimeLibs by tasks.registering(Sync::class) {
    dependsOn(":backend-standalone:syncRuntimeLibs")
    from(project(":backend-standalone").layout.buildDirectory.dir("runtime-libs"))
    into(layout.buildDirectory.dir("native/nativeCompile/runtime-libs"))
}

tasks.named("nativeCompile").configure {
    finalizedBy(stageNativeRuntimeLibs)
}

tasks.named<Sync>("syncPortableDist") {
    // Replace kast-cli's own runtime-libs with backend-standalone's (needed for daemon classpath).
    from(project(":backend-standalone").tasks.named("syncRuntimeLibs")) {
        into("runtime-libs")
    }
    dependsOn(":backend-standalone:syncRuntimeLibs")
}
