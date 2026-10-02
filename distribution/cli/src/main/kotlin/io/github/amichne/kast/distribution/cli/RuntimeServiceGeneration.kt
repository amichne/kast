package io.github.amichne.kast.distribution.cli

@JvmInline
internal value class RuntimeServiceGeneration private constructor(val value: String) {
    companion object {
        fun admit(raw: String): RuntimeGenerationAdmission =
            if (Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(raw))
                RuntimeGenerationAdmission.Admitted(RuntimeServiceGeneration(raw))
            else RuntimeGenerationAdmission.Rejected
    }
}

internal sealed interface RuntimeGenerationAdmission {
    data class Admitted(val generation: RuntimeServiceGeneration) : RuntimeGenerationAdmission

    data object Rejected : RuntimeGenerationAdmission
}
