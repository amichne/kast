package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QuerySourceFailure
import io.github.amichne.kast.query.contract.QueryTracePhase
import io.github.amichne.kast.source.contract.Containment
import io.github.amichne.kast.source.contract.DeclarationKind
import io.github.amichne.kast.source.contract.DeclarationKindSelection
import io.github.amichne.kast.source.contract.DeclarationSemanticIdentity
import io.github.amichne.kast.source.contract.EntityFilter
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceEntity
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceEntityName
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadContinuationState
import io.github.amichne.kast.source.contract.SourceReadLimitation
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.source.contract.SourceReadQualification
import io.github.amichne.kast.source.contract.SourceReadRejection
import io.github.amichne.kast.source.contract.SourceReadRequest
import io.github.amichne.kast.source.contract.SourceReadResult
import io.github.amichne.kast.source.contract.SourceReadScope
import io.github.amichne.kast.source.contract.SourceRegion
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.source.contract.TextProjection
import io.github.amichne.kast.source.contract.VisibilitySelection
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolExactRejection
import io.github.amichne.kast.symbol.contract.SymbolSelector

/** Direct structural membership admitted from the exact class anchor; no textual or package ownership inference. */
internal class QueryTraceMember
private constructor(
    val selection: SymbolDiscoverySelection,
    val selector: SourceSelector.Entity,
    val kind: CompilerSymbolKind,
) {
    companion object {
        fun admit(
            owner: SymbolSelector,
            region: SourceRegion,
            entity: SourceEntity,
        ): Refinement<QueryTraceMember, SourceReadRejection> {
            val declaration =
                entity as? SourceEntity.Declaration
                    ?: return Refinement.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
            val selection = (declaration.semanticIdentity as DeclarationSemanticIdentity.Candidate).selector.selection
            val kind =
                when (declaration.kind) {
                    DeclarationKind.FUNCTION -> CompilerSymbolKind.FUNCTION
                    DeclarationKind.PROPERTY -> CompilerSymbolKind.PROPERTY
                    DeclarationKind.CLASSLIKE,
                    DeclarationKind.CONSTRUCTOR,
                    DeclarationKind.TYPE_ALIAS -> return Refinement.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
                }
            val location =
                selection.candidate.location as? SymbolDiscoveryCandidateLocation.Declaration
                    ?: return Refinement.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
            val direct =
                declaration.nestingDepth.value == 0 &&
                    declaration.parentSelector.fingerprint == region.selector.fingerprint
            if (!direct || !selection.admitsOwner(owner, kind) || !selection.admitsEntity(location, entity, owner))
                return Refinement.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
            return Refinement.Refined(QueryTraceMember(selection, declaration.selector, kind))
        }
    }
}

/** Source remains the structural owner; the existing exact refinement owns compiler identity. */
internal class QueryTraceMembers(private val source: SourceReadOperations, private val stages: QueryReadStages) {
    suspend fun advance(
        task: PipelineTask.TraceTask,
        state: QueryExecutionState,
        tasks: ArrayDeque<PipelineTask>,
    ): Boolean =
        when (task) {
            is PipelineTask.TraceMembers -> read(task, state, tasks)
            is PipelineTask.TraceMember -> refine(task, state, tasks)
        }

    suspend fun read(
        task: PipelineTask.TraceMembers,
        state: QueryExecutionState,
        tasks: ArrayDeque<PipelineTask>,
    ): Boolean {
        val budget = state.indexedDiscoveryBudget() ?: return false
        val entityLimit = SourceEntityLimit.parse(minOf(1_000, budget.resources.resultLimit.value)).refined()
        val request = memberRequest(task, budget, entityLimit)
        val result = source.read(request)
        // Source pages expose no measured work. Charge the full transferred ceiling conservatively.
        state.consume(budget.resources.workUnitLimit.value)
        state.observeTime()
        return when (result) {
            is SourceReadResult.Rejected -> reject(task, result.reason, state, tasks)
            is SourceReadResult.Complete ->
                consumePage(
                    task,
                    MemberPage(result.snapshot, result.region, result.entities, null),
                    entityLimit,
                    state,
                    tasks,
                )
            is SourceReadResult.Qualified ->
                consumePage(
                    task,
                    MemberPage(result.snapshot, result.region, result.entities, result.qualification),
                    entityLimit,
                    state,
                    tasks,
                )
        }
    }

    private fun memberRequest(
        task: PipelineTask.TraceMembers,
        budget: io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget,
        entityLimit: SourceEntityLimit,
    ): SourceReadRequest =
        SourceReadRequest(
            SourceReadAnchor.Symbol(task.owner.selector),
            RegionSelection.Anchor,
            EntitySelection.matching(
                    Containment.DIRECT,
                    listOf(
                        EntityFilter.Declarations(
                            DeclarationKindSelection.from(setOf(DeclarationKind.FUNCTION, DeclarationKind.PROPERTY))
                                .refined(),
                            VisibilitySelection.Any,
                        )
                    ),
                )
                .refined(),
            TextProjection.None,
            entityLimit,
            SourceTextByteLimit.parse(budget.returnedBytes.value).refined(),
            task.page,
            budget.resources,
        )

    private fun consumePage(
        task: PipelineTask.TraceMembers,
        page: MemberPage,
        limit: SourceEntityLimit,
        state: QueryExecutionState,
        tasks: ArrayDeque<PipelineTask>,
    ): Boolean {
        val owner = task.owner.selector
        if (!page.admits(owner) || page.entities.size > limit.value)
            return reject(task, SourceReadRejection.CONTRACT_VIOLATION, state, tasks)
        val members = mutableListOf<QueryTraceMember>()
        for (entity in page.entities) when (val admitted = QueryTraceMember.admit(owner, page.region, entity)) {
            is Refinement.Refined -> members += admitted.value
            is Refinement.Rejected -> return reject(task, admitted.failure, state, tasks)
        }
        tasks.removeFirst()
        page.qualification?.let { retainContinuation(task, it, state, tasks) }
        members.asReversed().forEach { tasks.addFirst(PipelineTask.TraceMember(it, task.stage)) }
        return true
    }

    private fun retainContinuation(
        task: PipelineTask.TraceMembers,
        qualification: SourceReadQualification,
        state: QueryExecutionState,
        tasks: ArrayDeque<PipelineTask>,
    ) {
        val continuation = qualification.continuation
        if (
            continuation !is SourceReadContinuationState.Available ||
                qualification.limitations.any { it !in recoverableSourceLimits }
        ) {
            state.failure(
                QueryItemFailure.Source(task.owner.selector, QuerySourceFailure.EnumerationIncomplete(qualification))
            )
            state.limit(QueryLimitation.SOURCE_INCOMPLETE)
        }
        if (continuation is SourceReadContinuationState.Available) {
            if ((task.page as? SourceReadPage.Continue)?.continuation == continuation.continuation) {
                state.failure(
                    QueryItemFailure.Source(
                        task.owner.selector,
                        QuerySourceFailure.Rejected(SourceReadRejection.CONTINUATION_REQUEST_MISMATCH),
                    )
                )
                state.limit(QueryLimitation.SOURCE_INCOMPLETE)
            } else
                tasks.addFirst(
                    task.copy(page = SourceReadPage.Continue(continuation.continuation), qualification = qualification)
                )
        }
    }

    suspend fun refine(
        task: PipelineTask.TraceMember,
        state: QueryExecutionState,
        tasks: ArrayDeque<PipelineTask>,
    ): Boolean {
        if (!state.consumeUnit()) return false
        val symbols = stages.refine(task.member.selection, null, state)
        tasks.removeFirst()
        for (symbol in symbols.asReversed()) {
            val member = task.member
            if (!member.admitsExact(symbol.selector)) {
                state.failure(
                    QueryItemFailure.Refinement(member.selection, SymbolExactRejection.COMPILER_CONTRACT_VIOLATION)
                )
                state.limit(QueryLimitation.REFINEMENT_INCOMPLETE)
            } else tasks.addFirst(PipelineTask.Symbol(symbol, task.stage.copy(phase = QueryTracePhase.SEED)))
        }
        return true
    }

    private fun reject(
        task: PipelineTask.TraceMembers,
        reason: SourceReadRejection,
        state: QueryExecutionState,
        tasks: ArrayDeque<PipelineTask>,
    ): Boolean {
        state.failure(QueryItemFailure.Source(task.owner.selector, QuerySourceFailure.Rejected(reason)))
        state.limit(QueryLimitation.SOURCE_INCOMPLETE)
        tasks.removeFirst()
        return true
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Internal member selection invariant: $failure")
    }

private val recoverableSourceLimits =
    setOf(
        SourceReadLimitation.ENTITY_LIMIT_REACHED,
        SourceReadLimitation.WORK_LIMIT_REACHED,
        SourceReadLimitation.TIME_LIMIT_REACHED,
    )

private fun SymbolDiscoverySelection.admitsOwner(owner: SymbolSelector, kind: CompilerSymbolKind): Boolean {
    val authority = lease == owner.lease && scope == owner.scope
    val constraintsMatch = constraints.copy(declarationKinds = null) == owner.constraints.copy(declarationKinds = null)
    return authority && constraintsMatch && constraints.declarationKinds?.values == setOf(kind)
}

private fun SymbolDiscoverySelection.admitsEntity(
    location: SymbolDiscoveryCandidateLocation.Declaration,
    entity: SourceEntity,
    owner: SymbolSelector,
): Boolean =
    location.file == owner.file &&
        location.offset.value == entity.selector.range.startInclusive.value &&
        (entity.selector.name as? SourceEntityName.Present)?.value == candidate.name.value

private fun QueryTraceMember.admitsExact(exact: SymbolSelector): Boolean {
    val authority =
        exact.lease == selection.lease && exact.scope == selection.scope && exact.constraints == selection.constraints
    val range =
        exact.file == selector.snapshot.file &&
            exact.range.startInclusive == selector.range.startInclusive.value &&
            exact.range.endExclusive == selector.range.endExclusive.value
    val identity = exact.name == selection.candidate.name && exact.kind == kind
    return authority && range && identity
}

private data class MemberPage(
    val snapshot: SourceSnapshot,
    val region: SourceRegion,
    val entities: List<SourceEntity>,
    val qualification: SourceReadQualification?,
) {
    fun admits(owner: SymbolSelector): Boolean {
        val authority =
            snapshot.lease == owner.lease &&
                snapshot.file == owner.file &&
                snapshot.readScope == SourceReadScope.Constrained(owner.scope, owner.constraints)
        val range =
            region.kind == SourceRegionKind.DECLARATION &&
                region.selector.range.startInclusive.value == owner.range.startInclusive &&
                region.selector.range.endExclusive.value == owner.range.endExclusive
        return authority && range
    }
}
