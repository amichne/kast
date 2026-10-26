package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactValueConditionsTest {
    @Test
    fun `stable receiver encodes complete nonrecursive provenance and canonical types`() {
        val f = ImpactFindingFixture()
        val source = receiver(f)
        val condition = condition(f, source)
        val site = source.copy(conditions = f.bounded(listOf(condition)))
        val encoded = Json.encodeToString(site)
        val expected =
            Json.parseToJsonElement(
                requireNotNull(javaClass.getResource("/impact-conditions.expected.json")).readText()
            )
        assertEquals(expected, Json.parseToJsonElement(encoded).jsonObject.getValue("conditions"))
        val decoded = Json.decodeFromString<ImpactValueSiteReferenceDocument>(encoded)
        assertEquals(site, decoded)
        assertTrue(decoded.admitsExecutionOwner())
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            Json.decodeFromString<ImpactValueSiteReferenceDocument>(encoded.replace("STABLE_RECEIVER", "ASSUMED"))
        }
        val unconditional = Json.encodeToString(source)
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            Json.decodeFromString<ImpactValueSiteReferenceDocument>(unconditional.replace(",\"conditions\":[]", ""))
        }
    }

    @Test
    fun `only field reads append one matching fact and later direct edges conserve it`() {
        val f = ImpactFindingFixture()
        val source = receiver(f)
        val condition = condition(f, source)
        val fieldRead = fieldRead(f, source, condition)
        val target = fieldRead.target
        assertTrue(fieldRead.admitsEvidence())
        val next = target.copy(range = f.producer.range, role = ImpactValueRoleDocument.LocalBinding)
        assertTrue(
            ImpactCompilerTransferDocument(target, next, ImpactTransferKindDocument.LOCAL_BINDING).admitsEvidence()
        )
        assertFalse(
            ImpactCompilerTransferDocument(
                    target,
                    next.copy(conditions = EmptyImpactConditions),
                    ImpactTransferKindDocument.LOCAL_BINDING,
                )
                .admitsEvidence()
        )
        assertFalse(
            fieldRead
                .copy(kind = ImpactTransferKindDocument.LOCAL_READ, evidence = ImpactTransferEvidenceDocument.Direct)
                .admitsEvidence()
        )
    }

    @Test
    fun `field read rejects malformed duplicate and mismatched receiver facts`() {
        val f = ImpactFindingFixture()
        val source = receiver(f)
        val condition = condition(f, source)
        val fieldRead = fieldRead(f, source, condition)
        val target = fieldRead.target
        for (changed in
            listOf(
                condition.copy(useRange = source.range),
                condition.copy(origin = condition.origin.copy(range = target.range)),
                condition.copy(kind = ImpactReceiverKindDocument.DISPATCH),
                condition.copy(originalType = f.text("kotlin / Any?")),
                condition.copy(
                    origin =
                        condition.origin.copy(
                            execution =
                                condition.origin.execution.copy(
                                    enclosing = f.declaration.copy(file = f.text("/workspace/Other.kt"))
                                )
                        )
                ),
            )) {
            assertFalse(fieldRead.copy(target = target.copy(conditions = f.bounded(listOf(changed)))).admitsEvidence())
        }
        assertFalse(
            fieldRead.copy(target = target.copy(conditions = f.bounded(listOf(condition, condition)))).admitsEvidence()
        )
    }

    @Test
    fun `condition identity includes every origin component and both compiler types`() {
        val f = ImpactFindingFixture()
        val source = receiver(f)
        val condition = condition(f, source)
        val site = source.copy(conditions = f.bounded(listOf(condition)))
        val changes =
            listOf(
                condition.copy(originalType = f.text("kotlin/Any")),
                condition.copy(refinedType = f.text("fixture/Other")),
                condition.copy(useRange = source.range),
                condition.copy(kind = ImpactReceiverKindDocument.DISPATCH),
                condition.copy(origin = condition.origin.copy(range = f.destination.range)),
                condition.copy(origin = condition.origin.copy(role = ImpactValueRoleDocument.LocalRead)),
                condition.copy(
                    origin =
                        condition.origin.copy(valuePath = f.bounded(listOf(ImpactValuePathSegmentDocument.Element)))
                ),
                condition.copy(
                    origin =
                        condition.origin.copy(
                            execution =
                                condition.origin.execution.copy(
                                    enclosing =
                                        f.declaration.copy(
                                            basis =
                                                ImpactSemanticBasisDocument.Published(
                                                    f.text("/workspace"),
                                                    ImpactEvidenceRevisionDocument.parse(8).conditionValue(),
                                                )
                                        )
                                )
                        )
                ),
            )
        assertFalse(site.hasSameValueIdentity(source))
        for (changed in changes) {
            val other = site.copy(conditions = f.bounded(listOf(changed)))
            assertNotEquals(site, other)
            assertFalse(site.hasSameValueIdentity(other))
        }
        val foreign = site.copy(conditions = f.bounded(listOf(changes.last())))
        assertFalse(foreign.admitsExecutionOwner())
    }

    @Test
    fun `both cast prerequisites encode compiler target types without runtime generic claims`() {
        val f = ImpactFindingFixture()
        val source = f.producer.copy(role = ImpactValueRoleDocument.ExpressionResult)
        val unsafe = castCondition(f, source)
        val safe = unsafe.copy(kind = ImpactCastKindDocument.SAFE)
        val site = source.copy(conditions = f.bounded(listOf(unsafe, safe)))
        val encoded = Json.encodeToString(site)
        val expected =
            Json.parseToJsonElement(
                requireNotNull(javaClass.getResource("/impact-cast-conditions.expected.json")).readText()
            )
        assertEquals(expected, Json.parseToJsonElement(encoded).jsonObject.getValue("conditions"))
        assertEquals(site, Json.decodeFromString<ImpactValueSiteReferenceDocument>(encoded))
        assertTrue(site.admitsExecutionOwner())
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            Json.decodeFromString<ImpactValueSiteReferenceDocument>(encoded.replace("UNSAFE", "UNCHECKED"))
        }
        val first = source.copy(conditions = f.bounded(listOf(unsafe)))
        for (changed in
            listOf(
                safe,
                unsafe.copy(originalType = f.text("kotlin/Any")),
                unsafe.copy(targetType = f.text("kotlin/collections/List<kotlin/Int>")),
            )) {
            assertFalse(first.hasSameValueIdentity(first.copy(conditions = f.bounded(listOf(changed)))))
        }
    }

    @Test
    fun `cast results require one matching success prerequisite and preserve prior conditions`() {
        val f = ImpactFindingFixture()
        val prior = condition(f, receiver(f))
        val source = f.producer.copy(role = ImpactValueRoleDocument.LocalRead, conditions = f.bounded(listOf(prior)))
        val fact = castCondition(f, source)
        for (kind in listOf(ImpactCastKindDocument.UNSAFE, ImpactCastKindDocument.SAFE)) {
            val target =
                source.copy(
                    range = fact.useRange,
                    role = ImpactValueRoleDocument.ExpressionResult,
                    conditions = f.bounded(listOf(prior, fact.copy(kind = kind))),
                )
            val transfer = ImpactCompilerTransferDocument(source, target, ImpactTransferKindDocument.CAST_RESULT)
            assertTrue(transfer.admitsEvidence())
            assertFalse(transfer.copy(target = target.copy(conditions = source.conditions)).admitsEvidence())
            assertFalse(transfer.copy(target = target.copy(conditions = f.bounded(listOf(fact)))).admitsEvidence())
            assertFalse(transfer.copy(kind = ImpactTransferKindDocument.LOCAL_READ).admitsEvidence())
            assertTrue(
                ImpactCompilerTransferDocument(
                        target,
                        target.copy(role = ImpactValueRoleDocument.LocalBinding),
                        ImpactTransferKindDocument.LOCAL_BINDING,
                    )
                    .admitsEvidence()
            )
        }
    }

    @Test
    fun `cast prerequisites reject wrong origin containment owner roles and noncanonical types`() {
        val f = ImpactFindingFixture()
        val source = f.producer.copy(role = ImpactValueRoleDocument.ExpressionResult)
        val fact = castCondition(f, source)
        val target = source.copy(range = fact.useRange, conditions = f.bounded(listOf(fact)))
        val transfer = ImpactCompilerTransferDocument(source, target, ImpactTransferKindDocument.CAST_RESULT)
        val foreign =
            f.declaration.copy(
                basis =
                    ImpactSemanticBasisDocument.Published(
                        f.text("/other"),
                        ImpactEvidenceRevisionDocument.parse(7).conditionValue(),
                    )
            )
        val wrongFacts =
            listOf(
                fact.copy(origin = fact.origin.copy(range = f.destination.range)),
                fact.copy(useRange = f.destination.range),
                fact.copy(useRange = f.declaration.range.copy(end = f.offset(101))),
                fact.copy(origin = fact.origin.copy(role = ImpactValueRoleDocument.Return)),
                fact.copy(originalType = f.text("kotlin / Any?")),
                fact.copy(targetType = f.text("kotlin / String")),
                fact.copy(origin = fact.origin.copy(execution = fact.origin.execution.copy(enclosing = foreign))),
            )
        for (changed in wrongFacts) {
            assertFalse(transfer.copy(target = target.copy(conditions = f.bounded(listOf(changed)))).admitsEvidence())
        }
        assertFalse(transfer.copy(target = target.copy(conditions = EmptyImpactConditions)).admitsEvidence())
        assertFalse(transfer.copy(target = target.copy(role = ImpactValueRoleDocument.Return)).admitsEvidence())
        assertFalse(
            transfer
                .copy(target = target.copy(valuePath = f.bounded(listOf(ImpactValuePathSegmentDocument.Element))))
                .admitsEvidence()
        )
        assertFalse(
            transfer
                .copy(target = target.copy(enclosing = f.declaration.copy(file = f.text("/workspace/Other.kt"))))
                .admitsEvidence()
        )
    }
}

private fun castCondition(f: ImpactFindingFixture, source: ImpactValueSiteReferenceDocument) =
    ImpactValueConditionDocument.CastSuccess(
        ImpactValueConditionOriginDocument(
            ImpactExecutionReferenceDocument(source.enclosing, source.executionOwner),
            source.range,
            source.role,
            source.valuePath,
            source.callStack,
        ),
        ImpactSourceRangeDocument(f.offset(10), f.offset(30)),
        ImpactCastKindDocument.UNSAFE,
        f.text("kotlin/Any?"),
        f.text("kotlin/collections/List<kotlin/String>"),
    )

private fun fieldRead(
    f: ImpactFindingFixture,
    source: ImpactValueSiteReferenceDocument,
    condition: ImpactValueConditionDocument,
) =
    ImpactCompilerTransferDocument(
        source,
        f.destination.copy(
            role = ImpactValueRoleDocument.ExpressionResult,
            conditions = f.bounded(listOf(condition)),
        ),
        ImpactTransferKindDocument.FIELD_READ,
        ImpactTransferEvidenceDocument.Source(ImpactSourceOperationDocument.ReadField(f.declaration)),
    )

private fun receiver(f: ImpactFindingFixture) =
    f.producer.copy(
        role =
            ImpactValueRoleDocument.Formal(ImpactFormalPositionDocument.Receiver(ImpactReceiverKindDocument.EXTENSION))
    )

private fun condition(f: ImpactFindingFixture, source: ImpactValueSiteReferenceDocument) =
    ImpactValueConditionDocument.StableReceiver(
        ImpactValueConditionOriginDocument(
            ImpactExecutionReferenceDocument(source.enclosing, source.executionOwner),
            source.range,
            source.role,
            source.valuePath,
            source.callStack,
        ),
        f.destination.range,
        ImpactReceiverKindDocument.EXTENSION,
        f.text("kotlin/Any?"),
        f.text("fixture/Request"),
    )

private fun <T, F> io.github.amichne.kast.kernel.Refinement<T, F>.conditionValue(): T =
    when (this) {
        is io.github.amichne.kast.kernel.Refinement.Refined -> value
        is io.github.amichne.kast.kernel.Refinement.Rejected -> error(failure.toString())
    }
