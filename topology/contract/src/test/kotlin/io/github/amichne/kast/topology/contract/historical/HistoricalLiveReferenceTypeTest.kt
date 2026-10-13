package io.github.amichne.kast.topology.contract.historical

import io.github.amichne.kast.symbol.contract.SymbolSelector
import java.io.ByteArrayOutputStream
import java.net.URI
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.FileObject
import javax.tools.ForwardingJavaFileManager
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistoricalLiveReferenceTypeTest {
    @Test
    fun `compiler admits historical return and rejects live selector return`() {
        assertTrue(compile("HistoricalDeclarationReference").accepted)
        val rejected = compile("SymbolSelector")
        assertFalse(rejected.accepted)
        assertTrue(
            rejected.errors.any { it.contains("HistoricalDeclarationReference") && it.contains("SymbolSelector") }
        )
    }

    private fun compile(returnType: String): Compilation {
        val compiler = ToolProvider.getSystemJavaCompiler()
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val source =
            object : SimpleJavaFileObject(URI.create("string:///ReferenceUse.java"), JavaFileObject.Kind.SOURCE) {
                override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence =
                    """
                import io.github.amichne.kast.topology.contract.historical.HistoricalDeclarationReference;
                import io.github.amichne.kast.symbol.contract.SymbolSelector;
                class ReferenceUse {
                    static $returnType consume(HistoricalDeclarationReference historical) { return historical; }
                }
            """
                        .trimIndent()
            }
        val classpath =
            listOf(HistoricalDeclarationReference::class.java, SymbolSelector::class.java, Unit::class.java)
                .map { it.protectionDomain.codeSource.location.toURI().path }
                .distinct()
                .joinToString(java.io.File.pathSeparator)
        compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use { standard ->
            val memory =
                object : ForwardingJavaFileManager<JavaFileManager>(standard) {
                    override fun getJavaFileForOutput(
                        location: JavaFileManager.Location,
                        className: String,
                        kind: JavaFileObject.Kind,
                        sibling: FileObject?,
                    ): JavaFileObject =
                        object : SimpleJavaFileObject(URI.create("memory:///$className.class"), kind) {
                            override fun openOutputStream() = ByteArrayOutputStream()
                        }
                }
            val accepted =
                compiler
                    .getTask(
                        null,
                        memory,
                        diagnostics,
                        listOf("-proc:none", "-classpath", classpath),
                        null,
                        listOf(source),
                    )
                    .call()
            return Compilation(
                accepted,
                diagnostics.diagnostics.filter { it.kind == Diagnostic.Kind.ERROR }.map { it.getMessage(null) },
            )
        }
    }

    private data class Compilation(val accepted: Boolean, val errors: List<String>)
}
