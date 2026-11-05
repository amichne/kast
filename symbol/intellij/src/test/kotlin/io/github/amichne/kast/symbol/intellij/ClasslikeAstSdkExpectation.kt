package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue

/** Expected policy comes from the selected SDK, never from the observed product decision. */
internal enum class ClasslikeAstSdkExpectation(val optimized: Boolean, val archiveName: String) {
    AUDITED_262(true, "kotlinc.kotlin-compiler-common.jar"),
    UNQUALIFIED_263(false, "intellij.libraries.kotlinc.kotlin.compiler.common.jar");

    val traversalCounter: IntellijReadCounter
        get() =
            if (optimized) IntellijReadCounter.DECLARATION_AST_NODES_VISITED
            else IntellijReadCounter.DECLARATION_PSI_NODES_VISITED

    fun assertBinding(binding: IntellijClasslikeAstBinding) {
        if (optimized) assertTrue(binding is IntellijClasslikeAstBinding.Supported)
        else assertSame(IntellijClasslikeAstBinding.Unsupported, binding)
    }

    fun assertFallback(observation: RecordingDiscoveryObservation, files: Int = 1) {
        assertEquals(files, observation.counters[IntellijReadCounter.DECLARATION_AST_LEGACY_FALLBACKS])
        assertEquals(0, observation.counters[IntellijReadCounter.DECLARATION_AST_NODES_VISITED] ?: 0)
        assertEquals(0, observation.counters[IntellijReadCounter.DECLARATION_AST_PSI_REQUESTS] ?: 0)
        assertEquals(0, observation.counters[IntellijReadCounter.DECLARATION_AST_PSI_FALLBACKS] ?: 0)
        assertEquals(0, observation.counters[IntellijReadCounter.DECLARATION_AST_MAPPING_PSI_PROBES] ?: 0)
        assertEquals(0, observation.counters[IntellijReadCounter.DECLARATION_AST_BINDING_BYTES_READ] ?: 0)
        assertTrue((observation.counters[IntellijReadCounter.DECLARATION_PSI_NODES_VISITED] ?: 0) > 0)
    }

    companion object {
        val selected: ClasslikeAstSdkExpectation
            get() =
                when (System.getProperty("kast.astFixtureTarget")) {
                    "262" -> AUDITED_262
                    "263" -> UNQUALIFIED_263
                    else -> error("AST fixture requires an explicitly selected, supported SDK target")
                }

        val childArgument: String
            get() =
                "-Dkast.astFixtureTarget=" +
                    when (selected) {
                        AUDITED_262 -> "262"
                        UNQUALIFIED_263 -> "263"
                    }
    }
}
