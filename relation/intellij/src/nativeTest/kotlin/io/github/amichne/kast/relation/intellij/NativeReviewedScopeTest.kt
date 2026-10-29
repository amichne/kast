package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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

    private fun fixture(): NativePacketValueFixture =
        packetFixture(
            listOf(CONTROLS),
            configureCompiler = {
                ModuleRootModificationUtil.updateModel(module) { model ->
                    for (library in model.moduleLibraryTable.libraries) model.moduleLibraryTable.removeLibrary(library)
                    val library = model.moduleLibraryTable.createLibrary("reviewed-scope-2.3.0").modifiableModel
                    for ((name, kind, hash) in
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
                        )) {
                        val source = Path.of(checkNotNull(javaClass.getResource("/reviewed-libraries/$name")).toURI())
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

    private fun trace(
        fixture: NativePacketValueFixture,
        owner: PacketDeclaration,
        producer: String = "scopePayload",
    ): List<ValueFlowStep> = runBlocking {
        val adapter = IntellijValueFlowCompilerAdapter()
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
        const val MAX_SITES = 64
    }
}
