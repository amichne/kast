package io.github.amichne.kast.api.continuation

import java.net.URI
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContinuationOwnershipCompileSurfaceTest {
    @Test
    fun `compiler rejects one type in both owned state and projection roles`() {
        val result = compile(
            "compileprobe.IllegalContinuationAlias",
            """
                package compileprobe;

                import io.github.amichne.kast.api.continuation.ContinuationOwnedState;
                import io.github.amichne.kast.api.continuation.ServerHeldContinuationStore;

                final class IllegalContinuationAlias {
                    ServerHeldContinuationStore<Token, String, Same, Same> store;

                    static final class Token {}
                    static final class Same extends ContinuationOwnedState {}
                }
            """.trimIndent(),
        )

        assertFalse(result.compiled)
        assertTrue(
            result.diagnostics.any { diagnostic ->
                diagnostic.kind == javax.tools.Diagnostic.Kind.ERROR &&
                    diagnostic.getMessage(null).contains("within bounds")
            },
            result.messages,
        )
    }

    @Test
    fun `compiler rejects a generic public projection wrapper around owned state`() {
        val result = compile(
            "compileprobe.IllegalContinuationWrapper",
            """
                package compileprobe;

                import io.github.amichne.kast.api.continuation.ContinuationOwnedState;
                import io.github.amichne.kast.api.continuation.ContinuationProjection;
                import io.github.amichne.kast.api.continuation.ServerHeldContinuationStore;

                final class IllegalContinuationWrapper {
                    ServerHeldContinuationStore<
                        Token,
                        String,
                        Owned,
                        ContinuationProjection.Value<Owned>
                    > store;

                    static final class Token {}
                    static final class Owned extends ContinuationOwnedState {}
                }
            """.trimIndent(),
        )

        assertFalse(result.compiled, "A public generic projection wrapper can carry owned state")
        assertTrue(
            result.diagnostics.any { diagnostic ->
                diagnostic.kind == javax.tools.Diagnostic.Kind.ERROR &&
                    diagnostic.getMessage(null).contains("class Value") &&
                    diagnostic.getMessage(null).contains("ContinuationProjection")
            },
            result.messages,
        )
    }

    private fun compile(className: String, source: String): CompilationResult {
        val compiler = ToolProvider.getSystemJavaCompiler()
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val compiled = compiler.getTask(
            null,
            null,
            diagnostics,
            listOf("-classpath", System.getProperty("java.class.path")),
            null,
            listOf(JavaSource(className, source)),
        ).call()
        return CompilationResult(compiled, diagnostics.diagnostics)
    }

    private data class CompilationResult(
        val compiled: Boolean,
        val diagnostics: List<javax.tools.Diagnostic<out JavaFileObject>>,
    ) {
        val messages: String = diagnostics.joinToString(separator = "\n") { it.getMessage(null) }
    }

    private class JavaSource(
        className: String,
        private val source: String,
    ) : SimpleJavaFileObject(
        URI.create("string:///${className.replace('.', '/')}.java"),
        JavaFileObject.Kind.SOURCE,
    ) {
        override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
    }
}
