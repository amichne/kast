package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactPredicateStateShapeTest {
    private val basis =
        ImpactSemanticBasisDocument.Published(text("/workspace"), ImpactEvidenceRevisionDocument.parse(7).value())
    private val caller = declaration("Caller.kt", 0, 100, 'a')
    private val field = declaration("Values.kt", 10, 20, 'c')

    @Test
    fun `implicit formals keep the exact anonymous execution body and reject contradictory origin`() {
        val owner = ImpactExecutionOwnerDocument.Anonymous(caller.copy(range = range(10, 90)))
        val implicit =
            ImpactFormalParameterDocument(
                caller,
                offset(0),
                text("it"),
                range(10, 90),
                owner,
                ImpactFormalOriginDocument.Implicit,
            )
        val site =
            ImpactValueSiteReferenceDocument(
                caller,
                implicit.range,
                ImpactValueRoleDocument.Formal(ImpactFormalPositionDocument.Parameter(implicit)),
                owner,
            )
        assertTrue(site.admitsExecutionOwner())
        val invalid =
            listOf(
                implicit.copy(origin = ImpactFormalOriginDocument.Declared(range(11, 20))),
                implicit.copy(name = text("other")),
                implicit.copy(index = offset(1)),
                implicit.copy(executionOwner = ImpactExecutionOwnerDocument.Declaration),
            )
        for (parameter in invalid) {
            val changed =
                site.copy(role = ImpactValueRoleDocument.Formal(ImpactFormalPositionDocument.Parameter(parameter)))
            assertFalse(changed.admitsExecutionOwner(), parameter.toString())
        }
    }

    @Test
    fun `predicate subject and resolved subtype keep the exact semantic basis`() {
        val subject = ImpactPredicateSubjectDocument(caller, range(20, 25), ImpactValueRoleDocument.LocalRead)
        val predicate = ImpactValuePredicateDocument.Subtype(subject, field)
        val site =
            ImpactValueSiteReferenceDocument(
                caller,
                range(50, 60),
                ImpactValueRoleDocument.ExpressionResult,
                predicates = bounded(listOf(predicate)),
            )
        assertTrue(site.admitsExecutionOwner())
        val foreign = basis.copy(generation = ImpactEvidenceRevisionDocument.parse(8).value())
        assertFalse(
            site
                .copy(predicates = bounded(listOf(predicate.copy(resolvedType = field.copy(basis = foreign)))))
                .admitsExecutionOwner()
        )
        assertFalse(
            site
                .copy(
                    predicates =
                        bounded(
                            listOf(predicate.copy(subject = subject.copy(enclosing = caller.copy(basis = foreign))))
                        )
                )
                .admitsExecutionOwner()
        )
    }

    private fun declaration(file: String, start: Int, end: Int, hash: Char) =
        ImpactDeclarationReferenceDocument(
            basis,
            text("/workspace/$file"),
            range(start, end),
            text("canonical-signature-sha256-v1|" + hash.toString().repeat(64)),
        )

    private fun text(value: String) = ProtocolText.parse(value).value()

    private fun offset(value: Int) = ProtocolOffset.parse(value).value()

    private fun range(start: Int, end: Int) = ImpactSourceRangeDocument(offset(start), offset(end))

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
