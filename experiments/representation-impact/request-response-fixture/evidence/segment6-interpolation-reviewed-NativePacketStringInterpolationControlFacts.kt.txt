@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueField
import io.github.amichne.kast.relation.contract.ValueInterpolationScalarKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertSame
import junit.framework.TestCase.assertTrue
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** Exact PSI containment and actual K2 types establish the expected template contribution independently. */
internal class NativePacketStringInterpolationControlFacts(
    private val project: Project,
    private val base: VirtualFile,
) {
    fun scalar(fixture: NativePacketValueFixture, control: InterpolationScalarControl): InterpolationControlFacts =
        inspect(fixture = fixture, method = control.method, text = "value") { host, expression ->
            proveParameter(host, expression)
            assertClass(expression, control.qualifiedType, false)
        }

    fun unsupported(fixture: NativePacketValueFixture, method: String): InterpolationControlFacts =
        inspect(
            fixture = fixture,
            method = method,
            text = if (method == "flexibleValue") FLEXIBLE_EXPRESSION else "value",
        ) { host, expression ->
            when (method) {
                "nullableValue" -> {
                    proveParameter(host, expression)
                    assertClass(expression, "kotlin.String", true)
                }
                "customValue" -> proveCustom(host, expression)
                "genericValue" -> {
                    proveParameter(host, expression)
                    val parameter = (host.symbol as KaNamedFunctionSymbol).typeParameters.single()
                    assertEquals(parameter, (expression.expressionType as KaTypeParameterType).symbol)
                }
                "flexibleValue" -> proveFlexible(expression)
                else -> error("Unknown source control: $method")
            }
        }

    fun sibling(fixture: NativePacketValueFixture): InterpolationControlFacts =
        inspect(fixture = fixture, method = "siblings", text = "value") { host, expression ->
            proveParameter(host, expression)
            assertClass(expression, "kotlin.String", false)
        }

    fun nested(fixture: NativePacketValueFixture): InterpolationControlFacts =
        inspect(fixture = fixture, method = "nestedPath", text = "value") { host, expression ->
            proveParameter(host, expression)
            assertClass(expression, "kotlin.String", false)
        }

    fun lengthField(fixture: NativePacketValueFixture): ValueField =
        ReadAction.compute<ValueField, RuntimeException> {
            val host = host("nestedPath")
            val reference =
                PsiTreeUtil.collectElementsOfType(host, KtNameReferenceExpression::class.java).single { reference ->
                    reference.getReferencedName() == "length"
                }
            val declaration =
                IntellijReadObservation.None.observedAnalyze(reference) {
                    val property = checkNotNull(reference.resolveCall()).signature.symbol as KaKotlinPropertySymbol
                    assertEquals("length", property.name.asString())
                    val owner = property.containingDeclaration as KaNamedClassSymbol
                    assertEquals("kotlin.String", checkNotNull(owner.classId).asSingleFqName().asString())
                    checkNotNull(property.psi) as PsiNamedElement
                }
            val projected =
                IntellijK2RelationProjection(project, fixture.lease.workspaceRoot).project(declaration)
                    as IntellijRelationDeclarationProjection.Projected
            val admitted =
                RelationEndpoint.resolve(
                        lease = fixture.lease,
                        scope =
                            SymbolSearchScope.Workspace(
                                SymbolSourceKindPolicy.PRODUCTION_ONLY,
                                SymbolGeneratedSourcePolicy.EXCLUDE,
                                SymbolLibraryPolicy.INCLUDE,
                            ),
                        evidence = projected.evidence,
                    )
                    .nativeRefined()
            ValueField.fromCompiler(admitted).nativeRefined()
        }

    fun constant(fixture: NativePacketValueFixture): Pair<PacketExpression, ExactDeclarationTextRange> =
        ReadAction.compute<Pair<PacketExpression, ExactDeclarationTextRange>, RuntimeException> {
            val host = host("constantResult")
            val reference =
                PsiTreeUtil.collectElementsOfType(host, KtNameReferenceExpression::class.java).single { reference ->
                    reference.getReferencedName() == "value"
                }
            val constant = PsiTreeUtil.collectElementsOfType(host, KtStringTemplateExpression::class.java).single()
            assertTrue(constant.entries.none { it is KtStringTemplateEntryWithExpression })
            IntellijReadObservation.None.observedAnalyze(reference) { proveParameter(host, reference) }
            PacketExpression(selector(fixture, "constantResult"), range(reference)) to range(constant)
        }

    private fun inspect(
        fixture: NativePacketValueFixture,
        method: String,
        text: String,
        prove: KaSession.(KtNamedFunction, KtExpression) -> Unit,
    ): InterpolationControlFacts =
        ReadAction.compute<InterpolationControlFacts, RuntimeException> {
            val host = host(method)
            val templates = PsiTreeUtil.collectElementsOfType(host, KtStringTemplateExpression::class.java)
            val entry =
                templates
                    .flatMap { candidate -> candidate.entries.filterIsInstance<KtStringTemplateEntryWithExpression>() }
                    .single { candidate -> candidate.expression?.text == text }
            val expression = checkNotNull(entry.expression)
            val template = entry.parent as KtStringTemplateExpression
            assertSame(entry, expression.parent)
            assertTrue(range(template).startInclusive < range(entry).startInclusive)
            assertTrue(range(entry).endExclusive < range(template).endExclusive)
            IntellijReadObservation.None.observedAnalyze(expression) {
                assertEquals(KaSymbolOrigin.SOURCE, host.symbol.origin)
                prove(host, expression)
                assertClass(template, "kotlin.String", false)
            }
            InterpolationControlFacts(
                input = PacketExpression(selector(fixture, method), range(expression)),
                template = range(template),
                entry = range(entry),
                entryIndex = template.entries.indexOf(entry),
                literal = range(template.entries.first { it !is KtStringTemplateEntryWithExpression }),
                sibling = siblingTemplate(templates, template),
            )
        }

    private fun siblingTemplate(
        templates: Collection<KtStringTemplateExpression>,
        selected: KtStringTemplateExpression,
    ): ExactDeclarationTextRange? =
        templates
            .singleOrNull { candidate ->
                candidate !== selected &&
                    candidate.entries.filterIsInstance<KtStringTemplateEntryWithExpression>().any { entry ->
                        entry.expression?.text == "other"
                    }
            }
            ?.let(::range)

    private fun KaSession.proveParameter(host: KtNamedFunction, expression: KtExpression) {
        val reference = expression as KtNameReferenceExpression
        val actual = reference.references.filterIsInstance<KtReference>().single().resolveToSymbol()
        assertEquals(host.symbol.valueParameters.first(), actual)
        assertEquals(KaSymbolOrigin.SOURCE, checkNotNull(actual).origin)
    }

    private fun KaSession.proveCustom(host: KtNamedFunction, expression: KtExpression) {
        proveParameter(host, expression)
        assertClass(expression, "fixture.controls.InterpolationCustomValue", false)
        val file = expression.containingFile as KtFile
        val method =
            PsiTreeUtil.collectElementsOfType(file, KtNamedFunction::class.java).single { it.name == "toString" }
        assertEquals(KaSymbolOrigin.SOURCE, method.symbol.origin)
        assertEquals(
            "fixture.controls.InterpolationCustomValue.toString",
            method.symbol.callableId?.asSingleFqName()?.asString(),
        )
    }

    private fun KaSession.proveFlexible(expression: KtExpression) {
        val type = expression.expressionType as KaFlexibleType
        val lower = type.lowerBound as KaClassType
        val upper = type.upperBound as KaClassType
        assertEquals("kotlin.String", lower.classId.asSingleFqName().asString())
        assertEquals("kotlin.String", upper.classId.asSingleFqName().asString())
        assertFalse(lower.isMarkedNullable)
        assertTrue(upper.isMarkedNullable)
        val call = (expression as KtQualifiedExpression).selectorExpression as KtCallExpression
        assertEquals(KaSymbolOrigin.JAVA_LIBRARY, checkNotNull(call.resolveCall()).signature.symbol.origin)
    }

    private fun KaSession.assertClass(expression: KtExpression, qualifiedType: String, nullable: Boolean) {
        val type = expression.expressionType as KaClassType
        assertEquals(qualifiedType, type.classId.asSingleFqName().asString())
        assertEquals(nullable, type.isMarkedNullable)
    }

    private fun selector(fixture: NativePacketValueFixture, method: String) =
        fixture.callableDeclaration(
            PacketDeclaration(NativePacketStringInterpolationControlSource.PATH, "StringInterpolationControls", method)
        )

    private fun host(method: String): KtNamedFunction {
        val file = checkNotNull(base.findFileByRelativePath("src/fixture/controls/StringInterpolationControls.kt"))
        val psi = PsiManager.getInstance(project).findFile(file) as KtFile
        return PsiTreeUtil.collectElementsOfType(psi, KtNamedFunction::class.java).single { it.name == method }
    }

    private companion object {
        const val FLEXIBLE_EXPRESSION = "System.getProperty(\"kast.fixture.control\")"
    }
}

internal enum class InterpolationScalarControl(
    val method: String,
    val qualifiedType: String,
    val kind: ValueInterpolationScalarKind,
) {
    STRING("stringValue", "kotlin.String", ValueInterpolationScalarKind.STRING),
    BOOLEAN("booleanValue", "kotlin.Boolean", ValueInterpolationScalarKind.BOOLEAN),
    CHAR("charValue", "kotlin.Char", ValueInterpolationScalarKind.CHAR),
    BYTE("byteValue", "kotlin.Byte", ValueInterpolationScalarKind.BYTE),
    SHORT("shortValue", "kotlin.Short", ValueInterpolationScalarKind.SHORT),
    INT("intValue", "kotlin.Int", ValueInterpolationScalarKind.INT),
    LONG("longValue", "kotlin.Long", ValueInterpolationScalarKind.LONG),
    FLOAT("floatValue", "kotlin.Float", ValueInterpolationScalarKind.FLOAT),
    DOUBLE("doubleValue", "kotlin.Double", ValueInterpolationScalarKind.DOUBLE),
}

internal data class InterpolationControlFacts(
    val input: PacketExpression,
    val template: ExactDeclarationTextRange,
    val entry: ExactDeclarationTextRange,
    val entryIndex: Int,
    val literal: ExactDeclarationTextRange,
    val sibling: ExactDeclarationTextRange?,
)
