package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.Refinement
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class LegacyJvmCompilerInputsTest {
    @Test
    fun `actual SDK retains legacy field values or a finite unavailable model without inventing unset fields`() {
        val arguments = K2JVMCompilerArguments()
        when (checkNotNull(System.getProperty("kast.compilerModelTarget"))) {
            "262" -> {
                val empty =
                    assertInstanceOf<Refinement.Refined<LegacyJvmCompilerInputs>>(
                            LegacyJvmCompilerInputs.capture(arguments)
                        )
                        .value
                assertNull(empty.intellijPluginRoot)
                assertNull(empty.klibLibraries)
                assertArrayEquals(emptyArray<String>(), empty.fragmentIncrementalClasspath)
                val owner = K2JVMCompilerArguments::class.java
                owner.getMethod("setIntellijPluginRoot", String::class.java).invoke(arguments, "/external/compiler")
                owner.getMethod("setKlibLibraries", String::class.java).invoke(arguments, "/external/library.klib")
                owner
                    .getMethod("setFragmentIncrementalClasspath", Array<String>::class.java)
                    .invoke(arguments, arrayOf("/external/fragment.jar"))
                val retained =
                    assertInstanceOf<Refinement.Refined<LegacyJvmCompilerInputs>>(
                            LegacyJvmCompilerInputs.capture(arguments)
                        )
                        .value
                assertEquals("/external/compiler", retained.intellijPluginRoot)
                assertEquals("/external/library.klib", retained.klibLibraries)
                assertArrayEquals(arrayOf("/external/fragment.jar"), retained.fragmentIncrementalClasspath)
                assertEquals(
                    Refinement.Rejected(SemanticDependencyCaptureFailure.COMPILER_DISTRIBUTION_UNMODELED),
                    admitSemanticCompilerDistribution(arguments.kotlinHome, retained.intellijPluginRoot),
                )
            }
            "263" ->
                assertEquals(
                    Refinement.Rejected(SemanticDependencyCaptureFailure.COMPILER_CONFIGURATION_UNAVAILABLE),
                    LegacyJvmCompilerInputs.capture(arguments),
                )
            else -> error("unknown target cannot establish compiler-input qualification")
        }
    }
}
