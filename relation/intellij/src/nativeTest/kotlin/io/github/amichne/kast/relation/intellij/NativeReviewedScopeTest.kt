package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.amichne.kast.relation.contract.MapEntrySlot
import io.github.amichne.kast.relation.contract.ReviewedCallbackInvocationEvidence
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedCollectionSelection
import io.github.amichne.kast.relation.contract.ReviewedLibraryRule
import io.github.amichne.kast.relation.contract.ReviewedValueRelation
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking

class NativeReviewedScopeTest : NativeReviewedValueFixture() {
    override val fixtureSdkName = "reviewed-scope-jdk"

    fun testCallbackResultCannotReplaceAlsoReceiverOrConstantReturn() {
        val fixture = fixture()
        for (name in listOf("reviewedAlso", "constantReturn", "abruptLet", "throwingLet", "mutatedAlso")) {
            val owner = declaration(name)
            val steps = trace(fixture, owner)
            val destination = fixture.markedExpression(owner, "destination")
            assertTrue("The producer must run through the real transfer owner", steps.isNotEmpty())
            assertFalse("Forbidden payload arrival at $name", steps.any { it.source.range == destination.anchor })
            assertTrue(
                "No callback-result correspondence belongs to $name",
                steps.flatMap { it.reviewedTransfers }.isEmpty(),
            )
        }
    }

    fun testLetReturnKeepsTheSelectedNestedField() {
        val fixture = fixture()
        val owner = declaration("reviewedLet")
        val steps = trace(fixture, owner)
        val destination = fixture.markedExpression(owner, "destination")
        val result = steps.singleOrNull { it.source.range == destination.anchor && it.source.role == ValueRole.Return }
        assertNotNull("Missing let factory return: ${steps.flatMap { it.obligations }}", result)
        val path = checkNotNull(result).source.valuePath.segments
        assertEquals(1, path.size)
        assertEquals("selected", (path.single() as ValuePathSegment.Field).field.declaration.name.value)
        assertEquals(1, steps.flatMap { it.reviewedTransfers }.size)
    }

    fun testAlsoReturnsTheDistinctReceiverWholeValue() {
        val fixture = fixture()
        val owner = declaration("reviewedAlso")
        val steps = trace(fixture, owner, declaration("siblingPayload"))
        val destination = fixture.markedExpression(owner, "destination")
        val result = steps.singleOrNull { it.source.range == destination.anchor && it.source.role == ValueRole.Return }
        assertNotNull("Missing also receiver result: ${steps.flatMap { it.obligations }}", result)
        assertTrue(checkNotNull(result).source.valuePath.segments.isEmpty())
        assertEquals(1, steps.flatMap { it.reviewedTransfers }.size)
    }

    fun testSafeAndNullReceiversKeepConditionalReturnCorrespondence() {
        val fixture = fixture()
        for (name in listOf("safeLet", "nullLet")) {
            val owner = declaration(name)
            val steps = trace(fixture, owner)
            val edge = steps.flatMap { it.reviewedTransfers }.single()
            assertEquals(
                io.github.amichne.kast.relation.contract.ReviewedValueSemantics
                    .CONDITIONAL_CALLBACK_RESULT_TO_CALL_RESULT,
                (edge.relation as ReviewedValueRelation.CallbackCorrespondence).semantics,
            )
            assertEquals(edge.source.conditions, edge.target.conditions)
            assertTrue(
                edge.source.executionOwner.context
                    is io.github.amichne.kast.relation.contract.ValueExecutionContext.DeclaredCallback
            )
        }
    }

    fun testAbruptCallbacksCannotReturnTheReceiverNormally() {
        val fixture = fixture()
        for (name in listOf("receiverThrow", "receiverNonLocal", "receiverNothing", "receiverEarlyThrow")) {
            val owner = declaration(name)
            val steps = trace(fixture, owner, declaration("siblingPayload"))
            val destination = fixture.markedExpression(owner, "destination")
            assertFalse(
                "Forbidden normal result from abrupt callback $name",
                steps.any { it.source.range == destination.anchor },
            )
            assertTrue(steps.flatMap { it.reviewedTransfers }.isEmpty())
        }
    }

    fun testReviewedReceiverRestoresItsExactPublicSite() = runBlocking {
        val fixture = fixture()
        val owner = declaration("reviewedAlso")
        val steps = trace(fixture, owner, declaration("siblingPayload"))
        project.assertReviewedRestoration(fixture, owner, steps)
    }

    fun testLocalCallbackReturnKeepsItsExactTarget() {
        val fixture = fixture()
        val owner = declaration("labeledLet")
        val steps = trace(fixture, owner)
        val destination = fixture.markedExpression(owner, "destination")
        assertTrue(
            "Missing labeled callback return: ${steps.flatMap { it.obligations }}",
            steps.any { it.source.range == destination.anchor && it.source.role == ValueRole.Return },
        )
        assertEquals(
            "selected",
            (steps.flatMap { it.reviewedTransfers }.single().target.valuePath.segments.single()
                    as ValuePathSegment.Field)
                .field
                .declaration
                .name
                .value,
        )
    }

    fun testMapInputCannotReachSiblingOrConstantOutput() = assertMapNegatives(fixture(consumer = false))

    fun testConsumerMapInputCannotReachSiblingOrConstantOutput() = assertMapNegatives(fixture())

    fun testMapInputTraversesItsExactFormalAndBodyBeforeOutput() =
        assertSelectedMapRoute(fixture(consumer = false), ReviewedLibraryRule.KOTLIN_2_4_21_ITERABLE_MAP_V1)

    fun testConsumerMapInputTraversesItsExactFormalAndBodyBeforeOutput() = assertSelectedMapRoute(fixture())

    fun testEmptyConsumerMapKeepsConditionalCorrespondence() {
        val fixture = fixture(paths = listOf(CONTROLS, MAP_VARIANTS))
        val owner = PacketDeclaration(MAP_VARIANTS, "", "emptyMapSelectedRoute")
        val steps = trace(fixture, owner)
        assertMapRoute(fixture, owner, steps)
        assertTrue(
            steps
                .flatMap { it.reviewedTransfers }
                .all { transfer ->
                    (transfer.binding as ReviewedCallbackValueBinding).invocationEvidence ==
                        ReviewedCallbackInvocationEvidence.Unestablished &&
                        transfer.binding.artifact.rule == ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_MAP_V1
                }
        )
    }

    fun testChangedConsumerSourceRejectsSecondMapInput() {
        val fixture = fixture(paths = listOf(CONTROLS, MAP_VARIANTS))
        val owner = PacketDeclaration(MAP_VARIANTS, "", "artifactMapSelectedRoute")
        val firstOutput = fixture.markedExpression(owner, "first-output")
        val destination = fixture.markedExpression(owner, "destination")
        val observation = ValueReturnTestObservation()
        val ownedSourceJar = getOrCreateProjectBaseDir().toNioPath().resolve("kotlin-stdlib-2.3.0-sources.jar")
        var changed = false
        val steps =
            trace(
                fixture,
                owner,
                observation = observation,
                beforeRead = { site ->
                    if (!changed && site.role == ValueRole.ExpressionResult && site.range == firstOutput.anchor) {
                        changed = true
                        Files.write(ownedSourceJar, byteArrayOf(0), StandardOpenOption.APPEND)
                        ApplicationManager.getApplication().invokeAndWait {
                            checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(ownedSourceJar))
                                .refresh(false, false)
                        }
                    }
                },
            )
        assertTrue("The source artifact must change after the first map output", changed)
        val reviewed = steps.flatMap { it.reviewedTransfers }
        assertEquals(1, reviewed.size)
        assertEquals(firstOutput.anchor, reviewed.single().target.range)
        assertTrue(IntellijReadTermination.VALUE_REVIEWED_ARTIFACT_MISMATCH in observation.terminations)
        assertFalse(reviewed.any { it.target.role is ValueRole.Formal })
        assertFalse(steps.any { it.source.range == destination.anchor })
    }

    fun testTupleLibraryTargetsComeFromTheSelectedCompilerSymbols() {
        fixture(paths = listOf(CONTROLS, PIPELINE_VARIANTS))
        com.intellij.openapi.application.ReadAction.run<RuntimeException> {
            val virtual =
                checkNotNull(
                    getOrCreateProjectBaseDir()
                        .findFileByRelativePath("src/fixture/controls/ReviewedPipelineVariants.kt")
                )
            val file = psiManager.findFile(virtual) as org.jetbrains.kotlin.psi.KtFile
            assertReviewedTupleTargets(file)
        }
    }

    fun testSortSelectorCannotBecomeAnOutputElement() {
        val fixture = fixture(paths = listOf(CONTROLS, PIPELINE_VARIANTS))
        val owner = PacketDeclaration(PIPELINE_VARIANTS, "", "selectorCannotBecomeElement")
        assertPipelineExcludesDestination(fixture, owner, trace(fixture, owner))
    }

    fun testSameTypedPairSlotsCannotExchangeValues() {
        val fixture = fixture(paths = listOf(CONTROLS, PIPELINE_VARIANTS))
        for (name in
            listOf("pairKeyCannotBecomeValue", "pairValueCannotBecomeKey", "constructorKeyCannotBecomeValue")) {
            val owner = PacketDeclaration(PIPELINE_VARIANTS, "", name)
            assertPipelineExcludesDestination(fixture, owner, trace(fixture, owner))
        }
    }

    fun testSelectedElementCrossesTheWholeSchemaPipeline() {
        val fixture = fixture(paths = listOf(CONTROLS, PIPELINE_VARIANTS))
        val owner = PacketDeclaration(PIPELINE_VARIANTS, "", "schemaPipelineElement")
        val steps = trace(fixture, owner, budget = composedBudget(fixture))
        assertPipelineStages(
            fixture = fixture,
            owner = owner,
            steps = steps,
            selection = ReviewedCollectionSelection.ELEMENT,
        )
        val mapped = fixture.markedExpression(owner, "map-output")
        assertTrue(steps.any { it.source.range == mapped.anchor })
        val argument = pipelineArgument(fixture, owner, steps)
        assertEquals(
            listOf("feature", "payload"),
            argument.valuePath.segments.filterIsInstance<ValuePathSegment.Field>().map {
                it.field.declaration.name.value
            },
        )
    }

    fun testWholeCollectionCrossesTheSchemaPipelineAsContent() {
        val fixture = fixture(paths = listOf(CONTROLS, PIPELINE_VARIANTS))
        val owner = PacketDeclaration(PIPELINE_VARIANTS, "", "schemaPipelineWhole")
        val producer = PacketDeclaration(PIPELINE_VARIANTS, "", "pipelineFeatures")
        val steps = trace(fixture = fixture, owner = owner, budget = composedBudget(fixture), producer = producer)
        assertTrue(steps.first().source.valuePath.segments.isEmpty())
        assertPipelineStages(
            fixture = fixture,
            owner = owner,
            steps = steps,
            selection = ReviewedCollectionSelection.CONTENT,
        )
        val argument = pipelineArgument(fixture, owner, steps)
        assertEquals(
            listOf("feature"),
            argument.valuePath.segments.filterIsInstance<ValuePathSegment.Field>().map {
                it.field.declaration.name.value
            },
        )
        assertFalse(argument.valuePath.segments.isEmpty())
    }

    private fun assertMapNegatives(fixture: NativePacketValueFixture) {
        for (name in listOf("mapSiblingRoute", "mapConstantRoute")) {
            val owner = declaration(name)
            assertMapNegative(fixture, owner, trace(fixture, owner))
        }
    }

    private fun assertSelectedMapRoute(
        fixture: NativePacketValueFixture,
        rule: ReviewedLibraryRule = ReviewedLibraryRule.KOTLIN_2_3_0_ITERABLE_MAP_V1,
    ) {
        val owner = declaration("mapSelectedRoute")
        val steps = trace(fixture, owner)
        assertMapRoute(fixture, owner, steps)
        assertTrue(steps.flatMap { it.reviewedTransfers }.all { it.binding.artifact.rule == rule })
    }
}

private fun assertMapNegative(fixture: NativePacketValueFixture, owner: PacketDeclaration, steps: List<ValueFlowStep>) {
    val destination = fixture.markedExpression(owner, "destination")
    val reviewed = steps.flatMap { it.reviewedTransfers }
    val prefix = reviewed.firstOrNull()
    assertNotNull("The selected producer must cross the first real map callback", prefix)
    val path = checkNotNull(prefix).target.valuePath.segments
    assertEquals(2, path.size)
    assertEquals(ValuePathSegment.Element, path.first())
    assertEquals("selected", (path.last() as ValuePathSegment.Field).field.declaration.name.value)
    val input = reviewed.singleOrNull { it.target.role is ValueRole.Formal }
    assertNotNull("The negative must reach the actual second callback formal", input)
    assertMapInput(checkNotNull(input))
    assertFalse(
        "The selected producer cannot reach the unrelated output in ${owner.name}",
        steps.any { it.source.range == destination.anchor && it.source.role == ValueRole.Return },
    )
}

private fun assertMapRoute(fixture: NativePacketValueFixture, owner: PacketDeclaration, steps: List<ValueFlowStep>) {
    val destination = fixture.markedExpression(owner, "destination")
    val bodyRead = fixture.markedExpression(owner, "body-read")
    val returned = steps.singleOrNull {
        it.source.range == destination.anchor && it.source.role == ValueRole.Return
    }
    assertNotNull("Missing selected map output: ${steps.flatMap { it.obligations }}", returned)
    val outputPath = checkNotNull(returned).source.valuePath.segments
    assertEquals(2, outputPath.size)
    assertEquals(ValuePathSegment.Element, outputPath.first())
    assertEquals("payload", (outputPath.last() as ValuePathSegment.Field).field.declaration.name.value)
    val reviewed = steps.flatMap { it.reviewedTransfers }
    assertEquals(
        "Two callback outputs and one callback input must remain separate",
        MAP_RELATION_COUNT,
        reviewed.size,
    )
    val input = reviewed.single { it.target.role is ValueRole.Formal }
    assertMapInput(input)
    assertTrue(
        "The real formal-read owner must connect the input to the marked callback reference",
        steps
            .flatMap { it.transfers }
            .any { edge ->
                edge.source == input.target &&
                    edge.target.range == bodyRead.anchor &&
                    edge.target.executionOwner == input.target.executionOwner
            },
    )
    assertFalse(
        "The receiver cannot bypass the callback body to reach the collection result",
        reviewed.any {
            it.source.role is ValueRole.Receiver && it.target.range == destination.anchor
        },
    )
}

private fun assertMapInput(input: io.github.amichne.kast.relation.contract.ReviewedValueTransfer) {
    assertTrue(input.source.role is ValueRole.Receiver)
    val formal = (input.target.role as ValueRole.Formal).position as ValueFormalPosition.Parameter
    assertEquals(0, formal.parameter.position.value)
    assertEquals("it", formal.parameter.name.value)
    assertTrue(input.target.executionOwner.context is ValueExecutionContext.ReviewedCallback)
    assertEquals(input.target.executionOwner, formal.parameter.executionOwner)
    assertEquals(input.source.callStack, input.target.callStack)
    assertEquals(input.source.conditions, input.target.conditions)
    assertEquals(1, input.target.valuePath.depth)
    assertEquals(
        "selected",
        (input.target.valuePath.segments.single() as ValuePathSegment.Field).field.declaration.name.value,
    )
}

private const val MAP_RELATION_COUNT = 3

private fun assertPipelineExcludesDestination(
    fixture: NativePacketValueFixture,
    owner: PacketDeclaration,
    steps: List<ValueFlowStep>,
) {
    val destination = fixture.markedExpression(owner, "destination")
    assertTrue("The exact source producer must pass native seed admission", steps.isNotEmpty())
    assertFalse(
        "Forbidden selected-producer arrival at ${owner.name}",
        steps.any { it.source.range == destination.anchor && it.source.role == ValueRole.Return },
    )
}

private fun assertPipelineStages(
    fixture: NativePacketValueFixture,
    owner: PacketDeclaration,
    steps: List<ValueFlowStep>,
    selection: ReviewedCollectionSelection,
) {
    val argument = pipelineArgument(fixture, owner, steps)
    val entry = argument.valuePath.segments.first() as ValuePathSegment.MapEntryComponent
    assertEquals(MapEntrySlot.VALUE, entry.slot)
    val sorting =
        steps
            .flatMap { it.reviewedTransfers }
            .map { it.relation }
            .filterIsInstance<ReviewedValueRelation.ReorderedElements>()
            .single()
    assertEquals(selection, sorting.selection)
    for (marker in listOf("sorted-output", "pair", "feature-read")) {
        val selected = fixture.markedExpression(owner, marker)
        assertTrue(
            "Missing independently marked pipeline stage $marker",
            steps.any { it.source.range == selected.anchor },
        )
    }
}

private fun pipelineArgument(
    fixture: NativePacketValueFixture,
    owner: PacketDeclaration,
    steps: List<ValueFlowStep>,
): ValueSite {
    val destination = fixture.markedExpression(owner, "destination")
    val schema = fixture.markedExpression(owner, "schema")
    val argument = steps.singleOrNull { step ->
        step.source.range == destination.anchor &&
            step.source.role is ValueRole.Argument &&
            step.source.valuePath.segments.filterIsInstance<ValuePathSegment.Field>().any {
                it.field.declaration.name.value == "feature"
            }
    }
    assertNotNull("Missing schema constructor argument: ${steps.flatMap { it.obligations }}", argument)
    val site = checkNotNull(argument).source
    val role = site.role as ValueRole.Argument
    assertEquals(0, role.position.value)
    assertEquals(schema.anchor, role.call.range)
    return site
}

private suspend fun Project.assertReviewedRestoration(
    fixture: NativePacketValueFixture,
    owner: PacketDeclaration,
    steps: List<ValueFlowStep>,
) {
    val edge = steps.flatMap { it.reviewedTransfers }.single()
    val destination = fixture.markedExpression(owner, "destination")
    val selected =
        io.github.amichne.kast.symbol.contract.SymbolSelector.issue(
            fixture.lease,
            edge.source.enclosing.scope,
            edge.binding.target,
        )
    val claim =
        io.github.amichne.kast.relation.contract.ValueSiteRoleClaim.Receiver(
            destination.anchor,
            selected,
            io.github.amichne.kast.relation.contract.ValueReceiverKind.EXTENSION,
        )
    val question =
        io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest.create(
                enclosing = destination.enclosing,
                anchor = edge.source.range,
                role = claim,
                budget = fixture.budget,
            )
            .nativeRefined()
    val read =
        IntellijValueFlowCompilerAdapter()
            .revalidateSite(project = this, current = fixture.lease, request = question, model = fixture.model)
    assertTrue(
        "Reviewed receiver site must restore: $read",
        read is io.github.amichne.kast.relation.contract.ValueModelSiteRead.Revalidated,
    )
    assertEquals(
        edge.source.role,
        (read as io.github.amichne.kast.relation.contract.ValueModelSiteRead.Revalidated).position.site.role,
    )
}
