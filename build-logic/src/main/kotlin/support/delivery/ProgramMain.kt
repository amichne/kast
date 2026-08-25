package support.delivery

import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    val program = KastVfsPassiveReusedIndexProgram.validated
    val programOutput = args.firstOrNull()?.let(Path::of)
    val requirementTraceOutput = args.getOrNull(1)?.let(Path::of)
    val programJson = canonicalJson(program.projection()) + "\n"
    if (programOutput == null) {
        print(programJson)
    } else {
        Files.createDirectories(programOutput.parent)
        Files.writeString(programOutput, programJson)
    }
    if (requirementTraceOutput != null) {
        Files.createDirectories(requirementTraceOutput.parent)
        Files.writeString(
            requirementTraceOutput,
            canonicalJson(program.requirementTraceProjection()) + "\n",
        )
    }
}
