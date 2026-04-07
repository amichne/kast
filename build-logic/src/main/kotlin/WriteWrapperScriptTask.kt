import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.writeText

abstract class WriteWrapperScriptTask : DefaultTask() {
    @get:Input
    abstract val jarFileName: Property<String>

    @get:Input
    @get:Optional
    abstract val scriptContent: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun writeScript() {
        val script = outputFile.get().asFile
        val content = scriptContent.orNull ?: defaultJarLauncherScript(jarFileName.get())
        writeTextAtomically(
            script.toPath(),
            content,
        )
    }
}

private fun defaultJarLauncherScript(jarFileName: String): String =
    $$"""
    |#!/usr/bin/env bash
    |set -euo pipefail
    |
    |script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
    |jar_name="$$jarFileName"
    |candidate_paths=(
    |  "${KAST_APP_JAR:-}"
    |  "${script_dir}/libs/${jar_name}"
    |  "${script_dir}/../libs/${jar_name}"
    |)
    |
    |jar_path=""
    |for candidate in "${candidate_paths[@]}"; do
    |  if [[ -n "${candidate}" && -f "${candidate}" ]]; then
    |    jar_path="${candidate}"
    |    break
    |  fi
    |done
    |
    |if [[ -z "${jar_path}" ]]; then
    |  {
    |    echo "kast: unable to locate ${jar_name}"
    |    echo "searched:"
    |    for candidate in "${candidate_paths[@]}"; do
    |      if [[ -n "${candidate}" ]]; then
    |        echo "  - ${candidate}"
    |      fi
    |    done
    |    echo "hint: keep the wrapper next to a libs/ directory or inside build/scripts with ../libs/"
    |    echo "hint: set KAST_APP_JAR=/absolute/path/to/${jar_name} to override autodiscovery"
    |  } >&2
    |  exit 1
    |fi
    |
    |exec "${JAVA:-java}" ${JAVA_OPTS:-} -jar "${jar_path}" "$@"
    """.trimMargin()

private fun writeTextAtomically(
    path: Path,
    content: String,
) {
    val directory = path.parent ?: error("Wrapper script path must have a parent directory: $path")
    Files.createDirectories(directory)
    val tempFile = Files.createTempFile(directory, ".${path.fileName}", ".tmp")
    try {
        tempFile.writeText(content)
        tempFile.toFile().setReadable(true, false)
        tempFile.toFile().setWritable(true, true)
        tempFile.toFile().setExecutable(true, false)
        Files.move(
            tempFile,
            path,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } finally {
        Files.deleteIfExists(tempFile)
    }
}
