import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("kast.standalone-app")
}

application {
    mainClass = "io.github.amichne.kast.cli.CliMainKt"
}

val helperSource: RegularFile = layout.projectDirectory.file("src/helper/kast_helper.c")
val helperBinary: Provider<RegularFile> = layout.buildDirectory.file("bin/kast-helper")
val packagedSkillSourceDir: Directory = rootProject.layout.projectDirectory.dir(".agents/skills/kast")
val packagedSkillInstallerSource: RegularFile = layout.projectDirectory.file("src/packaging/install-kast-skilled.sh")

dependencies {
    implementation(project(":analysis-api"))
    implementation(project(":analysis-server"))
    implementation(project(":backend-standalone"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.logback.classic)
}

val cleanHelperOutputs: TaskProvider<Delete> by tasks.registering(Delete::class) {
    group = "build"
    description = "Removes generated helper binaries before rebuilding the kast launcher."

    delete(layout.buildDirectory.dir("bin"))
}

val compileHelper: TaskProvider<Exec> by tasks.registering(Exec::class) {
    dependsOn(cleanHelperOutputs)
    inputs.file(helperSource)
    outputs.file(helperBinary)

    doFirst {
        helperBinary.get().asFile.parentFile.mkdirs()
    }

    commandLine(
        "cc",
        "-std=c11",
        "-O2",
        "-Wall",
        "-Wextra",
        "-pedantic",
        helperSource.asFile.absolutePath,
        "-o",
        helperBinary.get().asFile.absolutePath,
    )
}

tasks.named("writeWrapperScript").configure {
    dependsOn(compileHelper)

    doLast {
        val script = layout.buildDirectory.file("scripts/${project.name}").get().asFile
        val dollar = '$'
        script.writeText(
            listOf(
                "#!/usr/bin/env bash",
                "set -euo pipefail",
                "",
                "script_dir=\"${dollar}(cd -- \"${dollar}(dirname -- \"${dollar}{BASH_SOURCE[0]}\")\" >/dev/null 2>&1 && pwd)\"",
                "helper_candidates=(",
                "  \"${dollar}{KAST_HELPER:-}\"",
                "  \"${dollar}{script_dir}/bin/kast-helper\"",
                "  \"${dollar}{script_dir}/../bin/kast-helper\"",
                ")",
                "",
                "for candidate in \"${dollar}{helper_candidates[@]}\"; do",
                "  if [[ -n \"${dollar}{candidate}\" && -x \"${dollar}{candidate}\" ]]; then",
                "    exec \"${dollar}{candidate}\" \"${dollar}@\"",
                "  fi",
                "done",
                "",
                "runtime_candidates=(",
                "  \"${dollar}{KAST_RUNTIME_LIBS:-}\"",
                "  \"${dollar}{script_dir}/runtime-libs\"",
                "  \"${dollar}{script_dir}/../runtime-libs\"",
                ")",
                "",
                "java_bin=\"${dollar}{JAVA_HOME:+${dollar}{JAVA_HOME}/bin/java}\"",
                "if [[ -z \"${dollar}{java_bin}\" || ! -x \"${dollar}{java_bin}\" ]]; then",
                "  java_bin=\"java\"",
                "fi",
                "",
                "for runtime_dir in \"${dollar}{runtime_candidates[@]}\"; do",
                "  classpath_file=\"${dollar}{runtime_dir}/classpath.txt\"",
                "  if [[ -f \"${dollar}{classpath_file}\" ]]; then",
                "    classpath=\"\"",
                "    while IFS= read -r entry; do",
                "      [[ -n \"${dollar}{entry}\" ]] || continue",
                "      if [[ -n \"${dollar}{classpath}\" ]]; then",
                "        classpath=\"${dollar}{classpath}:\"",
                "      fi",
                "      classpath=\"${dollar}{classpath}${dollar}{runtime_dir}/${dollar}{entry}\"",
                "    done < \"${dollar}{classpath_file}\"",
                "    exec \"${dollar}{java_bin}\" -cp \"${dollar}{classpath}\" io.github.amichne.kast.cli.CliMainKt \"${dollar}@\"",
                "  fi",
                "done",
                "",
                "echo \"Could not locate kast helper or runtime-libs\" >&2",
                "exit 1",
            ).joinToString(System.lineSeparator()),
        )
        script.setExecutable(true)
    }
}

val syncPackagedSkill: TaskProvider<Sync> by tasks.registering(Sync::class) {
    from(packagedSkillSourceDir)
    into(layout.buildDirectory.dir("packaged-skill/share/skills/kast"))
}

val stagePackagedSkillInstaller: TaskProvider<Task> by tasks.registering {
    inputs.file(packagedSkillInstallerSource)
    outputs.file(layout.buildDirectory.file("packaged-skill/scripts/install-kast-skilled.sh"))

    doLast {
        val source = packagedSkillInstallerSource.asFile.toPath()
        val target = layout.buildDirectory.file("packaged-skill/scripts/install-kast-skilled.sh").get().asFile.toPath()
        Files.createDirectories(target.parent)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        target.toFile().setExecutable(true)
    }
}

tasks.named<Sync>("syncPortableDist") {
    dependsOn(compileHelper)
    dependsOn(syncPackagedSkill)
    dependsOn(stagePackagedSkillInstaller)
    from(helperBinary) {
        into("bin")
    }
    from(syncPackagedSkill) {
        into("share/skills/kast")
    }
    from(stagePackagedSkillInstaller) {
        into("scripts")
    }
}

tasks.named<Test>("test") {
    dependsOn(compileHelper)
    dependsOn(tasks.named("writeWrapperScript"))
    systemProperty(
        "kast.wrapper",
        layout.buildDirectory.file("scripts/${project.name}").get().asFile.absolutePath,
    )
}
