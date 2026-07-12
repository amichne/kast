package io.github.amichne.kast.idea.proofloss

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.shared.proofloss.analysis.ProofLossAnalyzer
import io.github.amichne.kast.shared.proofloss.ir.ExtractionResult
import io.github.amichne.kast.shared.proofloss.ir.UnsupportedReason
import io.github.amichne.kast.shared.proofloss.model.BoundaryId
import io.github.amichne.kast.shared.proofloss.model.PredicateId
import io.github.amichne.kast.shared.proofloss.model.ProofTextParseResult
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
internal class IdeaProofIrExtractorTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()
        private val moduleFixture = projectFixture.moduleFixture("main")
        private val rootFixture = moduleFixture.sourceRootFixture()
        private val fileFixture: TestFixture<PsiFile> = rootFixture.psiFileFixture(
            "ProofFixture.kt", """
            package demo
            fun isZip(raw: String): Boolean = raw.length == 5
            fun parseZip(raw: String): String? = raw.takeIf { it.length == 5 }
            fun submit(raw: String) = Unit
            fun positive(raw: String) { if (isZip(raw)) { submit(raw) } }
            fun negative(raw: String) { if (!isZip(raw)) return; submit(raw) }
            fun refined(raw: String) { val zip = parseZip(raw) ?: return; submit(zip) }
            fun mutable(raw: String) { var zip = raw; if (isZip(zip)) submit(zip) }
            fun loop(raw: String) { while (isZip(raw)) submit(raw) }
            fun expression(raw: String) { if (isZip(raw)) submit(raw.trim()) }
            fun named(raw: String) { if (isZip(raw)) submit(raw = raw) }
            fun unresolved(raw: String) { missingCall(raw) }
            fun nested(raw: String) { listOf(raw).forEach { submit(raw) } }
            fun overloaded(raw: String): Boolean = true
            fun overloaded(raw: Int): Boolean = true
            fun overloadCalls(raw: String) { overloaded(raw); overloaded(1) }
            fun unmodeledGuard(raw: String) { if (raw.isNotEmpty()) submit(raw) }
            fun expressionBody(raw: String) = submit(raw)
        """.trimIndent()
        )
    }

    @Test
    fun `resolved identity and P1-P3 extraction are source backed`() = runBlocking {
        val project = projectFixture.get(); moduleFixture.get();
        val file = fileFixture.get()
        io.github.amichne.kast.idea.waitUntilIndexesAreReady(project)
        readAction {
            val functions = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java).associateBy { it.name }
            val calls = PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java)
            val declarationKey = requireNotNull(functions.getValue("isZip").toProofCallableKey())
            val callKey = requireNotNull(calls.first { it.calleeExpression?.text == "isZip" }.toProofCallableKey())
            assertEquals(declarationKey, callKey)

            val predicateId = PredicateId.id("zip")
            val boundaryId = BoundaryId.id("submit")
            val model = requireNotNull(
                resolveIdeaProofModel(
                    IdeaProofModelSpec(
                        predicates = listOf(
                            IdeaPredicateSpec(
                                predicateId,
                                functions.getValue("isZip"),
                                0,
                                listOf(
                                    IdeaMaterializerSpec(
                                        functions.getValue("parseZip"),
                                        false
                                    )
                                )
                            )
                        ),
                        boundaries = listOf(
                            IdeaBoundarySpec(
                                boundaryId,
                                functions.getValue("submit"),
                                listOf(0 to predicateId)
                            )
                        ),
                    ),
                )
            )
            val extractor = IdeaProofIrExtractor(model)
            fun extract(name: String) = extractor.extract(functions.getValue(name))
            val positive = assertInstanceOf(ExtractionResult.Supported::class.java, extract("positive"))
            assertEquals(1, ProofLossAnalyzer(model).analyze(positive.function).size)
            val negative = assertInstanceOf(ExtractionResult.Supported::class.java, extract("negative"))
            assertEquals(1, ProofLossAnalyzer(model).analyze(negative.function).size)
            val refined = assertInstanceOf(ExtractionResult.Supported::class.java, extract("refined"))
            assertTrue(ProofLossAnalyzer(model).analyze(refined.function).isEmpty())
            assertTrue(
                assertInstanceOf(
                    ExtractionResult.Unsupported::class.java,
                    extract("mutable")
                ).reasons.value.any { it is UnsupportedReason.MutableTrackedValue })
            assertTrue(
                assertInstanceOf(
                    ExtractionResult.Unsupported::class.java,
                    extract("loop")
                ).reasons.value.any { it is UnsupportedReason.Loop })
            assertTrue(
                assertInstanceOf(
                    ExtractionResult.Unsupported::class.java,
                    extract("expression")
                ).reasons.value.any { it is UnsupportedReason.NonDirectBoundaryArgument })
            assertTrue(
                assertInstanceOf(
                    ExtractionResult.Unsupported::class.java,
                    extract("named")
                ).reasons.value.any { it is UnsupportedReason.UnsupportedArgumentMapping })
            assertTrue(
                assertInstanceOf(
                    ExtractionResult.Unsupported::class.java,
                    extract("unresolved")
                ).reasons.value.any { it is UnsupportedReason.UnresolvedCall })
            assertTrue(
                assertInstanceOf(
                    ExtractionResult.Unsupported::class.java,
                    extract("nested")
                ).reasons.value.any { it is UnsupportedReason.NestedLambda })
            val overloadKeys = calls.filter { it.calleeExpression?.text == "overloaded" }
                .mapNotNull { it.toProofCallableKey() }
                .toSet()
            assertEquals(2, overloadKeys.size)
            assertTrue(
                assertInstanceOf(
                    ExtractionResult.Unsupported::class.java,
                    extract("unmodeledGuard")
                ).reasons.value.any { it is UnsupportedReason.UnsupportedControlFlow })
            assertTrue(
                assertInstanceOf(
                    ExtractionResult.Unsupported::class.java,
                    extract("expressionBody")
                ).reasons.value.any { it is UnsupportedReason.UnsupportedControlFlow })
        }
    }
}

private fun PredicateId.Companion.id(raw: String) = (parse(raw) as ProofTextParseResult.Valid).value
private fun BoundaryId.Companion.id(raw: String) = (parse(raw) as ProofTextParseResult.Valid).value
