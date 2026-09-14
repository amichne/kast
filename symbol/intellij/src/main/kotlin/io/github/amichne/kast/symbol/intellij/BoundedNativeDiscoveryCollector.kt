package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.NavigationItem
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.candidateOrder
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualifications
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination

internal class BoundedNativeDiscoveryCollector(
    private val compiledScope: CompiledIntellijSearchScope,
    private val request: SymbolDiscoveryRequest,
    private val itemFile: IntellijDiscoveryItemFile,
    private val projector: IntellijDiscoveryCandidateProjector,
    private val itemAdmission: IntellijDiscoveryItemAdmissionPolicy,
    private val itemCompilerKind: IntellijDiscoveryItemCompilerKind,
    private val itemPackage: IntellijDiscoveryItemPackage,
    private val environmentState: () -> IntellijDiscoveryEnvironmentState,
    private val cancellationCheck: () -> Unit,
    private val clock: IntellijDiscoveryNanoClock,
    private val observation: IntellijReadObservation,
) {
    private val startedAt = clock.now()
    private val candidates = linkedSetOf<SymbolDiscoveryCandidate>()
    private val qualifications = linkedSetOf<SymbolDiscoveryQualification>()
    var contributor: IntellijReadContributor = IntellijReadContributor.NONE
    private var encodedBytes = 0L
    private var workUnits = 0L
    private var projectionNanoseconds = 0L
    var halted: Boolean = false
        private set

    fun observe(): Boolean {
        cancellationCheck()
        when (environmentState()) {
            IntellijDiscoveryEnvironmentState.DUMB -> {
                qualifyAndHalt(SymbolDiscoveryQualification.DUMB_MODE_TRANSITION)
                return false
            }
            IntellijDiscoveryEnvironmentState.DISPOSED -> {
                qualifyAndHalt(SymbolDiscoveryQualification.PROVIDER_FAILURE)
                return false
            }
            IntellijDiscoveryEnvironmentState.READY -> Unit
        }
        if (elapsedSince(startedAt) >= request.elapsedLimitNanoseconds().value) {
            qualifyAndHalt(SymbolDiscoveryQualification.TIME_LIMIT_REACHED)
            return false
        }
        return true
    }

    private fun admitWork(): Boolean {
        if (!observe()) return false
        if (workUnits >= request.budget.resources.workUnitLimit.value) {
            observation.terminated(IntellijReadTermination.WORK_LIMIT, contributor)
            qualifyAndHalt(SymbolDiscoveryQualification.WORK_LIMIT_REACHED)
            return false
        }
        workUnits += 1L
        return true
    }

    fun accept(item: NavigationItem): Boolean {
        if (!observe()) {
            return false
        }
        val file =
            when (val itemFileResult = itemFile.find(item)) {
                is IntellijDiscoveryItemFileResult.Found -> itemFileResult.file
                IntellijDiscoveryItemFileResult.Unsupported -> {
                    qualify(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
                    return true
                }
            }
        if (!compiledScope.nativeScope.contains(file)) {
            observation.count(IntellijReadCounter.SCOPE_FILTERED, contributor)
            return true
        }
        when (
            request.constraints.admit(
                item,
                file.path,
                request.scope.lease.workspaceRoot.value,
                compiledScope,
                itemCompilerKind,
                itemPackage,
            )
        ) {
            IntellijDiscoveryItemAdmission.ADMITTED -> Unit
            IntellijDiscoveryItemAdmission.FILTERED -> {
                observation.count(IntellijReadCounter.SCOPE_FILTERED, contributor)
                return true
            }
            IntellijDiscoveryItemAdmission.UNSUPPORTED -> {
                qualify(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
                return true
            }
        }
        when (itemAdmission.admit(item)) {
            IntellijDiscoveryItemAdmission.ADMITTED -> Unit
            IntellijDiscoveryItemAdmission.FILTERED -> {
                observation.count(IntellijReadCounter.SCOPE_FILTERED, contributor)
                return true
            }
            IntellijDiscoveryItemAdmission.UNSUPPORTED -> {
                qualify(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
                return true
            }
        }
        return project(item, file)
    }

    private fun project(item: NavigationItem, file: com.intellij.openapi.vfs.VirtualFile): Boolean {
        val projectionStartedAt = clock.now()
        val projected = projector.project(request, item, file)
        projectionNanoseconds =
            saturatedAdd(
                projectionNanoseconds,
                elapsedSince(projectionStartedAt),
            )
        val candidate =
            when (projected) {
                is Refinement.Refined -> projected.value
                is Refinement.Rejected -> {
                    qualify(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
                    return true
                }
            }
        if (candidate in candidates) {
            return true
        }
        if (!admitWork()) {
            return false
        }
        if (candidates.size >= request.budget.resources.resultLimit.value) {
            qualifyAndHalt(SymbolDiscoveryQualification.RESULT_LIMIT_REACHED)
            return false
        }
        val candidateBytes = candidate.projectedUtf8Size()
        if (candidateBytes.value > request.budget.returnedBytes.value - encodedBytes) {
            qualifyAndHalt(SymbolDiscoveryQualification.BYTE_LIMIT_REACHED)
            return false
        }
        candidates += candidate
        observation.count(IntellijReadCounter.CANDIDATES_PROJECTED, contributor)
        encodedBytes += candidateBytes.value
        return true
    }

    fun qualify(qualification: SymbolDiscoveryQualification) {
        qualifications += qualification
        if (qualification != SymbolDiscoveryQualification.WORK_LIMIT_REACHED) {
            observation.terminated(qualification.observedTermination(), contributor)
        }
    }

    fun qualifyAndHalt(qualification: SymbolDiscoveryQualification) {
        qualify(qualification)
        halted = true
    }

    fun finish(): IntellijNativeDiscoveryExecution {
        if (elapsedSince(startedAt) >= request.elapsedLimitNanoseconds().value) {
            qualify(SymbolDiscoveryQualification.TIME_LIMIT_REACHED)
        }
        val totalNanoseconds = elapsedSince(startedAt)
        val timings =
            SymbolDiscoveryTimings(
                nativeQuery = (totalNanoseconds - projectionNanoseconds).coerceAtLeast(0L).elapsedMeasure(),
                projection = projectionNanoseconds.elapsedMeasure(),
            )
        val orderedCandidates = candidates.sortedWith(request.candidateOrder())
        val batch =
            when (
                val creation =
                    SymbolDiscoveryBatch.create(
                        request = request,
                        candidates = orderedCandidates,
                        encodedBytes = encodedBytes.byteMeasure(),
                        examinedWorkUnits = workUnits.workMeasure(),
                        timings = timings,
                    )
            ) {
                is Refinement.Refined -> creation.value
                is Refinement.Rejected ->
                    return IntellijNativeDiscoveryExecution.Rejected(
                        IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT
                    )
            }
        val outcome =
            if (qualifications.isEmpty()) {
                observation.terminated(IntellijReadTermination.COMPLETE, contributor)
                SymbolDiscoveryOutcome.Complete(batch)
            } else {
                val typedQualifications =
                    when (val refinement = SymbolDiscoveryQualifications.from(qualifications)) {
                        is Refinement.Refined -> refinement.value
                        is Refinement.Rejected ->
                            return IntellijNativeDiscoveryExecution.Rejected(
                                IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT
                            )
                    }
                SymbolDiscoveryOutcome.Qualified(batch, typedQualifications)
            }
        return IntellijNativeDiscoveryExecution.Produced(outcome)
    }

    private fun elapsedSince(start: Long): Long = (clock.now() - start).coerceAtLeast(0L)
}

private fun Long.byteMeasure(): SymbolDiscoveryByteCount =
    when (val parsed = SymbolDiscoveryByteCount.parse(this)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("non-negative byte accumulator rejected")
    }

private fun Long.workMeasure(): SymbolDiscoveryWorkCount =
    when (val parsed = SymbolDiscoveryWorkCount.parse(this)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("non-negative work accumulator rejected")
    }

private fun Long.elapsedMeasure(): SymbolDiscoveryElapsedNanoseconds =
    when (val parsed = SymbolDiscoveryElapsedNanoseconds.parse(this)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("non-negative elapsed accumulator rejected")
    }

private fun saturatedAdd(
    left: Long,
    right: Long,
): Long = if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

internal const val MAX_NATIVE_DISCOVERY_NAMES = 10_000
internal const val MAX_NATIVE_DISCOVERY_CANDIDATES = 10_000

internal fun String.observedContributor(): IntellijReadContributor =
    when (this) {
        "org.jetbrains.kotlin.idea.goto.KotlinGotoClassContributor" -> IntellijReadContributor.KOTLIN_CLASS
        "org.jetbrains.kotlin.idea.goto.KotlinGotoClassSymbolContributor" -> IntellijReadContributor.KOTLIN_CLASS_SYMBOL
        "org.jetbrains.kotlin.idea.goto.KotlinGotoFunctionSymbolContributor" ->
            IntellijReadContributor.KOTLIN_FUNCTION_SYMBOL
        "org.jetbrains.kotlin.idea.goto.KotlinGotoPropertySymbolContributor" ->
            IntellijReadContributor.KOTLIN_PROPERTY_SYMBOL
        "org.jetbrains.kotlin.idea.goto.KotlinGotoTypeAliasContributor" -> IntellijReadContributor.KOTLIN_TYPE_ALIAS
        else -> IntellijReadContributor.OTHER
    }

private fun SymbolDiscoveryQualification.observedTermination(): IntellijReadTermination =
    when (this) {
        SymbolDiscoveryQualification.WORK_LIMIT_REACHED -> IntellijReadTermination.WORK_LIMIT
        SymbolDiscoveryQualification.TIME_LIMIT_REACHED -> IntellijReadTermination.TIME_LIMIT
        SymbolDiscoveryQualification.RESULT_LIMIT_REACHED -> IntellijReadTermination.RESULT_LIMIT
        SymbolDiscoveryQualification.BYTE_LIMIT_REACHED -> IntellijReadTermination.BYTE_LIMIT
        SymbolDiscoveryQualification.UNSUPPORTED_ITEM -> IntellijReadTermination.UNSUPPORTED_ITEM
        SymbolDiscoveryQualification.UNSCOPED_PROVIDER -> IntellijReadTermination.UNSCOPED_PROVIDER
        SymbolDiscoveryQualification.PROVIDER_FAILURE -> IntellijReadTermination.PROVIDER_FAILURE
        SymbolDiscoveryQualification.DUMB_MODE_TRANSITION -> IntellijReadTermination.INDEXING
        SymbolDiscoveryQualification.EXACT_DEFINITION_UNAVAILABLE ->
            IntellijReadTermination.EXACT_REFINEMENT_UNAVAILABLE
    }
