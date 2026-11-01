package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import io.github.amichne.kast.relation.contract.MapEntrySlot
import io.github.amichne.kast.relation.contract.PairSlot
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueCondition
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtFile

class NativeReviewedCollectionTest : NativeReviewedValueFixture() {
    override val fixtureSdkName = "reviewed-collection-jdk"

    fun testLiteralKeyListUsesTheExactCompilerVarargTarget() {
        collectionFixture()
        ReadAction.run<RuntimeException> {
            val virtual =
                checkNotNull(
                    getOrCreateProjectBaseDir()
                        .findFileByRelativePath("src/fixture/controls/ReviewedCollectionControls.kt")
                )
            assertReviewedLiteralListTarget(psiManager.findFile(virtual) as KtFile)
        }
    }

    fun testTuplePublicSitesRestoreOnlyTheirExactCompilerRoles() = runBlocking {
        val fixture = collectionFixture()
        for (name in
            listOf("pairFirstMatches", "pairSecondMatches", "constructorFirstMatches", "constructorSecondMatches")) {
            val owner = collectionDeclaration(name)
            val steps = trace(fixture, owner)
            val enclosing = fixture.markedExpression(owner, "destination").enclosing
            project.assertRestoredTupleSites(fixture, enclosing, steps.flatMap { it.reviewedTransfers })
        }
    }

    fun testPairSlotsReachTheirMatchingProjection() {
        val fixture = collectionFixture()
        for ((name, slot) in
            listOf(
                "pairFirstMatches" to PairSlot.FIRST,
                "pairSecondMatches" to PairSlot.SECOND,
                "constructorFirstMatches" to PairSlot.FIRST,
                "constructorSecondMatches" to PairSlot.SECOND,
            )) {
            val owner = collectionDeclaration(name)
            val steps = trace(fixture, owner)
            val tuple = fixture.markedExpression(owner, "tuple")
            assertTrue(
                "The selected producer must enter its exact tuple slot",
                steps
                    .flatMap { it.reviewedTransfers }
                    .any { transfer ->
                        transfer.target.range == tuple.anchor &&
                            (transfer.target.valuePath.segments.firstOrNull() as? ValuePathSegment.PairComponent)
                                ?.slot == slot
                    },
            )
            assertTrue(collectionResult(fixture, owner, steps).valuePath.segments.isEmpty())
        }
    }

    fun testPairSlotsRejectTheOppositeProjectionAfterConstruction() {
        val fixture = collectionFixture()
        for ((name, expected) in
            listOf(
                "pairKeyCannotBecomeValue" to PairSlot.FIRST,
                "pairValueCannotBecomeKey" to PairSlot.SECOND,
                "constructorKeyCannotBecomeValue" to PairSlot.FIRST,
            )) {
            val owner = PacketDeclaration(PIPELINE_VARIANTS, "", name)
            val steps = trace(fixture, owner)
            assertTrue(
                "The negative must cross its selected tuple construction",
                steps
                    .flatMap { it.reviewedTransfers }
                    .any { transfer ->
                        (transfer.target.valuePath.segments.firstOrNull() as? ValuePathSegment.PairComponent)?.slot ==
                            expected
                    },
            )
            val destination = fixture.markedExpression(owner, "destination")
            assertFalse(
                "The selected tuple slot cannot reach the opposite property",
                steps.any {
                    it.source.range == destination.anchor && it.source.role == ValueRole.Return
                },
            )
        }
    }

    fun testDistinctAndEqualSortKeysKeepSelectedElementContent() {
        val fixture = collectionFixture()
        for (name in listOf("distinctSortKeys", "equalSortKeys")) {
            val owner = collectionDeclaration(name)
            val steps = trace(fixture, owner)
            val result = collectionResult(fixture, owner, steps)
            assertEquals(2, result.valuePath.depth)
            assertEquals(ValuePathSegment.Element, result.valuePath.segments.first())
            assertEquals(
                "payload",
                (result.valuePath.segments.last() as ValuePathSegment.Field).field.declaration.name.value,
            )
            assertTrue(
                "Sorting must use an admitted receiver relationship",
                steps
                    .flatMap { it.reviewedTransfers }
                    .any {
                        it.source.role is ValueRole.Receiver && it.target.range == result.range
                    },
            )
        }
    }

    fun testAssociationRejectsTheOriginalSmallGrant() = runBlocking {
        val fixture = collectionFixture()
        val owner = collectionDeclaration("duplicateKeyAssociation")
        val steps = trace(fixture, owner, budget = composedBudget(fixture))
        val destination = fixture.markedExpression(owner, "destination")
        val output =
            steps
                .single { step ->
                    step.source.range == destination.anchor &&
                        step.source.role == ValueRole.ExpressionResult &&
                        step.source.valuePath.segments.firstOrNull() is ValuePathSegment.MapEntryComponent
                }
                .source
        assertEquals(ORIGINAL_RETURNED_BYTES, fixture.budget.returnedBytes.value)
        val read =
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(output, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        assertTrue(
            "The original native grant must reject the actual association output: $read",
            read is ValueFlowRead.Rejected,
        )
        assertEquals(ValueFlowRejection.GRANT_TOO_SMALL, (read as ValueFlowRead.Rejected).cause)
    }

    fun testDuplicateAndUnknownKeysKeepEntryUniquenessUnproved() {
        val fixture = collectionFixture()
        for (name in listOf("duplicateKeyAssociation", "unknownKeyAssociation")) {
            val owner = collectionDeclaration(name)
            val result = collectionResult(fixture, owner, trace(fixture, owner, budget = composedBudget(fixture)))
            val entry = result.valuePath.segments.single() as ValuePathSegment.MapEntryComponent
            assertEquals(MapEntrySlot.VALUE, entry.slot)
            assertTrue(
                "Final entry correlation must remain conditional for $name",
                result.conditions.facts.any {
                    it is ValueCondition.ReviewedEntryKeyUnique && it.entry == entry.entry
                },
            )
        }
    }

    fun testDistinctLiteralKeysPreserveTheirAssociatedValues() {
        val fixture = collectionFixture()
        val owner = collectionDeclaration("distinctKeyAssociation")
        val result = collectionResult(fixture, owner, trace(fixture, owner, budget = composedBudget(fixture)))
        val entry = result.valuePath.segments.single() as ValuePathSegment.MapEntryComponent
        assertEquals(MapEntrySlot.VALUE, entry.slot)
        assertFalse(
            "Complete distinct literal keys must discharge entry uniqueness",
            result.conditions.facts.any {
                it is ValueCondition.ReviewedEntryKeyUnique && it.entry == entry.entry
            },
        )
    }

    fun testAssociationCannotExchangeSameTypedKeyAndValue() {
        val fixture = collectionFixture()
        for ((name, expected) in
            listOf(
                "associationKeyCannotBecomeValue" to MapEntrySlot.KEY,
                "associationValueCannotBecomeKey" to MapEntrySlot.VALUE,
            )) {
            val owner = collectionDeclaration(name)
            val result = collectionResult(fixture, owner, trace(fixture, owner, budget = composedBudget(fixture)))
            assertEquals(expected, (result.valuePath.segments.single() as ValuePathSegment.MapEntryComponent).slot)
        }
    }

    fun testOneReturnedPairKeepsSharedEntryCorrelation() {
        val fixture = collectionFixture()
        val owner = collectionDeclaration("associationKeyCannotBecomeValue")
        val key = collectionResult(fixture, owner, trace(fixture, owner, budget = composedBudget(fixture)))
        val value =
            collectionResult(
                fixture,
                owner,
                trace(
                    fixture = fixture,
                    owner = owner,
                    budget = composedBudget(fixture),
                    producer = declaration("siblingPayload"),
                ),
            )
        val keyPosition = key.valuePath.segments.single() as ValuePathSegment.MapEntryComponent
        val valuePosition = value.valuePath.segments.single() as ValuePathSegment.MapEntryComponent
        assertEquals(MapEntrySlot.KEY, keyPosition.slot)
        assertEquals(MapEntrySlot.VALUE, valuePosition.slot)
        assertEquals(keyPosition.entry, valuePosition.entry)
    }

    fun testConstantAssociationResultCannotAcquireInputContent() {
        val fixture = collectionFixture()
        val owner = collectionDeclaration("constantAssociationDropsInput")
        val steps = trace(fixture, owner)
        val input = steps.flatMap { it.reviewedTransfers }.singleOrNull { it.target.role is ValueRole.Formal }
        assertNotNull("The source must reach the actual association callback input", input)
        val destination = fixture.markedExpression(owner, "destination")
        assertFalse(
            "The constant callback result cannot acquire the input payload",
            steps.any {
                it.source.range == destination.anchor && it.source.role == ValueRole.Return
            },
        )
    }

    private fun collectionFixture() = fixture(paths = listOf(CONTROLS, PIPELINE_VARIANTS, COLLECTION_CONTROLS))

    private fun collectionDeclaration(name: String) = PacketDeclaration(COLLECTION_CONTROLS, "", name)

    private companion object {
        const val ORIGINAL_RETURNED_BYTES = 1_000_000L
        const val COLLECTION_CONTROLS = "src/main/kotlin/fixture/controls/ReviewedCollectionControls.kt"
    }
}

private fun collectionResult(
    fixture: NativePacketValueFixture,
    owner: PacketDeclaration,
    steps: List<ValueFlowStep>,
): ValueSite {
    val destination = fixture.markedExpression(owner, "destination")
    val result = steps.singleOrNull { it.source.range == destination.anchor && it.source.role == ValueRole.Return }
    assertNotNull("Missing exact collection result in ${owner.name}: ${steps.flatMap { it.obligations }}", result)
    return checkNotNull(result).source
}
