package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ReviewedCallbackInvocationEvidence
import io.github.amichne.kast.relation.contract.ReviewedLibraryRule
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking

class NativeReviewedScopeTest : NativePacketValueFixtureTest() {
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
        val steps = trace(fixture, owner, "siblingPayload")
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
                edge.semantics,
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
            val steps = trace(fixture, owner, "siblingPayload")
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
        val steps = trace(fixture, owner, "siblingPayload")
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
                .revalidateSite(project = project, current = fixture.lease, request = question, model = fixture.model)
        assertTrue(
            "Reviewed receiver site must restore: $read",
            read is io.github.amichne.kast.relation.contract.ValueModelSiteRead.Revalidated,
        )
        assertEquals(
            edge.source.role,
            (read as io.github.amichne.kast.relation.contract.ValueModelSiteRead.Revalidated).position.site.role,
        )
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
                    transfer.binding.invocationEvidence == ReviewedCallbackInvocationEvidence.Unestablished &&
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

    private fun fixture(consumer: Boolean = true, paths: List<String> = listOf(CONTROLS)): NativePacketValueFixture =
        packetFixture(
            paths,
            configureCompiler = {
                ModuleRootModificationUtil.updateModel(module) { model ->
                    for (library in model.moduleLibraryTable.libraries) model.moduleLibraryTable.removeLibrary(library)
                    val library = model.moduleLibraryTable.createLibrary("reviewed-value-stdlib").modifiableModel
                    for ((name, kind, hash) in reviewedFiles(consumer)) {
                        val source = reviewedLibraryPath(name)
                        val bytes = Files.readAllBytes(source)
                        assertEquals(
                            hash,
                            java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                        )
                        val owned = getOrCreateProjectBaseDir().toNioPath().resolve(name)
                        Files.write(owned, bytes)
                        checkNotNull(
                            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(owned)
                        )
                        library.addRoot("jar://$owned!/", kind)
                    }
                    library.commit()
                }
            },
        )

    private fun reviewedFiles(consumer: Boolean): List<Triple<String, OrderRootType, String>> =
        if (consumer)
            listOf(
                Triple(
                    "kotlin-stdlib-2.3.0.jar",
                    OrderRootType.CLASSES,
                    "887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1",
                ),
                Triple(
                    "kotlin-stdlib-2.3.0-sources.jar",
                    OrderRootType.SOURCES,
                    "326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531",
                ),
            )
        else
            listOf(
                Triple(
                    "kotlin-stdlib-2.4.21.jar",
                    OrderRootType.CLASSES,
                    "bd8250210584cb659847dce9cda660f8e9906e5def7fbebcea93dc6dcb6a88a3",
                ),
                Triple(
                    "kotlin-stdlib-2.4.21-sources.jar",
                    OrderRootType.SOURCES,
                    "1e29c0668b0aa555e7b154e5da2e8094e1b755cfee25737839195bbbdad8a4a2",
                ),
            )

    private fun reviewedLibraryPath(name: String): Path =
        if (name == "kotlin-stdlib-2.4.21.jar")
            System.getProperty("java.class.path").split(java.io.File.pathSeparator).map(Path::of).single {
                it.fileName.toString() == name
            }
        else Path.of(checkNotNull(javaClass.getResource("/reviewed-libraries/$name")).toURI())

    private fun trace(
        fixture: NativePacketValueFixture,
        owner: PacketDeclaration,
        producer: String = "scopePayload",
        beforeRead: (ValueSite) -> Unit = {},
        observation: IntellijReadObservation = IntellijReadObservation.None,
    ): List<ValueFlowStep> = runBlocking {
        val adapter = IntellijValueFlowCompilerAdapter(observation)
        val seed =
            adapter.seed(
                project = project,
                current = fixture.lease,
                request = fixture.selection(owner, producer, declaration(producer)),
                model = fixture.model,
            )
        assertTrue("Producer admission prerequisite: $seed", seed is ValueProducerSeedRead.Seeded)
        val pending = ArrayDeque<ValueSite>()
        pending.add((seed as ValueProducerSeedRead.Seeded).seed.site)
        val visited = mutableSetOf<ValueSiteIdentity>()
        val steps = mutableListOf<ValueFlowStep>()
        while (pending.isNotEmpty()) {
            val site = pending.removeFirst()
            if (!visited.add(site.identity)) continue
            assertTrue("Fixture route exceeds its finite limit", visited.size < MAX_SITES)
            beforeRead(site)
            val read =
                adapter.read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(site, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
            assertTrue("Native read prerequisite at ${site.range}: $read", read is ValueFlowRead.Observed)
            val step = (read as ValueFlowRead.Observed).step
            steps.add(step)
            step.transfers.forEach { pending.add(it.target) }
            step.reviewedTransfers.forEach { pending.add(it.target) }
        }
        steps
    }

    private fun declaration(name: String) = PacketDeclaration(CONTROLS, "", name)

    private companion object {
        const val CONTROLS = "src/main/kotlin/fixture/controls/ReviewedScopeControls.kt"
        const val MAP_VARIANTS = "src/main/kotlin/fixture/controls/ReviewedMapVariants.kt"
        const val MAX_SITES = 64
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
