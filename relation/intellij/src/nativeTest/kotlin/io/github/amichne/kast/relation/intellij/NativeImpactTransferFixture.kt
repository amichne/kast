package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteIdentity
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Incident-shaped native fixtures for the reported transfer gaps. Expected sites come from independently marked fixture
 * expressions, never from traversal output. The trace records every read and never treats a rejection as exhaustion.
 */
abstract class NativeImpactTransferFixture : NativeReviewedValueFixture() {
    protected fun assertSupported(function: String, run: TransferRun) {
        assertTrue("$function: native reads were rejected: ${run.summary()}", run.rejected.isEmpty())
        assertTrue("$function: required obligations remain: ${run.summary()}", run.obligations.isEmpty())
    }

    protected fun fixture(vararg paths: String): NativePacketValueFixture =
        fixture(paths = paths.toList(), readSource = { path -> resource(path) })

    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/impact-transfers/${path.substringAfterLast('/')}")) {
                "Missing fixture resource: $path"
            }
            .use { it.readBytes() }

    protected fun trace(
        fixture: NativePacketValueFixture,
        owner: PacketDeclaration,
        producer: String = "source",
        producerPath: String = owner.path,
    ): TransferRun = runBlocking {
        val adapter = IntellijValueFlowCompilerAdapter()
        val seed =
            adapter.seed(
                project = project,
                current = fixture.lease,
                request = fixture.selection(owner, producer, PacketDeclaration(producerPath, "", producer)),
                model = fixture.model,
            )
        assertTrue("Seed not admitted: $seed", seed is ValueProducerSeedRead.Seeded)
        val seedSite = (seed as ValueProducerSeedRead.Seeded).seed.site
        assertEquals(fixture.markedExpression(owner, "seed").anchor, seedSite.range)
        val budget = fixture.budget.copy(returnedBytes = RelationByteLimit.parse(RETURNED_BYTES).nativeRefined())
        val pending = ArrayDeque<ValueSite>().apply { add(seedSite) }
        val visited = linkedSetOf<ValueSiteIdentity>()
        val steps = mutableListOf<ValueFlowStep>()
        val rejected = mutableListOf<Pair<ValueSite, ValueFlowRead>>()
        while (pending.isNotEmpty()) {
            val site = pending.removeFirst()
            if (!visited.add(site.identity)) continue
            check(visited.size <= MAX_SITES) { "Fixture exceeded its finite site bound" }
            when (
                val read =
                    adapter.read(
                        project = project,
                        current = fixture.lease,
                        request = ValueFlowRequest(site, budget, RelationSearchBoundary.RETAINED_SUBJECT),
                        model = fixture.model,
                    )
            ) {
                is ValueFlowRead.Observed -> {
                    steps += read.step
                    read.step.transfers.forEach { pending.addLast(it.target) }
                    read.step.reviewedTransfers.forEach { pending.addLast(it.target) }
                }
                is ValueFlowRead.Suspended -> {
                    steps += read.step
                    rejected += site to read
                }
                is ValueFlowRead.Rejected,
                is ValueFlowRead.ContractRejected -> rejected += site to read
            }
        }
        TransferRun(steps, rejected)
    }

    protected class TransferRun(val steps: List<ValueFlowStep>, val rejected: List<Pair<ValueSite, ValueFlowRead>>) {
        val sites: List<ValueSite> = steps.map { it.source } + rejected.map { it.first }
        val obligations: List<ValueFlowObligation> = steps.flatMap { it.obligations }

        fun summary(): String =
            steps.joinToString(prefix = "steps=[", postfix = "]") { step ->
                "${step.source.range}:${step.source.role.javaClass.simpleName}:" +
                    "${step.source.executionOwner.context.javaClass.simpleName}" +
                    "->${step.transfers.map { "${it.kind}@${it.target.range}" }}" +
                    "+${step.reviewedTransfers.map { "reviewed@${it.target.range}" }}" +
                    (if (step.obligations.isEmpty()) ""
                    else " obligations=${step.obligations.map { "${it.cause}@${it.site.range}/${it.evidence}" }}")
            } + " rejected=${rejected.map { "${it.first.range}:${it.second.javaClass.simpleName}" }}"
    }

    protected companion object {
        const val NULL_GUARD = "src/main/kotlin/fixture/impact/nullguard/NullGuard.kt"
        const val EQUALITY_CONTROL = "src/main/kotlin/fixture/impact/equalitycontrol/EqualityControl.kt"
        const val EQUALITY_SINK = "src/main/kotlin/fixture/impact/equalitysink/EqualitySink.kt"
        const val SUBJECT_SUPPORT = "src/main/kotlin/fixture/impact/subject/SubjectSupport.kt"
        const val SUBJECT_INLINE = "src/main/kotlin/fixture/impact/subject/SubjectInline.kt"
        const val SUBJECT_HOISTED = "src/main/kotlin/fixture/impact/subject/SubjectHoisted.kt"
        const val SUBJECT_SHADOWED = "src/main/kotlin/fixture/impact/subject/SubjectShadowed.kt"
        const val CAPTURE = "src/main/kotlin/fixture/impact/capture/Capture.kt"
        const val CAPTURE_CONTROLS = "src/main/kotlin/fixture/impact/capture/CaptureControls.kt"
        const val MAX_SITES = 128
        const val RETURNED_BYTES = 16_000_000L
    }
}

internal fun io.github.amichne.kast.relation.contract.ValueAccessPath.fieldNames(): List<String> =
    segments.map { segment ->
        when (segment) {
            is ValuePathSegment.Field -> segment.field.declaration.name.value
            else -> segment.javaClass.simpleName
        }
    }

internal fun io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange.covers(
    other: io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
): Boolean = startInclusive <= other.startInclusive && other.endExclusive <= endExclusive

/** An obligation belongs to a marked expression through its site or its bounded expression evidence. */
internal fun ValueFlowObligation.within(
    anchor: io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
): Boolean =
    anchor.covers(site.range) ||
        (evidence as? io.github.amichne.kast.relation.contract.ValueFlowObligationEvidence.Expression)?.range?.let {
            anchor.covers(it)
        } == true
