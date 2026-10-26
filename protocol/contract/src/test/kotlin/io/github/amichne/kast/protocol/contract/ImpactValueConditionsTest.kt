package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StableLocalImpactValueConditionsTest {
    @Test
    fun `stable local encodes the required exact definition`() {
        val f = ImpactFindingFixture()
        val fact = localCondition(f)
        val encoded = Json.encodeToString<ImpactValueConditionDocument>(fact)
        val document = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("type", "origin", "useRange", "version", "originalType", "refinedType"), document.keys)
        assertEquals("STABLE_LOCAL", document.getValue("type").jsonPrimitive.content)
        assertEquals("kotlin/Any?", document.getValue("originalType").jsonPrimitive.content)
        assertEquals("fixture/Request", document.getValue("refinedType").jsonPrimitive.content)
        assertRange(document.getValue("useRange"), 35, 36)
        val version = document.getValue("version").jsonObject
        assertEquals(setOf("declaration", "block", "definition", "rhs", "end"), version.keys)
        assertRange(version.getValue("declaration"), 10, 30)
        assertRange(version.getValue("block"), 5, 95)
        assertRange(version.getValue("definition"), 10, 30)
        assertRange(version.getValue("rhs"), 20, 30)
        val end = version.getValue("end").jsonObject
        assertEquals(setOf("type", "range", "rhs"), end.keys)
        assertEquals("NEXT_WRITE", end.getValue("type").jsonPrimitive.content)
        assertRange(end.getValue("range"), 60, 80)
        assertRange(end.getValue("rhs"), 70, 80)
        assertEquals(fact, Json.decodeFromString<ImpactValueConditionDocument>(encoded))
    }

    @Test
    fun `stable local encodes complete nonrecursive origin with required empty state`() {
        val fact = localCondition(ImpactFindingFixture())
        val document = Json.parseToJsonElement(Json.encodeToString<ImpactValueConditionDocument>(fact)).jsonObject
        val origin = document.getValue("origin").jsonObject
        assertEquals(setOf("execution", "range", "role", "valuePath", "callStack"), origin.keys)
        assertRange(origin.getValue("range"), 10, 30)
        assertTrue(origin.getValue("valuePath").jsonArray.isEmpty())
        assertTrue(origin.getValue("callStack").jsonArray.isEmpty())
        val role = origin.getValue("role").jsonObject
        assertEquals(setOf("type"), role.keys)
        assertEquals("LOCAL_BINDING", role.getValue("type").jsonPrimitive.content)
        val execution = origin.getValue("execution").jsonObject
        assertEquals(setOf("enclosing", "executionOwner"), execution.keys)
        val owner = execution.getValue("executionOwner").jsonObject
        assertEquals(setOf("type"), owner.keys)
        assertEquals("DECLARATION", owner.getValue("type").jsonPrimitive.content)
        val enclosing = execution.getValue("enclosing").jsonObject
        assertEquals(setOf("basis", "file", "range", "compilerIdentity"), enclosing.keys)
        assertEquals("/workspace/File.kt", enclosing.getValue("file").jsonPrimitive.content)
        assertEquals(
            "canonical-signature-sha256-v1|" + "a".repeat(64),
            enclosing.getValue("compilerIdentity").jsonPrimitive.content,
        )
        assertRange(enclosing.getValue("range"), 0, 100)
        val basis = enclosing.getValue("basis").jsonObject
        assertEquals(setOf("type", "root", "generation"), basis.keys)
        assertEquals("PUBLISHED", basis.getValue("type").jsonPrimitive.content)
        assertEquals("/workspace", basis.getValue("root").jsonPrimitive.content)
        assertEquals(7, basis.getValue("generation").jsonPrimitive.int)
    }

    @Test
    fun `stable local decoding rejects missing null empty and unversioned proofs`() {
        val fact = localCondition(ImpactFindingFixture())
        val encoded = Json.encodeToString<ImpactValueConditionDocument>(fact)
        val versionField = "\"version\":" + Json.encodeToString(fact.version)
        val invalid =
            listOf(
                encoded.replace("$versionField,", ""),
                encoded.replace(versionField, "\"version\":null"),
                encoded.replace(versionField, "\"version\":{}"),
                encoded.replace(versionField, "\"version\":{\"type\":\"UNVERSIONED\"}"),
                encoded.replace("STABLE_LOCAL", "ASSUMED_LOCAL"),
            )
        for (malformed in invalid) {
            assertNotEquals(encoded, malformed)
            assertThrows(kotlinx.serialization.SerializationException::class.java) {
                Json.decodeFromString<ImpactValueConditionDocument>(malformed)
            }
        }
    }

    @Test
    fun `stable local appends only on the exact versioned read and cannot be erased downstream`() {
        val f = ImpactFindingFixture()
        val fact = localCondition(f)
        val source =
            f.producer.copy(
                range = fact.version.definition,
                role = ImpactValueRoleDocument.LocalBinding,
                localVersion = fact.version,
            )
        val target =
            f.producer.copy(
                range = fact.useRange,
                role = ImpactValueRoleDocument.LocalRead,
                conditions = f.bounded(listOf(fact)),
            )
        val transfer = ImpactCompilerTransferDocument(source, target, ImpactTransferKindDocument.LOCAL_READ)
        assertTrue(target.admitsExecutionOwner())
        assertTrue(transfer.admitsEvidence())
        assertFalse(
            transfer.copy(source = source.copy(localVersion = ImpactLocalVersionDocument.Unversioned)).admitsEvidence()
        )
        assertFalse(transfer.copy(kind = ImpactTransferKindDocument.LOCAL_BINDING).admitsEvidence())
        assertFalse(transfer.copy(target = target.copy(range = f.destination.range)).admitsEvidence())
        assertFalse(transfer.copy(target = target.copy(conditions = f.bounded(listOf(fact, fact)))).admitsEvidence())
        val otherVersion = fact.version.copy(end = ImpactLocalLifetimeEndDocument.BlockEnd(fact.version.block))
        val changed = target.copy(conditions = f.bounded(listOf(fact.copy(version = otherVersion))))
        assertTrue(changed.admitsExecutionOwner())
        assertFalse(target.hasSameValueIdentity(changed))
        assertFalse(transfer.copy(target = changed).admitsEvidence())
        val next = target.copy(role = ImpactValueRoleDocument.Return)
        val downstream = ImpactCompilerTransferDocument(target, next, ImpactTransferKindDocument.RETURN)
        assertTrue(downstream.admitsEvidence())
        assertFalse(downstream.copy(target = next.copy(conditions = EmptyImpactConditions)).admitsEvidence())
        assertFalse(downstream.copy(target = changed.copy(role = ImpactValueRoleDocument.Return)).admitsEvidence())
    }

    @Test
    fun `stable local rejects invalid definitions lifetimes origins and compiler types`() {
        val f = ImpactFindingFixture()
        val fact = localCondition(f)
        val invalid =
            listOf(
                fact.copy(version = fact.version.copy(definition = f.producer.range)),
                fact.copy(version = fact.version.copy(rhs = fact.useRange)),
                fact.copy(version = fact.version.copy(declaration = fact.useRange)),
                fact.copy(version = fact.version.copy(block = f.producer.range)),
                fact.copy(version = fact.version.copy(end = ImpactLocalLifetimeEndDocument.BlockEnd(f.producer.range))),
                fact.copy(
                    version =
                        fact.version.copy(
                            end = ImpactLocalLifetimeEndDocument.NextWrite(f.producer.range, fact.useRange)
                        )
                ),
                fact.copy(useRange = fact.version.rhs),
                fact.copy(useRange = ImpactSourceRangeDocument(f.offset(85), f.offset(86))),
                fact.copy(origin = fact.origin.copy(role = ImpactValueRoleDocument.LocalRead)),
                fact.copy(origin = fact.origin.copy(range = f.producer.range)),
                fact.copy(originalType = f.text("kotlin / Any?")),
                fact.copy(refinedType = f.text("fixture / Request")),
            )
        for (changed in invalid) {
            val site = f.destination.copy(conditions = f.bounded(listOf(changed)))
            assertFalse(site.admitsExecutionOwner(), changed.toString())
        }
        val rhsRead = fact.copy(useRange = ImpactSourceRangeDocument(f.offset(70), f.offset(71)))
        assertTrue(f.destination.copy(conditions = f.bounded(listOf(rhsRead))).admitsExecutionOwner())
    }
}

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

private fun assertRange(element: JsonElement, start: Int, end: Int) {
    val range = element.jsonObject
    assertEquals(setOf("start", "end"), range.keys)
    assertEquals(start, range.getValue("start").jsonPrimitive.int)
    assertEquals(end, range.getValue("end").jsonPrimitive.int)
}

private fun localCondition(f: ImpactFindingFixture): ImpactValueConditionDocument.StableLocal {
    fun range(start: Int, end: Int) = ImpactSourceRangeDocument(f.offset(start), f.offset(end))
    val definition = range(10, 30)
    return ImpactValueConditionDocument.StableLocal(
        ImpactValueConditionOriginDocument(
            ImpactExecutionReferenceDocument(f.declaration, ImpactExecutionOwnerDocument.Declaration),
            definition,
            ImpactValueRoleDocument.LocalBinding,
        ),
        range(35, 36),
        ImpactLocalVersionDocument.Defined(
            definition,
            range(5, 95),
            definition,
            range(20, 30),
            ImpactLocalLifetimeEndDocument.NextWrite(range(60, 80), range(70, 80)),
        ),
        f.text("kotlin/Any?"),
        f.text("fixture/Request"),
    )
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
