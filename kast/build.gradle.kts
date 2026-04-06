import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip

plugins {
    id("kast.standalone-app")
}

application {
    mainClass = "io.github.amichne.kast.cli.jvm.JvmCliMainKt"
}

val nativeBinary = project(":kast-cli").layout.buildDirectory.file("native/nativeCompile/kast")

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
                "export KAST_LAUNCHER_PATH=\"${dollar}{script_dir}/${project.name}\"",
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

tasks.named<Sync>("syncPortableDist") {
    dependsOn(":kast-cli:nativeCompile")
    from(rootProject.layout.projectDirectory.file("smoke.sh"))
    from(nativeBinary) {
        into("bin")
    }
}

tasks.named<Zip>("portableDistZip").configure {
    eachFile {
        if (relativePath.pathString == "${project.name}/smoke.sh") {
            permissions { unix("755") }
        }
    }
}
