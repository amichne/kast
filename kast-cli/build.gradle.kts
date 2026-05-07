import org.gradle.internal.execution.caching.CachingState.enabled

plugins {
    id("kast.standalone-serialization-app")
    alias(libs.plugins.graalvm.native)
}

val nativeConfigDir = layout.projectDirectory.dir(
    "src/main/resources/META-INF/native-image/io.github.amichne.kast/kast-cli",
)
val packagedSkillSourceDir = rootProject.layout.projectDirectory.dir(".agents/skills/kast")
val packagedCopilotAgentsSourceDir = rootProject.layout.projectDirectory.dir(".agents/agents")
val packagedCopilotHooksSourceDir = rootProject.layout.projectDirectory.dir(".github/hooks")
val packagedCopilotExtensionsSourceDir = rootProject.layout.projectDirectory.dir(".github/extensions")
val embeddedSkillFiles = listOf(
    "SKILL.md",
    "fixtures/maintenance/evals/evals.json",
    "fixtures/maintenance/evals/routing.json",
    "fixtures/maintenance/references/routing-improvement.md",
    "fixtures/maintenance/references/wrapper-openapi.yaml",
    "fixtures/maintenance/scripts/build-routing-corpus.py",
    "history/eval-baseline.json",
    "references/quickstart.md",
    "references/wrapper-openapi.yaml",
    "scripts/kast-session-start.sh",
    "scripts/resolve-kast.sh",
    "value-proof/README.md",
    "value-proof/bindings.schema.json",
    "value-proof/bindings/konditional.json",
    "value-proof/bindings/template.json",
    "value-proof/catalog.json",
    "value-proof/history/progression.json",
    "value-proof/scripts/generate_executive_summary.py",
    "value-proof/scripts/render_prompts.py",
    "value-proof/scripts/run_value_proof.py",
)
val embeddedCopilotAgentFiles = listOf(
    "kast-orchestrator.md",
)
val embeddedCopilotHookFiles = listOf(
    "hooks.json",
    "hook-state.sh",
    "session-start.sh",
    "record-paths.sh",
    "require-skills.sh",
    "skill-shadowing.json",
    "session-end.sh",
    "resolve-kast-cli-path.sh",
)
val embeddedCopilotExtensionFiles = listOf(
    "_shared/lib.mjs",
    "kast/extension.mjs",
    "kast/scripts/resolve-kast.sh",
    "kotlin-gradle-loop/extension.mjs",
    "kotlin-gradle-loop/scripts/gradle/run_gradle_hook.sh",
    "kotlin-gradle-loop/scripts/gradle/run_task.sh",
    "kotlin-gradle-loop/scripts/parse/jacoco_report.py",
    "kotlin-gradle-loop/scripts/parse/junit_results.py",
    "kotlin-gradle-loop/scripts/parse/kotlin_build_report.py",
    "kotlin-gradle-loop/scripts/state/get_state.py",
    "kotlin-gradle-loop/scripts/state/init_state.py",
    "kotlin-gradle-loop/scripts/state/record_action.py",
    "kotlin-gradle-loop/scripts/state/update_state.py",
)

application {
    mainClass = "io.github.amichne.kast.cli.tty.CliMainKt"
}

dependencies {
    api(project(":analysis-api"))
    implementation(project(":index-store"))
    implementation(libs.bundles.coroutines)
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
            mainClass.set("io.github.amichne.kast.cli.tty.CliMainKt")
            sharedLibrary.set(false)
            configurationFileDirectories.from(nativeConfigDir)
            buildArgs.addAll(
                "--no-fallback",
                "--initialize-at-build-time=kotlin.DeprecationLevel",
                "--enable-native-access=ALL-UNNAMED",
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

val syncPackagedCopilotExtensionResources by tasks.registering(Sync::class) {
    from(packagedCopilotAgentsSourceDir) {
        include(embeddedCopilotAgentFiles)
        into("packaged-copilot-extension/agents")
    }
    from(packagedCopilotHooksSourceDir) {
        include(embeddedCopilotHookFiles)
        into("packaged-copilot-extension/hooks")
    }
    from(packagedCopilotExtensionsSourceDir) {
        include(embeddedCopilotExtensionFiles)
        into("packaged-copilot-extension/extensions")
    }
    into(layout.buildDirectory.dir("generated/packaged-copilot-extension-resources"))
    includeEmptyDirs = false
}

tasks.named<ProcessResources>("processResources") {
    from(syncPackagedSkillResources)
    from(syncPackagedCopilotExtensionResources)
}

val shrinkRuntimeEnabled = providers.gradleProperty("kast.shrinkRuntime")
    .map(String::toBoolean)
    .getOrElse(false)

if (shrinkRuntimeEnabled) {
    tasks.named<Sync>("syncPortableDist") {
        dependsOn(":backend-standalone:shrinkRuntimeLibs")
        from(project(":backend-standalone").layout.buildDirectory.dir("shrunk-runtime-libs")) {
            into("runtime-libs")
        }
    }
} else {
    tasks.named<Sync>("syncPortableDist") {
        dependsOn(":backend-standalone:syncRuntimeLibs")
        from(project(":backend-standalone").layout.buildDirectory.dir("runtime-libs")) {
            into("runtime-libs")
        }
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
            .file(".agents/skills/kast/references/wrapper-openapi.yaml")
            .asFile.absolutePath,
    )
}
