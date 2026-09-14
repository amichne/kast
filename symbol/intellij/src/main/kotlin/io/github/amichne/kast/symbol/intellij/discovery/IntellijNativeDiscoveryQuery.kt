package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.symbol.contract.relevance
import io.github.amichne.kast.symbol.contract.SymbolNameRelevance
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.util.concurrent.CancellationException

internal enum class IntellijDiscoveryEnvironmentState {
    READY,
    DUMB,
    DISPOSED,
}

internal enum class IntellijNativeDiscoveryRejection {
    DUMB_MODE,
    PROJECT_DISPOSED,
    NO_NATIVE_PROVIDERS,
    INTERNAL_INVARIANT,
}

internal sealed interface IntellijNativeDiscoveryExecution {
    data class Produced(val outcome: SymbolDiscoveryOutcome) : IntellijNativeDiscoveryExecution

    data class Rejected(val reason: IntellijNativeDiscoveryRejection) : IntellijNativeDiscoveryExecution
}

fun interface IntellijReadNanoClock {
    /** Returns a monotonic nanosecond observation at the native-read effect boundary. */
    fun now(): Long
}

internal typealias IntellijDiscoveryNanoClock = IntellijReadNanoClock

internal object SystemIntellijDiscoveryNanoClock : IntellijReadNanoClock {
    override fun now(): Long = System.nanoTime()
}

internal class IntellijNativeDiscoveryQuery(
    private val itemFile: IntellijDiscoveryItemFile = IntellijPsiDiscoveryItemFile,
    private val projector: IntellijDiscoveryCandidateProjector = IntellijPsiDiscoveryCandidateProjector,
    private val itemAdmission: IntellijDiscoveryItemAdmissionPolicy = AdmitEveryIntellijDiscoveryItem,
    private val itemCompilerKind: IntellijDiscoveryItemCompilerKind = IntellijPsiDiscoveryItemCompilerKind,
    private val itemPackage: IntellijDiscoveryItemPackage = IntellijPsiDiscoveryItemPackage,
    private val environmentState: () -> IntellijDiscoveryEnvironmentState,
    private val cancellationCheck: () -> Unit,
    private val clock: IntellijDiscoveryNanoClock = SystemIntellijDiscoveryNanoClock,
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
    private val limits: ReadLimits = ReadLimits.Default,
) {
    /**
     * Proof transition: CompiledIntellijSearchScope + SymbolDiscoveryRequest + native contributors to
     * IntellijNativeDiscoveryExecution.
     *
     * Establishes that each provider receives the compiled scope before native index work, each item is scope-checked
     * before PSI projection, and every returned candidate is detached, deterministic, record/byte/work/time bounded,
     * and authority-bound. [IntellijNativeDiscoveryRejection] and [SymbolDiscoveryQualification] are the closed
     * expected failure and partial-coverage states. Cancellation remains a platform cancellation and is propagated.
     * Live contributors, navigation items, virtual files, and scopes remain inside this request-local call.
     */
    fun discover(
        compiledScope: CompiledIntellijSearchScope,
        request: SymbolDiscoveryRequest,
        contributors: List<ChooseByNameContributor>,
        nameFilter: IdFilter = IdFilter.ACCEPT_ALL,
    ): IntellijNativeDiscoveryExecution {
        when (environmentState()) {
            IntellijDiscoveryEnvironmentState.DUMB ->
                return IntellijNativeDiscoveryExecution.Rejected(IntellijNativeDiscoveryRejection.DUMB_MODE)
            IntellijDiscoveryEnvironmentState.DISPOSED ->
                return IntellijNativeDiscoveryExecution.Rejected(IntellijNativeDiscoveryRejection.PROJECT_DISPOSED)
            IntellijDiscoveryEnvironmentState.READY -> Unit
        }

        val target = request.target
        if (target !is SymbolDiscoveryTarget.Name && target !is SymbolDiscoveryTarget.All) {
            return IntellijNativeDiscoveryExecution.Rejected(IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT)
        }
        val collector =
            BoundedNativeDiscoveryCollector(
                compiledScope = compiledScope,
                request = request,
                itemFile = itemFile,
                projector = projector,
                itemAdmission = itemAdmission,
                itemCompilerKind = itemCompilerKind,
                itemPackage = itemPackage,
                environmentState = environmentState,
                cancellationCheck = cancellationCheck,
                clock = clock,
                observation = observation,
            )
        if (compiledScope.population == IntellijScopePopulation.KNOWN_EMPTY) return collector.finish()
        if (contributors.isEmpty()) {
            return IntellijNativeDiscoveryExecution.Rejected(IntellijNativeDiscoveryRejection.NO_NATIVE_PROVIDERS)
        }

        contributors
            .sortedBy { it.javaClass.name }
            .forEach { contributor ->
                if (collector.halted) {
                    return@forEach
                }
                collector.contributor = contributor.javaClass.name.observedContributor()
                if (contributor !is ChooseByNameContributorEx) {
                    collector.qualify(SymbolDiscoveryQualification.UNSCOPED_PROVIDER)
                    return@forEach
                }
                try {
                    val matchingNames = linkedSetOf<String>()
                    contributor.processNames(
                        Processor { name ->
                            observation.count(IntellijReadCounter.NAMES_VISITED, collector.contributor)
                            if (!collector.observe()) {
                                return@Processor false
                            }
                            val matches =
                                when (target) {
                                    is SymbolDiscoveryTarget.All -> true
                                    is SymbolDiscoveryTarget.Name ->
                                        when (target.match) {
                                            SymbolDiscoveryMatch.FUZZY -> target.pattern.relevance(name) != SymbolNameRelevance.UNMATCHED
                                            SymbolDiscoveryMatch.EXACT_NAME -> name == target.pattern.value
                                        }
                                }
                            if (!matches) {
                                return@Processor true
                            }
                            if (
                                name !in matchingNames &&
                                    matchingNames.size >= limits[ReadLimitParameter.DISCOVERY_NAMES].value
                            ) {
                                observation.terminated(IntellijReadTermination.NAME_CAP, collector.contributor)
                                collector.qualify(SymbolDiscoveryQualification.WORK_LIMIT_REACHED)
                                return@Processor false
                            }
                            if (matchingNames.add(name))
                                observation.count(IntellijReadCounter.NAMES_MATCHED, collector.contributor)
                            !collector.halted
                        },
                        compiledScope.nativeScope,
                        nameFilter,
                    )
                    val pending = ArrayList<NavigationItem>()
                    var reachedCandidateLimit = false
                    for (name in matchingNames) {
                        if (collector.halted || reachedCandidateLimit) break
                        contributor.processElementsWithName(
                            name,
                            Processor { item ->
                                if (!collector.observe()) return@Processor false
                                if (collector.admit(item, inspectPackage = false) != IntellijDiscoveryItemAdmission.ADMITTED)
                                    return@Processor !collector.halted
                                if (pending.size >= limits[ReadLimitParameter.DISCOVERY_CANDIDATES].value) {
                                    reachedCandidateLimit = true
                                    observation.terminated(IntellijReadTermination.CANDIDATE_CAP, collector.contributor)
                                    collector.qualify(SymbolDiscoveryQualification.WORK_LIMIT_REACHED)
                                    return@Processor false
                                }
                                pending += item
                                observation.count(IntellijReadCounter.CANDIDATES_COLLECTED, collector.contributor)
                                true
                            },
                            FindSymbolParameters.wrap(name, compiledScope.nativeScope),
                        )
                    }
                    // Native provider callbacks have ended before projection or compiler refinement.
                    for (item in pending) {
                        if (!collector.accept(item)) break
                    }
                } catch (cancelled: ProcessCanceledException) {
                    throw cancelled
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IndexNotReadyException) {
                    collector.qualifyAndHalt(SymbolDiscoveryQualification.DUMB_MODE_TRANSITION)
                } catch (failure: RuntimeException) {
                    observation.unexpected(
                        io.github.amichne.kast.workspace.intellij.read.IntellijReadUnexpectedFailure.capture(
                            io.github.amichne.kast.workspace.intellij.read.IntellijReadStage.DISCOVERY,
                            failure,
                            limits,
                        )
                    )
                    collector.qualify(SymbolDiscoveryQualification.PROVIDER_FAILURE)
                }
            }
        return collector.finish()
    }

    /** Scoped file enumeration ends native callbacks before name/kind admission or PSI projection. */
    fun discoverDeclarations(
        compiledScope: CompiledIntellijSearchScope,
        request: SymbolDiscoveryRequest,
        process:
            (
                observe: () -> Boolean,
                qualify: (SymbolDiscoveryQualification) -> Unit,
                accept: (NavigationItem) -> Boolean,
            ) -> Boolean,
    ): IntellijNativeDiscoveryExecution {
        val target = request.target
        if (
            target !is SymbolDiscoveryTarget.All &&
                !(target is SymbolDiscoveryTarget.Name && target.match == SymbolDiscoveryMatch.FUZZY)
        )
            return IntellijNativeDiscoveryExecution.Rejected(IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT)
        return discoverIndexed(compiledScope, request, IntellijReadContributor.SCOPED_DECLARATIONS, process)
    }

    fun discoverAll(
        compiledScope: CompiledIntellijSearchScope,
        request: SymbolDiscoveryRequest,
        process: ((NavigationItem) -> Boolean) -> Boolean,
    ): IntellijNativeDiscoveryExecution =
        discoverDeclarations(compiledScope, request) { _, _, accept -> process(accept) }

    fun discoverExactName(
        compiledScope: CompiledIntellijSearchScope,
        request: SymbolDiscoveryRequest,
        process: (String, (NavigationItem) -> Boolean) -> Boolean,
    ): IntellijNativeDiscoveryExecution {
        val target =
            request.target as? SymbolDiscoveryTarget.Name
                ?: return IntellijNativeDiscoveryExecution.Rejected(IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT)
        if (target.match != SymbolDiscoveryMatch.EXACT_NAME)
            return IntellijNativeDiscoveryExecution.Rejected(IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT)
        return discoverIndexed(compiledScope, request, IntellijReadContributor.EXACT_INDEX) { _, _, accept ->
            process(target.pattern.value, accept)
        }
    }

    private fun discoverIndexed(
        compiledScope: CompiledIntellijSearchScope,
        request: SymbolDiscoveryRequest,
        contributor: IntellijReadContributor,
        process: (() -> Boolean, (SymbolDiscoveryQualification) -> Unit, (NavigationItem) -> Boolean) -> Boolean,
    ): IntellijNativeDiscoveryExecution {
        when (environmentState()) {
            IntellijDiscoveryEnvironmentState.DUMB ->
                return IntellijNativeDiscoveryExecution.Rejected(IntellijNativeDiscoveryRejection.DUMB_MODE)
            IntellijDiscoveryEnvironmentState.DISPOSED ->
                return IntellijNativeDiscoveryExecution.Rejected(IntellijNativeDiscoveryRejection.PROJECT_DISPOSED)
            IntellijDiscoveryEnvironmentState.READY -> Unit
        }
        val collector =
            BoundedNativeDiscoveryCollector(
                compiledScope,
                request,
                itemFile,
                projector,
                itemAdmission,
                itemCompilerKind,
                itemPackage,
                environmentState,
                cancellationCheck,
                clock,
                observation,
            )
        collector.contributor = contributor
        if (compiledScope.population == IntellijScopePopulation.KNOWN_EMPTY) return collector.finish()
        val pending = ArrayList<NavigationItem>()
        try {
            var reachedLimit = false
            val target = request.target
            val fuzzy = (target as? SymbolDiscoveryTarget.Name)?.takeIf { it.match == SymbolDiscoveryMatch.FUZZY }
            val capacity = minOf(request.budget.resources.workUnitLimit.value,
                limits[ReadLimitParameter.DISCOVERY_CANDIDATES].value.toLong()).toInt()
            val ranked = fuzzy?.let { BoundedLexicalCandidates(it.pattern, minOf(capacity, request.budget.resources.resultLimit.value)) }
            val complete =
                process(collector::observe, collector::qualify) { item ->
                    if (!collector.observe()) return@process false
                    if (fuzzy != null) {
                        observation.count(IntellijReadCounter.NAMES_VISITED, contributor)
                        val name = item.name ?: return@process true
                        if (fuzzy.pattern.relevance(name) == SymbolNameRelevance.UNMATCHED) return@process true
                        observation.count(IntellijReadCounter.NAMES_MATCHED, contributor)
                    }
                    // Scoped enumeration has left native callbacks; package PSI is safe here.
                    if (collector.admit(item, contributor == IntellijReadContributor.SCOPED_DECLARATIONS) !=
                        IntellijDiscoveryItemAdmission.ADMITTED) return@process !collector.halted
                    if (ranked != null) {
                        ranked.accept(item)
                    } else {
                        if (pending.size >= capacity) {
                            reachedLimit = true
                            return@process false
                        }
                        pending += item
                    }
                    observation.count(IntellijReadCounter.CANDIDATES_COLLECTED, collector.contributor)
                    true
                }
            if (ranked != null) {
                pending += ranked.values()
                if (ranked.truncated) {
                    collector.qualify(if (request.budget.resources.resultLimit.value <= capacity)
                        SymbolDiscoveryQualification.RESULT_LIMIT_REACHED else SymbolDiscoveryQualification.WORK_LIMIT_REACHED)
                }
            }
            if (reachedLimit) {
                observation.terminated(
                    if (pending.size == limits[ReadLimitParameter.DISCOVERY_CANDIDATES].value)
                        IntellijReadTermination.CANDIDATE_CAP
                    else IntellijReadTermination.WORK_LIMIT,
                    collector.contributor,
                )
                collector.qualify(SymbolDiscoveryQualification.WORK_LIMIT_REACHED)
            } else if (!complete && !collector.halted) {
                collector.qualify(SymbolDiscoveryQualification.PROVIDER_FAILURE)
            }
            for (item in pending) {
                if (
                    target is SymbolDiscoveryTarget.Name &&
                        target.match == SymbolDiscoveryMatch.EXACT_NAME &&
                        item.name != target.pattern.value
                ) {
                    collector.qualify(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
                    continue
                }
                if (!collector.accept(item)) break
            }
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IndexNotReadyException) {
            collector.qualifyAndHalt(SymbolDiscoveryQualification.DUMB_MODE_TRANSITION)
        } catch (failure: RuntimeException) {
            observation.unexpected(
                io.github.amichne.kast.workspace.intellij.read.IntellijReadUnexpectedFailure.capture(
                    io.github.amichne.kast.workspace.intellij.read.IntellijReadStage.DISCOVERY,
                    failure,
                    limits,
                )
            )
            collector.qualifyAndHalt(SymbolDiscoveryQualification.PROVIDER_FAILURE)
        }
        return collector.finish()
    }

    private companion object {
        val LOG: Logger = Logger.getInstance(IntellijNativeDiscoveryQuery::class.java)
    }
}
