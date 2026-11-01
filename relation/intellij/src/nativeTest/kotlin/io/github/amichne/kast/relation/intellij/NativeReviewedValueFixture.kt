package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import junit.framework.TestCase.fail
import kotlinx.coroutines.runBlocking

abstract class NativeReviewedValueFixture : NativePacketValueFixtureTest() {
    protected fun fixture(consumer: Boolean = true, paths: List<String> = listOf(CONTROLS)): NativePacketValueFixture =
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

    private fun reviewedLibraryPath(name: String): Path =
        if (name == "kotlin-stdlib-2.4.21.jar")
            System.getProperty("java.class.path").split(java.io.File.pathSeparator).map(Path::of).single {
                it.fileName.toString() == name
            }
        else Path.of(checkNotNull(javaClass.getResource("/reviewed-libraries/$name")).toURI())

    protected fun trace(
        fixture: NativePacketValueFixture,
        owner: PacketDeclaration,
        producer: PacketDeclaration = declaration("scopePayload"),
        beforeRead: (ValueSite) -> Unit = {},
        observation: IntellijReadObservation = IntellijReadObservation.None,
    ): List<ValueFlowStep> =
        project.traceScope(
            fixture = fixture,
            owner = owner,
            producer = producer,
            options = ReviewedTraceOptions(fixture.budget, beforeRead, observation),
        )

    protected fun trace(
        fixture: NativePacketValueFixture,
        owner: PacketDeclaration,
        budget: RelationBudget,
        producer: PacketDeclaration = declaration("scopePayload"),
    ): List<ValueFlowStep> =
        project.traceScope(
            fixture = fixture,
            owner = owner,
            producer = producer,
            options = ReviewedTraceOptions(budget),
        )

    protected fun composedBudget(fixture: NativePacketValueFixture): RelationBudget =
        fixture.budget.copy(returnedBytes = RelationByteLimit.parse(COMPOSED_RETURNED_BYTES).nativeRefined())

    protected fun declaration(name: String) = PacketDeclaration(CONTROLS, "", name)

    protected companion object {
        const val CONTROLS = "src/main/kotlin/fixture/controls/ReviewedScopeControls.kt"
        const val MAP_VARIANTS = "src/main/kotlin/fixture/controls/ReviewedMapVariants.kt"
        const val PIPELINE_VARIANTS = "src/main/kotlin/fixture/controls/ReviewedPipelineVariants.kt"
    }
}

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

private const val MAX_SITES = 64

private fun Project.traceScope(
    fixture: NativePacketValueFixture,
    owner: PacketDeclaration,
    producer: PacketDeclaration,
    options: ReviewedTraceOptions,
): List<ValueFlowStep> = runBlocking {
    val adapter = IntellijValueFlowCompilerAdapter(options.observation)
    val seed =
        adapter.seed(
            project = this@traceScope,
            current = fixture.lease,
            request = fixture.selection(owner, producer.name, producer),
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
        options.beforeRead(site)
        val read =
            adapter.read(
                project = this@traceScope,
                current = fixture.lease,
                request = ValueFlowRequest(site, options.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                model = fixture.model,
            )
        if (read !is ValueFlowRead.Observed)
            fail(reviewedReadFailure(site = site, read = read, budget = options.budget, previous = steps))
        val step = (read as ValueFlowRead.Observed).step
        if (step.obligations.any { it.cause == ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED })
            println(reviewedReadFailure(site = site, read = read, budget = options.budget, previous = steps))
        steps.add(step)
        step.transfers.forEach { pending.add(it.target) }
        step.reviewedTransfers.forEach { pending.add(it.target) }
    }
    steps
}

private data class ReviewedTraceOptions(
    val budget: RelationBudget,
    val beforeRead: (ValueSite) -> Unit = {},
    val observation: IntellijReadObservation = IntellijReadObservation.None,
)

private const val COMPOSED_RETURNED_BYTES = 8_388_608L
