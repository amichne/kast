package io.github.amichne.kast.idea

import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.query.ReplacementPlanQuery
import io.github.amichne.kast.api.contract.result.ReplacementOccurrenceProvenance
import io.github.amichne.kast.api.contract.result.ExactReplacementOutboundReference
import io.github.amichne.kast.api.contract.result.ReplacementProofDimension
import io.github.amichne.kast.api.contract.result.ReplacementOutboundTarget
import io.github.amichne.kast.api.protocol.ReplacementProofIncompleteException
import io.github.amichne.kast.api.protocol.ReplacementProofLimitation
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong
import java.nio.file.Files
import java.nio.file.Path

@TestApplication
internal class ExactReplacementPlanningTest : KastIndexerBackendContractTestFixture() {
    private val replacementFileFixture: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture(
        "Replacement.kt",
        """
            package demo.replacement

            val replacementValue: String = "old"

            fun replacementFunction(value: String): String = value

            fun choose(value: String): String = value
            fun choose(value: Int): String = "int"

            @kotlin.jvm.JvmName("annotatedJvmFunction")
            fun annotatedFunction(value: String): String = value

            @get:kotlin.jvm.JvmName("annotatedJvmAccessor")
            val annotatedAccessor: String get() = "old"

            @kotlin.jvm.JvmField
            val annotatedField: String = "old"
        """.trimIndent(),
    )
    @Test
    fun `function replacement planning is compiler proven and does not mutate source`() = runBlocking {
        ensureProjectReady()
        val input = readAction {
            val sourceBefore = sampleFile.text
            val declarationOffset = sourceBefore.indexOf("greet")
            ReplacementInput(
                workspaceRoot = commonWorkspaceRoot(
                    sampleFile.virtualFile.path,
                    hierarchyFile.virtualFile.path,
                ),
                sourceBefore = sourceBefore,
                target = SymbolIdentity(
                    fqName = "demo.greet",
                    kind = SymbolKind.FUNCTION,
                    declarationFile = NormalizedPath.parse(sampleFile.virtualFile.path),
                    declarationStartOffset = NonNegativeInt(declarationOffset),
                ),
            )
        }

        val result = backend(workspaceRoot = input.workspaceRoot).planReplacement(
            ReplacementPlanQuery(
                target = input.target,
                proposedDeclaration = "fun greet(name: String): String = name",
            ),
        )

        assertEquals(input.sourceBefore, readAction { sampleFile.text })
        assertEquals(input.target, result.proof.target)
        assertEquals(result.proof.oldSignature, result.proof.proposedSignature)
        assertEquals(ReplacementProofDimension.entries, result.proof.evidence.dimensions)
        assertEquals(result.edit.filePath, result.proof.sourceRange.filePath)
        assertEquals(result.edit.startOffset, result.proof.sourceRange.startOffset)
        assertEquals(result.edit.endOffset, result.proof.sourceRange.endOffset)
        assertTrue(result.proof.outboundReferences.isNotEmpty())
        assertTrue(result.proof.outboundReferences.all { reference ->
            reference.provenance == ReplacementOccurrenceProvenance.COMPILER
        })
        assertEquals(listOf(result.edit.filePath), result.fileImages.map { image -> image.filePath.value })
        assertEquals(result.proof.fileHashes.single().hash, result.fileImages.single().preimage.sha256.value)
    }

    @Test
    fun `replacement planning rejects an unsaved document instead of omitting exact images`() = runBlocking {
        val input = replacementInput("replacementFunction", SymbolKind.FUNCTION)
        val file = replacementFileFixture.get()
        val filePath = Path.of(file.virtualFile.path)
        val before = Files.readAllBytes(filePath)
        val document = readAction {
            requireNotNull(FileDocumentManager.getInstance().getDocument(file.virtualFile))
        }
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(document.textLength, "\n// unsaved")
            }
        }

        val failure = replacementFailure(
            input,
            "fun replacementFunction(value: String): String = value",
        )

        assertLimitation(failure, ReplacementProofLimitation.SOURCE_IMAGE_UNPROVEN)
        assertArrayEquals(before, Files.readAllBytes(filePath))
    }

    @Test
    fun `property replacement planning fails closed outside function body authority`() = runBlocking {
        val input = replacementInput("replacementValue", SymbolKind.PROPERTY)

        val failure = replacementFailure(
            input,
            "val replacementValue: String = \"new\"",
        )

        assertEquals(input.sourceBefore, readAction { replacementFileFixture.get().text })
        assertLimitation(failure, ReplacementProofLimitation.UNSUPPORTED_TARGET_KIND)
    }

    @Test
    fun `function replacement retains exact source identity for an outbound overload`() = runBlocking {
        val input = replacementInput("replacementFunction", SymbolKind.FUNCTION)

        val result = backend(workspaceRoot = input.workspaceRoot).planReplacement(
            ReplacementPlanQuery(
                target = input.target,
                proposedDeclaration =
                    "fun replacementFunction(value: String): String = choose(value)",
            ),
        )

        val chooseTarget = result.proof.outboundReferences
            .map(ExactReplacementOutboundReference::resolvedTarget)
            .filterIsInstance<ReplacementOutboundTarget.Source>()
            .single { target -> target.symbol.fqName == "demo.replacement.choose" }
        assertEquals(SymbolKind.FUNCTION, chooseTarget.symbol.kind)
        assertEquals(input.target.declarationFile, chooseTarget.symbol.declarationFile)
    }

    @Test
    fun `replacement planning rejects compiler signature drift`() = runBlocking {
        val input = replacementInput("replacementFunction", SymbolKind.FUNCTION)

        val failure = replacementFailure(
            input,
            "fun replacementFunction(value: Int): String = \"changed\"",
        )

        assertLimitation(failure, ReplacementProofLimitation.SIGNATURE_DRIFT)
        assertLimitation(
            replacementFailure(
                input,
                "inline fun replacementFunction(value: String): String = value",
            ),
            ReplacementProofLimitation.SIGNATURE_DRIFT,
        )
    }

    @Test
    fun `replacement planning rejects zero and multiple replacement declarations`() = runBlocking {
        val input = replacementInput("replacementFunction", SymbolKind.FUNCTION)

        assertLimitation(
            replacementFailure(input, "42"),
            ReplacementProofLimitation.ZERO_REPLACEMENT_DECLARATIONS,
        )
        assertLimitation(
            replacementFailure(
                input,
                "fun replacementFunction(value: String): String = value\nval extra: String = value",
            ),
            ReplacementProofLimitation.MULTIPLE_REPLACEMENT_DECLARATIONS,
        )
    }

    @Test
    fun `replacement planning rejects unsupported target and proposed declaration kinds`() = runBlocking {
        ensureProjectReady()
        val unsupportedTarget = readAction {
            val offset = hierarchyFile.text.indexOf("Shape")
            ReplacementInput(
                workspaceRoot = commonWorkspaceRoot(
                    sampleFile.virtualFile.path,
                    hierarchyFile.virtualFile.path,
                ),
                sourceBefore = hierarchyFile.text,
                target = SymbolIdentity(
                    fqName = "demo.hierarchy.Shape",
                    kind = SymbolKind.INTERFACE,
                    declarationFile = NormalizedPath.parse(hierarchyFile.virtualFile.path),
                    declarationStartOffset = NonNegativeInt(offset),
                ),
            )
        }
        assertLimitation(
            replacementFailure(unsupportedTarget, "interface Shape"),
            ReplacementProofLimitation.UNSUPPORTED_TARGET_KIND,
        )

        val function = replacementInput("replacementFunction", SymbolKind.FUNCTION)
        assertLimitation(
            replacementFailure(function, "class replacementFunction"),
            ReplacementProofLimitation.UNSUPPORTED_REPLACEMENT_KIND,
        )
    }

    @Test
    fun `replacement planning rejects unresolved and ambiguous outbound calls`() = runBlocking {
        val input = replacementInput("replacementFunction", SymbolKind.FUNCTION)

        assertLimitation(
            replacementFailure(
                input,
                "fun replacementFunction(value: String): String = missing(value)",
            ),
            ReplacementProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
        )
        assertLimitation(
            replacementFailure(
                input,
                "fun replacementFunction(value: String): String = choose(null)",
            ),
            ReplacementProofLimitation.OVERLOAD_AMBIGUOUS,
        )
    }

    @Test
    fun `replacement planning rejects implicit invoke that cannot retain both compiler targets`() = runBlocking {
        val input = replacementInput("replacementFunction", SymbolKind.FUNCTION)

        assertLimitation(
            replacementFailure(
                input,
                "fun replacementFunction(value: String): String = ({ value })()",
            ),
            ReplacementProofLimitation.UNSUPPORTED_REFERENCE_KIND,
        )
    }

    @Test
    fun `replacement planning rejects every unmodeled implicit-call PSI form`() = runBlocking {
        val input = replacementInput("replacementFunction", SymbolKind.FUNCTION)
        val proposals = listOf(
            "fun replacementFunction(value: String): String { for (item in value) { item.code }; return value }",
            "fun replacementFunction(value: String): String { val values = arrayOf(value); values[0] = values[0]; return values[0] }",
            "fun replacementFunction(value: String): String { val (first, second) = Pair(value, value); return first + second }",
            "fun replacementFunction(value: String): String { val delegated by lazy { value }; return delegated }",
        )

        proposals.forEach { proposed ->
            assertLimitation(
                replacementFailure(input, proposed),
                ReplacementProofLimitation.UNSUPPORTED_REFERENCE_KIND,
            )
        }
    }

    @Test
    fun `replacement planning rejects annotated targets proposed declarations and accessors`() = runBlocking {
        listOf(
            Triple("annotatedFunction", SymbolKind.FUNCTION, "fun annotatedFunction(value: String): String = value"),
            Triple("annotatedAccessor", SymbolKind.PROPERTY, "val annotatedAccessor: String get() = \"new\""),
            Triple("annotatedField", SymbolKind.PROPERTY, "val annotatedField: String = \"new\""),
        ).forEach { (name, kind, proposed) ->
            assertLimitation(
                replacementFailure(replacementInput(name, kind), proposed),
                ReplacementProofLimitation.UNSUPPORTED_DECLARATION_ANNOTATION,
            )
        }

        val function = replacementInput("replacementFunction", SymbolKind.FUNCTION)
        assertLimitation(
            replacementFailure(
                function,
                "@kotlin.jvm.JvmName(\"proposedJvmFunction\") fun replacementFunction(value: String): String = value",
            ),
            ReplacementProofLimitation.UNSUPPORTED_DECLARATION_ANNOTATION,
        )
        val property = replacementInput("replacementValue", SymbolKind.PROPERTY)
        assertLimitation(
            replacementFailure(
                property,
                "@get:kotlin.jvm.JvmName(\"proposedJvmAccessor\") val replacementValue: String get() = \"new\"",
            ),
            ReplacementProofLimitation.UNSUPPORTED_DECLARATION_ANNOTATION,
        )
    }

    @Test
    fun `replacement planning rejects a changed semantic generation`() = runBlocking {
        val input = replacementInput("replacementFunction", SymbolKind.FUNCTION)
        val generation = AtomicLong()

        val failure = runCatching {
            backend(
                workspaceRoot = input.workspaceRoot,
                psiGeneration = generation::incrementAndGet,
            ).planReplacement(
                ReplacementPlanQuery(
                    target = input.target,
                    proposedDeclaration = "fun replacementFunction(value: String): String = value",
                ),
            )
        }.exceptionOrNull()

        assertLimitation(failure, ReplacementProofLimitation.GENERATION_CHANGED)
    }

    private suspend fun replacementInput(name: String, kind: SymbolKind): ReplacementInput {
        ensureProjectReady()
        val file = replacementFileFixture.get()
        waitUntilIndexesAreReady(project)
        return readAction {
            val sourceBefore = file.text
            val declarationOffset = sourceBefore.indexOf(name)
            ReplacementInput(
                workspaceRoot = commonWorkspaceRoot(
                    file.virtualFile.path,
                    hierarchyFile.virtualFile.path,
                ),
                sourceBefore = sourceBefore,
                target = SymbolIdentity(
                    fqName = "demo.replacement.$name",
                    kind = kind,
                    declarationFile = NormalizedPath.parse(file.virtualFile.path),
                    declarationStartOffset = NonNegativeInt(declarationOffset),
                ),
            )
        }
    }

    private suspend fun replacementFailure(
        input: ReplacementInput,
        proposedDeclaration: String,
    ): Throwable? = runCatching {
        backend(workspaceRoot = input.workspaceRoot).planReplacement(
            ReplacementPlanQuery(
                target = input.target,
                proposedDeclaration = proposedDeclaration,
            ),
        )
    }.exceptionOrNull()

    private fun assertLimitation(failure: Throwable?, limitation: ReplacementProofLimitation) {
        val replacementFailure = failure as? ReplacementProofIncompleteException
            ?: error("Expected replacement proof failure, got $failure")
        assertTrue(limitation in replacementFailure.evidence.limitations)
    }

    private data class ReplacementInput(
        val workspaceRoot: java.nio.file.Path,
        val sourceBefore: String,
        val target: SymbolIdentity,
    )
}
