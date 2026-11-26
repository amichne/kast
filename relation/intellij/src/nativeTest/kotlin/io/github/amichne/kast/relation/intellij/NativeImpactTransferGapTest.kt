package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueCondition
import io.github.amichne.kast.relation.contract.ValueRole
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue

/** Literal-null predicates (F1) and producer results bound by a `when` subject (F3). */
class NativeImpactTransferGapTest : NativeImpactTransferFixture() {
    override val fixtureSdkName = "impact-transfer-gap-jdk"

    // F1: a literal-null predicate accounts for its operand without aliasing it into the Boolean result.

    fun testLiteralNullGuardsReachTheGuardedFieldRead() {
        val fixture = fixture(NULL_GUARD)
        for (function in listOf("guarded", "reversed", "inverse")) {
            val owner = PacketDeclaration(NULL_GUARD, "", function)
            val run = trace(fixture, owner)
            val read = fixture.markedExpression(owner, "read")
            val predicate = fixture.markedExpression(owner, "predicate")
            val excluded = fixture.markedExpression(owner, "excluded")
            assertTrue(
                "$function: the selected value did not reach its guarded field read: ${run.summary()}",
                run.sites.any { it.range == read.anchor && it.role == ValueRole.ExpressionResult },
            )
            assertTrue(
                "$function: the literal-null predicate retained a frontier: ${run.summary()}",
                run.obligations.none { it.within(predicate.anchor) },
            )
            assertTrue(
                "$function: the tracked object aliased into the Boolean predicate: ${run.summary()}",
                run.sites.none { it.range == predicate.anchor },
            )
            assertTrue(
                "$function: the null branch acquired the selected value: ${run.summary()}",
                run.sites.none { excluded.anchor.covers(it.range) },
            )
            val arrival = run.sites.single { it.range == read.anchor && it.role == ValueRole.ExpressionResult }
            val condition = arrival.conditions.facts.single() as ValueCondition.ReceiverNonNull
            assertEquals(read.anchor, condition.useRange)
            assertEquals(read.anchor.startInclusive, condition.origin.range.startInclusive)
            assertSupported(function, run)
        }
    }

    fun testLiteralNullShortcutLeavesGeneralEqualityToItsResolvedCall() {
        val fixture = fixture(EQUALITY_CONTROL)
        val nullOwner = PacketDeclaration(EQUALITY_CONTROL, "", "nullComparison")
        val nullRun = trace(fixture, nullOwner)
        val nullPredicate = fixture.markedExpression(nullOwner, "null-predicate")
        assertTrue(
            "A literal-null test against an overridden equals was treated as a call: ${nullRun.summary()}",
            nullRun.obligations.none { it.within(nullPredicate.anchor) },
        )
        assertTrue(nullRun.sites.none { it.range == nullPredicate.anchor })
        for ((function, marker) in
            listOf("generalEquality" to "general-equality", "nullableOperand" to "nullable-equality")) {
            val owner = PacketDeclaration(EQUALITY_CONTROL, "", function)
            val run = trace(fixture, owner)
            val equality = fixture.markedExpression(owner, marker)
            assertTrue(
                "$function: non-literal equality lost its resolved-call obligation: ${run.summary()}",
                run.obligations.any { it.within(equality.anchor) },
            )
        }
    }

    // F4: an equality whose `equals` the compiler proves cannot retain an operand consumes the tracked value.

    fun testProvenEqualityConsumesItsOperand() {
        val fixture = fixture(EQUALITY_SINK)
        val consumed =
            listOf(
                "finalLeft" to "source",
                "finalRight" to "source",
                "identity" to "source",
                "stringEquality" to "text",
            )
        for ((function, producer) in consumed) {
            val owner = PacketDeclaration(EQUALITY_SINK, "", function)
            val run = trace(fixture, owner, producer)
            val compare = fixture.markedExpression(owner, "compare")
            assertTrue(
                "$function: the tracked value aliased into the Boolean comparison: ${run.summary()}",
                run.sites.none { it.range == compare.anchor },
            )
            assertSupported(function, run)
        }
    }

    fun testUnprovenEqualityKeepsItsResolvedCallObligation() {
        val fixture = fixture(EQUALITY_SINK)
        val unproven =
            listOf("openEquality" to "open", "interfaceEquality" to "keyed", "overriddenReceivesValue" to "source")
        for ((function, producer) in unproven) {
            val owner = PacketDeclaration(EQUALITY_SINK, "", function)
            val run = trace(fixture, owner, producer)
            val compare = fixture.markedExpression(owner, "compare")
            assertTrue(
                "$function: an equality that may run a source override lost its obligation: ${run.summary()}",
                run.obligations.any { it.within(compare.anchor) },
            )
        }
    }

    // F3: a producer result bound by a `when` subject keeps its selected nested path in both declaration placements.

    fun testInlineWhenSubjectCarriesTheSelectedPayloadPath() = assertSubjectRoute(SUBJECT_INLINE, "inlineSubject")

    fun testHoistedWhenSubjectCarriesTheSelectedPayloadPath() = assertSubjectRoute(SUBJECT_HOISTED, "hoistedSubject")

    fun testShadowingLocalDoesNotAcquireTheSubjectPath() {
        val fixture = fixture(SUBJECT_SUPPORT, SUBJECT_SHADOWED)
        val owner = PacketDeclaration(SUBJECT_SHADOWED, "", "shadowedSubject")
        val run = trace(fixture, PacketDeclaration(SUBJECT_SUPPORT, "", "produce"), "selectedProducer")
        val binding = fixture.markedExpression(owner, "binding-init")
        val excluded = fixture.markedExpression(owner, "excluded-read")
        assertTrue(
            "The subject binding was not reached: ${run.summary()}",
            run.sites.any { it.role == ValueRole.LocalBinding && it.range.covers(binding.anchor) },
        )
        assertTrue(
            "The shadowing local acquired the selected path: ${run.summary()}",
            run.sites.none { excluded.anchor.covers(it.range) },
        )
        assertTrue(
            "The shadowed read retained an obligation instead of exclusion: ${run.summary()}",
            run.obligations.none { it.within(excluded.anchor) },
        )
    }

    private fun assertSubjectRoute(path: String, function: String) {
        val fixture = fixture(SUBJECT_SUPPORT, path)
        val owner = PacketDeclaration(path, "", function)
        val run = trace(fixture, PacketDeclaration(SUBJECT_SUPPORT, "", "produce"), "selectedProducer")
        val binding = fixture.markedExpression(owner, "binding-init")
        val selected = fixture.markedExpression(owner, "selected-read")
        val sibling = fixture.markedExpression(owner, "sibling-read")
        val error = fixture.markedExpression(owner, "error-read")
        val bound = run.sites.filter { it.range.covers(binding.anchor) && it.role == ValueRole.LocalBinding }
        assertEquals("Missing exact subject binding: ${run.summary()}", 1, bound.size)
        assertEquals(listOf("value", "left"), bound.single().valuePath.fieldNames())
        val arrival = run.sites.filter { it.range == selected.anchor && it.role == ValueRole.ExpressionResult }
        assertEquals("The selected field did not reach its read: ${run.summary()}", 1, arrival.size)
        assertTrue(arrival.single().valuePath.segments.isEmpty())
        assertEquals(selected.enclosing.compilerIdentity, arrival.single().enclosing.compilerIdentity)
        // The binding read inside the sibling chain still carries `[left]`; `.right` must then select a different
        // field.
        assertTrue(
            "The sibling field acquired the selected value: ${run.summary()}",
            run.sites.none { sibling.anchor.covers(it.range) && it.valuePath.segments.isEmpty() },
        )
        assertTrue(run.sites.none { it.range == sibling.anchor })
        assertTrue(
            "The error arm acquired the selected path: ${run.summary()}",
            run.sites.none { error.anchor.covers(it.range) },
        )
        assertSupported(function, run)
    }
}
