package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.IntellijReadUnexpectedFailure
import kotlinx.coroutines.runBlocking

/** Records actual reads, never interpreting a rejected read as an observed empty result. */
internal class ImpactBoundaryTrace(private val project: Project, private val fixture: NativePacketValueFixture) {
    fun run(request: ValueProducerSeedRequest): RecordedRun = runBlocking {
        val observation = RecordedObservation()
        val adapter = IntellijValueFlowCompilerAdapter(observation)
        val seed = adapter.seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        val seedEnteredPhases = observation.enteredPhases.toSet()
        val seedTerminations = observation.terminations.toSet()
        val reads =
            if (seed is ValueProducerSeedRead.Seeded) reads(adapter, observation, seed.seed.site) else emptyList()
        RecordedRun(
                seed = seed,
                seedEnteredPhases = seedEnteredPhases,
                seedTerminations = seedTerminations,
                reads = reads,
                failures = observation.failures.toList(),
            )
            .also { println(it.summary()) }
    }

    // Breadth-first over observed targets; the first non-observed read ends collection.
    private suspend fun reads(
        adapter: IntellijValueFlowCompilerAdapter,
        observation: RecordedObservation,
        start: ValueSite,
    ): List<RecordedRead> {
        val reads = mutableListOf<RecordedRead>()
        val pending = ArrayDeque<ValueSite>().apply { add(start) }
        val visited = linkedSetOf<ValueSiteIdentity>()
        val budget = fixture.budget.copy(returnedBytes = RelationByteLimit.parse(16_000_000L).nativeRefined())
        while (pending.isNotEmpty()) {
            val site = pending.removeFirst()
            if (!visited.add(site.identity)) continue
            check(visited.size <= MAX_SITES) { "Fixture exceeded its finite site bound" }
            observation.enteredPhases.clear()
            observation.terminations.clear()
            val read =
                adapter.read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(site, budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
            reads +=
                RecordedRead(
                    source = site,
                    read = read,
                    enteredPhases = observation.enteredPhases.toSet(),
                    terminations = observation.terminations.toSet(),
                )
            when (read) {
                is ValueFlowRead.Observed -> read.step.transfers.forEach { pending.addLast(it.target) }
                is ValueFlowRead.Rejected,
                is ValueFlowRead.ContractRejected,
                is ValueFlowRead.Suspended -> pending.clear()
            }
        }
        return reads
    }

    private companion object {
        const val MAX_SITES = 64
    }
}

// Case-owned category sets establish neither event order nor phase completion.
private class RecordedObservation : IntellijReadObservation by IntellijReadObservation.None {
    val failures = mutableListOf<IntellijReadUnexpectedFailure>()
    val enteredPhases = linkedSetOf<IntellijReadPhase>()
    val terminations = linkedSetOf<RecordedTermination>()

    override fun phase(value: IntellijReadPhase) {
        enteredPhases += value
    }

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
        terminations += RecordedTermination(reason, contributor)
    }

    override fun unexpected(failure: IntellijReadUnexpectedFailure) {
        failures += failure
    }
}

internal data class RecordedTermination(val reason: IntellijReadTermination, val contributor: IntellijReadContributor)

internal data class RecordedRead(
    val source: ValueSite,
    val read: ValueFlowRead,
    val enteredPhases: Set<IntellijReadPhase>,
    val terminations: Set<RecordedTermination>,
)

internal data class RecordedRun(
    val seed: ValueProducerSeedRead,
    val seedEnteredPhases: Set<IntellijReadPhase>,
    val seedTerminations: Set<RecordedTermination>,
    val reads: List<RecordedRead>,
    val failures: List<IntellijReadUnexpectedFailure>,
) {
    fun summary(): String =
        "seed=${if (seed is ValueProducerSeedRead.Seeded) "seeded" else seed}; " +
            "seedEnteredPhases=$seedEnteredPhases; seedTerminations=$seedTerminations; reads=" +
            reads.joinToString { attempt ->
                val outcome =
                    when (val read = attempt.read) {
                        is ValueFlowRead.Observed ->
                            "observed:${read.step.terminal}; " +
                                "transfers=${read.step.transfers.map { it.kind }}; " +
                                "obligations=${read.step.obligations.map { it.cause }}"
                        is ValueFlowRead.Rejected -> "rejected:${read.cause}; work=${read.examinedWorkUnits.value}"
                        is ValueFlowRead.ContractRejected ->
                            "contractRejected:${read.cause}; work=${read.examinedWorkUnits.value}"
                        is ValueFlowRead.Suspended -> "suspended:${read.cause}"
                    }
                "${attempt.source.range} (${attempt.source.role.javaClass.simpleName}) $outcome; " +
                    "enteredPhases=${attempt.enteredPhases}; terminations=${attempt.terminations}"
            } +
            "; failures=$failures"
}
