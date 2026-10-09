package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits

enum class IntellijReadUnexpectedKind {
    RUNTIME,
    LINKAGE,
}

data class IntellijReadUnexpectedFailure
private constructor(
    val stage: IntellijReadStage,
    val kind: IntellijReadUnexpectedKind,
    val exceptionType: String,
    val adapterFrames: List<String>,
) {
    companion object {
        fun capture(
            stage: IntellijReadStage,
            failure: Throwable,
            limits: ReadLimits = ReadLimits.Default,
        ): IntellijReadUnexpectedFailure =
            IntellijReadUnexpectedFailure(
                stage,
                if (failure is LinkageError) IntellijReadUnexpectedKind.LINKAGE else IntellijReadUnexpectedKind.RUNTIME,
                failure.javaClass.name.take(limits[ReadLimitParameter.DIAGNOSTIC_TEXT_CHARACTERS].value),
                failure.stackTrace
                    .asSequence()
                    .filter { it.className.startsWith("io.github.amichne.kast.") }
                    .take(limits[ReadLimitParameter.DIAGNOSTIC_FRAMES].value)
                    .map {
                        "${it.className}.${it.methodName}:${it.lineNumber}"
                            .take(limits[ReadLimitParameter.DIAGNOSTIC_TEXT_CHARACTERS].value)
                    }
                    .toList(),
            )
    }
}
