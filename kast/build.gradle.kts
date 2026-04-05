import org.gradle.api.tasks.Sync
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("kast.standalone-app")
}

application {
    mainClass = "io.github.amichne.kast.cli.jvm.JvmCliMainKt"
}

val nativeBinary = project(":kast-cli").layout.buildDirectory.file("native/nativeCompile/kast")
val packagedSkillSourceDir: Directory = rootProject.layout.projectDirectory.dir(".agents/skills/kast")
val packagedSkillInstallerSource: RegularFile = layout.projectDirectory.file("src/packaging/install-kast-skilled.sh")

dependencies {
    implementation(project(":kast-cli"))
    implementation(project(":backend-standalone"))
}

tasks.named("writeWrapperScript").configure {
    doLast {
        val script = layout.buildDirectory.file("scripts/${project.name}").get().asFile
        val dollar = '$'
        script.writeText(
            listOf(
                "#!/usr/bin/env bash",
                "set -euo pipefail",
                "",
                "script_dir=\"${dollar}(cd -- \"${dollar}(dirname -- \"${dollar}{BASH_SOURCE[0]}\")\" >/dev/null 2>&1 && pwd)\"",
                "runtime_candidates=(",
                "  \"${dollar}{KAST_RUNTIME_LIBS:-}\"",
                "  \"${dollar}{script_dir}/runtime-libs\"",
                "  \"${dollar}{script_dir}/../runtime-libs\"",
                ")",
                "",
                "native_candidates=(",
                "  \"${dollar}{KAST_NATIVE:-}\"",
                "  \"${dollar}{script_dir}/bin/kast\"",
                "  \"${dollar}{script_dir}/../bin/kast\"",
                ")",
                "",
                "resolved_runtime_libs=\"\"",
                "for runtime_dir in \"${dollar}{runtime_candidates[@]}\"; do",
                "  classpath_file=\"${dollar}{runtime_dir}/classpath.txt\"",
                "  if [[ -n \"${dollar}{runtime_dir}\" && -f \"${dollar}{classpath_file}\" ]]; then",
                "    resolved_runtime_libs=\"${dollar}{runtime_dir}\"",
                "    break",
                "  fi",
                "done",
                "",
                "for candidate in \"${dollar}{native_candidates[@]}\"; do",
                "  if [[ -n \"${dollar}{candidate}\" && -x \"${dollar}{candidate}\" ]]; then",
                "    if [[ -n \"${dollar}{resolved_runtime_libs}\" && -z \"${dollar}{KAST_RUNTIME_LIBS:-}\" ]]; then",
                "      export KAST_RUNTIME_LIBS=\"${dollar}{resolved_runtime_libs}\"",
                "    fi",
                "    exec \"${dollar}{candidate}\" \"${dollar}@\"",
                "  fi",
                "done",
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
                "    exec \"${dollar}{java_bin}\" -cp \"${dollar}{classpath}\" io.github.amichne.kast.cli.jvm.JvmCliMainKt \"${dollar}@\"",
                "  fi",
                "done",
                "",
                "echo \"Could not locate kast native binary or runtime-libs\" >&2",
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
    dependsOn(":kast-cli:nativeCompile")
    dependsOn(syncPackagedSkill)
    dependsOn(stagePackagedSkillInstaller)
    from(nativeBinary) {
        into("bin")
    }
    from(syncPackagedSkill) {
        into("share/skills/kast")
    }
    from(stagePackagedSkillInstaller) {
        into("scripts")
    }
}
