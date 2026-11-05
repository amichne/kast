package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.kernel.Refinement
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CancellationException
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments

/** Missing legacy compiler fields are an unqualified model, never evidence that an override is unset. */
internal class LegacyJvmCompilerInputs
private constructor(
    val intellijPluginRoot: String?,
    val klibLibraries: String?,
    val fragmentIncrementalClasspath: Array<String>?,
) {
    companion object {
        fun capture(arguments: K2JVMCompilerArguments): SemanticCapture<LegacyJvmCompilerInputs> =
            try {
                val owner = K2JVMCompilerArguments::class.java
                val root = owner.getMethod("getIntellijPluginRoot")
                val libraries = owner.getMethod("getKlibLibraries")
                val fragments = owner.getMethod("getFragmentIncrementalClasspath")
                if (
                    root.returnType != String::class.java ||
                        libraries.returnType != String::class.java ||
                        fragments.returnType != Array<String>::class.java
                )
                    captureRejected(SemanticDependencyCaptureFailure.COMPILER_CONFIGURATION_UNAVAILABLE)
                else
                    Refinement.Refined(
                        LegacyJvmCompilerInputs(
                            String::class.java.cast(root.invoke(arguments)),
                            String::class.java.cast(libraries.invoke(arguments)),
                            Array<String>::class.java.cast(fragments.invoke(arguments)),
                        )
                    )
            } catch (failure: InvocationTargetException) {
                when (val cause = failure.cause) {
                    is ProcessCanceledException -> throw cause
                    is CancellationException -> throw cause
                    is Error -> throw cause
                    else -> captureRejected(SemanticDependencyCaptureFailure.COMPILER_CONFIGURATION_UNAVAILABLE)
                }
            } catch (_: ReflectiveOperationException) {
                captureRejected(SemanticDependencyCaptureFailure.COMPILER_CONFIGURATION_UNAVAILABLE)
            } catch (_: SecurityException) {
                captureRejected(SemanticDependencyCaptureFailure.COMPILER_CONFIGURATION_UNAVAILABLE)
            }
    }
}
