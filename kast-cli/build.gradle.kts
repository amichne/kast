import org.gradle.internal.execution.caching.CachingState.enabled

plugins {
    id("kast.standalone-serialization-app")
    alias(libs.plugins.graalvm.native)
}

val nativeConfigDir = layout.projectDirectory.dir(
    "src/main/resources/META-INF/native-image/io.github.amichne.kast/kast-cli",
)
val packagedSkillSourceDir = rootProject.layout.projectDirectory.dir(".agents/skills/kast")
val packagedEvaluationSourceDir = rootProject.layout.projectDirectory.dir("evaluation")
val packagedCopilotAgentsSourceDir = rootProject.layout.projectDirectory.dir(".github/agents")
val packagedCopilotHooksSourceDir = rootProject.layout.projectDirectory.dir(".github/hooks")
val packagedCopilotExtensionsSourceDir = rootProject.layout.projectDirectory.dir(".github/extensions")
val embeddedSkillFiles = listOf(
    "SKILL.md",
    "fixtures/maintenance/references/routing-improvement.md",
    "fixtures/maintenance/scripts/build-routing-corpus.py",
    "references/quickstart.md",
    "references/commands.json",
    "references/routing-improvement.md",
    "scripts/build-routing-corpus.py",
    "scripts/kast-session-start.sh",
    "scripts/resolve-kast.sh",
)
val embeddedEvaluationFiles = listOf(
    "README.md",
    "bindings.schema.json",
    "bindings/konditional.json",
    "bindings/template.json",
    "catalog.json",
    "catalog.schema.json",
    "fixtures/.gitkeep",
    "grading.schema.json",
    "scripts/dispatch_runs.py",
    "scripts/finalize_grading.py",
    "scripts/generate_executive_summary.py",
    "scripts/parse_tool_calls.py",
    "scripts/render_prompts.py",
    "scripts/run_evaluation.py",
    "scripts/run_value_proof.py",
    "scripts/value_proof_aggregate.py",
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
    "export-session.py",
    "session-end.sh",
    "resolve-kast-cli-path.sh",
)
val embeddedCopilotExtensionFiles = listOf(
    "_shared/lib.mjs",
    "_shared/kast-tools.mjs",
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
    from(packagedEvaluationSourceDir) {
        include(embeddedEvaluationFiles)
        into("packaged-skill/evaluation")
    }
    into(layout.buildDirectory.dir("generated/packaged-skill-resources"))
    includeEmptyDirs = false
}

val generateFilteredSkillShadowing by tasks.registering {
    val sourceFile = packagedCopilotHooksSourceDir.file("skill-shadowing.json").asFile
    val outputFile = layout.buildDirectory.file(
        "generated/filtered-skill-shadowing/packaged-copilot-extension/hooks/skill-shadowing.json",
    )
    inputs.file(sourceFile)
    outputs.file(outputFile)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val parsed = groovy.json.JsonSlurper().parse(sourceFile) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val allSkills = parsed["skills"] as List<Map<String, Any>>
        val portableSkills = allSkills.filter { it.containsKey("shadowingExtensionId") }
        val filtered = mapOf("skills" to portableSkills)
        outputFile.get().asFile.let { out ->
            out.parentFile.mkdirs()
            out.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(filtered)))
        }
    }
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
    from(generateFilteredSkillShadowing.map { it.outputs.files }) {
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

tasks.register<JavaExec>("generateVersionedCommandSpec") {
    group = "documentation"
    description = "Generate the versioned command spec JSON from serialized model shapes."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.amichne.kast.cli.VersionedCommandSpecKt")
    val versionValue = providers.gradleProperty("VERSION").orElse("dev")
    val targetFile = rootProject.layout.projectDirectory
        .file(".agents/skills/kast/references/commands.json")
        .asFile.absolutePath
    args(versionValue.get(), targetFile)
}
