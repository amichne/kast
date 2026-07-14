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
        val compiler = ToolProvider.getSystemJavaCompiler()
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val source = JavaSource(
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

        val compiled = compiler.getTask(
            null,
            null,
            diagnostics,
            listOf("-classpath", System.getProperty("java.class.path")),
            null,
            listOf(source),
        ).call()

        assertFalse(compiled)
        assertTrue(
            diagnostics.diagnostics.any { diagnostic ->
                diagnostic.kind == javax.tools.Diagnostic.Kind.ERROR &&
                    diagnostic.getMessage(null).contains("within bounds")
            },
            diagnostics.diagnostics.joinToString(separator = "\n") { it.getMessage(null) },
        )
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
