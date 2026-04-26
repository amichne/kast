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
    "fixtures/maintenance/evals/evals.json",
    "fixtures/maintenance/evals/routing.json",
    "fixtures/maintenance/references/routing-improvement.md",
    "fixtures/maintenance/references/wrapper-openapi.yaml",
    "fixtures/maintenance/scripts/build-routing-corpus.py",
    "references/quickstart.md",
    "scripts/kast-session-start.sh",
    "scripts/resolve-kast.sh",
)

application {
    mainClass = "io.github.amichne.kast.cli.CliMainKt"
}

dependencies {
    api(project(":analysis-api"))
    implementation(libs.coroutines.core)
    implementation(libs.mordant)
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

tasks.named<Sync>("syncPortableDist") {
    dependsOn(":backend-standalone:syncRuntimeLibs")
    from(project(":backend-standalone").layout.buildDirectory.dir("runtime-libs")) {
        into("runtime-libs")
    }
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
}

tasks.register<JavaExec>("generateWrapperOpenApiSchema") {
    group = "documentation"
    description = "Generate the packaged kast wrapper OpenAPI document from serialized model shapes."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.amichne.kast.cli.WrapperOpenApiDocumentKt")
    args(
        rootProject.layout.projectDirectory
            .file(".agents/skills/kast/fixtures/maintenance/references/wrapper-openapi.yaml")
            .asFile.absolutePath,
    )
}
