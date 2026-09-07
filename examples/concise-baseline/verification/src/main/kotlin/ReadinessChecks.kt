package kast.baseline.verification

import kast.baseline.coordinator.*
import kast.baseline.model.*
import kast.baseline.read.*

fun readinessChecks(): List<String> {
    val checks = mutableListOf<String>()
    fun prove(name: String, predicate: Boolean) { check(predicate) { name }; checks += name }
    val workspace = WorkspaceId.parse("sample").valueOrFail()
    val coordinates = Coordinates(workspace, Generation.parse(1).valueOrFail())
    val budget = PreparationBudget.parse(1000).valueOrFail()
    val calls = mutableListOf<String>()
    val start = StartCoordinator(RuntimeStart { calls += "start"; Outcome.Complete(it) },
        IndexPreparation { _, _ -> calls += "index"; Outcome.Complete(coordinates) })
    prove("start-establishes-indexed-readiness", start.start(workspace, budget) is Outcome.Complete)
    prove("one-readiness-order", calls == listOf("start", "index"))
    val selector = Selector(coordinates)
    val read = ReadCoordinator(SemanticRead { calls += "read"; Outcome.Complete(it.coordinates) })
    calls.clear()
    prove("cheap-read-remains-read-only", read.read(selector) == Outcome.Complete(coordinates))
    prove("cheap-read-does-not-prepare", calls == listOf("read"))
    val traversal = TraversalCoordinator(read, TopologyPreparation { current, admittedBudget ->
        calls += "topology"
        check(admittedBudget == budget)
        Outcome.Complete(current)
    })
    calls.clear()
    prove("traversal-prepares-without-public-build-call", traversal.prepare(selector, budget) == Outcome.Complete(coordinates))
    prove("traversal-checks-generation-before-build", calls == listOf("read", "topology"))
    val absent = ReadCoordinator(SemanticRead { Outcome.Rejected(Failure.RUNTIME_ABSENT) })
    prove("read-never-starts-an-absent-runtime", absent.read(selector) == Outcome.Rejected(Failure.RUNTIME_ABSENT))
    val successor = coordinates.copy(generation = Generation.parse(2).valueOrFail())
    val stale = ReadCoordinator(SemanticRead { Outcome.Complete(successor) })
    var builds = 0
    val staleTraversal = TraversalCoordinator(stale, TopologyPreparation { current, _ -> builds++; Outcome.Complete(current) })
    prove("stale-selector-never-rebinds", staleTraversal.prepare(selector, budget) == Outcome.Rejected(Failure.GENERATION_CHANGED))
    prove("stale-selector-does-not-build", builds == 0)
    val changed = TraversalCoordinator(read, TopologyPreparation { _, _ -> Outcome.Complete(successor) })
    prove("generation-change-during-build-rejects", changed.prepare(selector, budget) == Outcome.Rejected(Failure.GENERATION_CHANGED))
    val qualified = StartCoordinator(RuntimeStart { Outcome.Complete(it) },
        IndexPreparation { _, _ -> Outcome.Qualified(coordinates, Qualification.RESULT_LIMIT) })
    prove("qualified-indexing-is-not-ready", qualified.start(workspace, budget) == Outcome.Rejected(Failure.PREPARATION_REJECTED))
    val recovery = StartCoordinator(RuntimeStart { Outcome.Rejected(Failure.RECOVERY_REQUIRED) },
        IndexPreparation { _, _ -> error("must not synchronize unresolved recovery") })
    prove("recovery-is-not-silent-repair", recovery.start(workspace, budget) == Outcome.Rejected(Failure.RECOVERY_REQUIRED))
    return checks
}
