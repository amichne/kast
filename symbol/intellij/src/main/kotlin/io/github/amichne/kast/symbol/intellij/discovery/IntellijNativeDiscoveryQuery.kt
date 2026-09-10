package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import com.intellij.util.text.matching.MatchingMode
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualifications
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import java.nio.file.Path
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
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
    data class Produced(
        val outcome: SymbolDiscoveryOutcome,
    ) : IntellijNativeDiscoveryExecution

    data class Rejected(
        val reason: IntellijNativeDiscoveryRejection,
    ) : IntellijNativeDiscoveryExecution
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
    private val projector: IntellijDiscoveryCandidateProjector =
        IntellijPsiDiscoveryCandidateProjector,
    private val itemAdmission: IntellijDiscoveryItemAdmissionPolicy =
        AdmitEveryIntellijDiscoveryItem,
    private val itemCompilerKind: IntellijDiscoveryItemCompilerKind =
        IntellijPsiDiscoveryItemCompilerKind,
    private val itemPackage: IntellijDiscoveryItemPackage = IntellijPsiDiscoveryItemPackage,
    private val environmentState: () -> IntellijDiscoveryEnvironmentState,
    private val cancellationCheck: () -> Unit,
    private val clock: IntellijDiscoveryNanoClock = SystemIntellijDiscoveryNanoClock,
) {
    /**
     * Proof transition:
     * CompiledIntellijSearchScope + SymbolDiscoveryRequest + native contributors to
     * IntellijNativeDiscoveryExecution.
     *
     * Establishes that each provider receives the compiled scope before native index work, each
     * item is scope-checked before PSI projection, and every returned candidate is detached,
     * deterministic, record/byte/work/time bounded, and authority-bound.
     * [IntellijNativeDiscoveryRejection] and [SymbolDiscoveryQualification] are the closed expected
     * failure and partial-coverage states. Cancellation remains a platform cancellation and is
     * propagated. Live contributors, navigation items, virtual files, and scopes remain inside this
     * request-local call.
     */
    fun discover(
        compiledScope: CompiledIntellijSearchScope,
        request: SymbolDiscoveryRequest,
        contributors: List<ChooseByNameContributor>,
        nameFilter: IdFilter = IdFilter.ACCEPT_ALL,
    ): IntellijNativeDiscoveryExecution {
        when (environmentState()) {
            IntellijDiscoveryEnvironmentState.DUMB ->
                return IntellijNativeDiscoveryExecution.Rejected(
                    IntellijNativeDiscoveryRejection.DUMB_MODE,
                )
            IntellijDiscoveryEnvironmentState.DISPOSED ->
                return IntellijNativeDiscoveryExecution.Rejected(
                    IntellijNativeDiscoveryRejection.PROJECT_DISPOSED,
                )
            IntellijDiscoveryEnvironmentState.READY -> Unit
        }

        val target = request.target
        if (target !is SymbolDiscoveryTarget.Name && target !is SymbolDiscoveryTarget.All) {
            return IntellijNativeDiscoveryExecution.Rejected(
                IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT,
            )
        }
        val collector = BoundedNativeDiscoveryCollector(
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
        )
        if (compiledScope.population == IntellijScopePopulation.KNOWN_EMPTY) return collector.finish()
        if (contributors.isEmpty()) {
            return IntellijNativeDiscoveryExecution.Rejected(
                IntellijNativeDiscoveryRejection.NO_NATIVE_PROVIDERS,
            )
        }
        val fuzzyMatcher = when (target) {
            is SymbolDiscoveryTarget.All -> null
            is SymbolDiscoveryTarget.Name -> when (target.match) {
                SymbolDiscoveryMatch.FUZZY -> NameUtil.buildMatcher(
                    "*${target.pattern.value}",
                    MatchingMode.IGNORE_CASE,
                )
                SymbolDiscoveryMatch.EXACT_NAME -> null
            }
        }

        contributors.sortedBy { it.javaClass.name }.forEach { contributor ->
            if (collector.halted) {
                return@forEach
            }
            if (contributor !is ChooseByNameContributorEx) {
                collector.qualify(SymbolDiscoveryQualification.UNSCOPED_PROVIDER)
                return@forEach
            }
            try {
                val matchingNames = linkedSetOf<String>()
                contributor.processNames(
                    Processor { name ->
                        if (!collector.observe()) {
                            return@Processor false
                        }
                        val matches = when (target) {
                            is SymbolDiscoveryTarget.All -> true
                            is SymbolDiscoveryTarget.Name -> when (target.match) {
                                SymbolDiscoveryMatch.FUZZY -> checkNotNull(fuzzyMatcher).matches(name)
                                SymbolDiscoveryMatch.EXACT_NAME -> name == target.pattern.value
                            }
                        }
                        if (!matches) {
                            return@Processor true
                        }
                        if (name !in matchingNames && matchingNames.size >= MAX_NATIVE_DISCOVERY_NAMES) {
                            collector.qualify(SymbolDiscoveryQualification.WORK_LIMIT_REACHED)
                            return@Processor false
                        }
                        matchingNames += name
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
                            if (pending.size >= MAX_NATIVE_DISCOVERY_CANDIDATES) {
                                reachedCandidateLimit = true
                                collector.qualify(SymbolDiscoveryQualification.WORK_LIMIT_REACHED)
                                return@Processor false
                            }
                            pending += item
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
                LOG.warn(
                    "Native symbol discovery provider failed: ${contributor.javaClass.name}",
                    failure,
                )
                collector.qualify(SymbolDiscoveryQualification.PROVIDER_FAILURE)
            }
        }
        return collector.finish()
    }

    /** Direct-key candidates are fully collected before projection or compiler refinement. */
    fun discoverExactName(
        compiledScope: CompiledIntellijSearchScope,
        request: SymbolDiscoveryRequest,
        process: (String, (NavigationItem) -> Boolean) -> Boolean,
    ): IntellijNativeDiscoveryExecution {
        val target = request.target as? SymbolDiscoveryTarget.Name
            ?: return IntellijNativeDiscoveryExecution.Rejected(
                IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT,
            )
        if (target.match != SymbolDiscoveryMatch.EXACT_NAME) {
            return IntellijNativeDiscoveryExecution.Rejected(
                IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT,
            )
        }
        when (environmentState()) {
            IntellijDiscoveryEnvironmentState.DUMB -> return IntellijNativeDiscoveryExecution.Rejected(
                IntellijNativeDiscoveryRejection.DUMB_MODE,
            )
            IntellijDiscoveryEnvironmentState.DISPOSED -> return IntellijNativeDiscoveryExecution.Rejected(
                IntellijNativeDiscoveryRejection.PROJECT_DISPOSED,
            )
            IntellijDiscoveryEnvironmentState.READY -> Unit
        }
        val collector = BoundedNativeDiscoveryCollector(
            compiledScope, request, itemFile, projector, itemAdmission, itemCompilerKind, itemPackage,
            environmentState, cancellationCheck, clock,
        )
        if (compiledScope.population == IntellijScopePopulation.KNOWN_EMPTY) return collector.finish()
        val pending = ArrayList<NavigationItem>()
        try {
            var reachedLimit = false
            val complete = process(target.pattern.value) { item ->
                if (!collector.observe()) return@process false
                if (pending.size.toLong() >= request.budget.resources.workUnitLimit.value) {
                    reachedLimit = true
                    return@process false
                }
                pending += item
                true
            }
            if (reachedLimit) collector.qualify(SymbolDiscoveryQualification.WORK_LIMIT_REACHED)
            else if (!complete && !collector.halted) {
                collector.qualify(SymbolDiscoveryQualification.PROVIDER_FAILURE)
            }
            for (item in pending) {
                if (item.name != target.pattern.value) {
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
        } catch (_: RuntimeException) {
            collector.qualifyAndHalt(SymbolDiscoveryQualification.PROVIDER_FAILURE)
        }
        return collector.finish()
    }

    private companion object {
        val LOG: Logger = Logger.getInstance(IntellijNativeDiscoveryQuery::class.java)
    }
}

private class BoundedNativeDiscoveryCollector(
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
) {
    private val startedAt = clock.now()
    private val candidates = linkedSetOf<SymbolDiscoveryCandidate>()
    private val qualifications = linkedSetOf<SymbolDiscoveryQualification>()
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
        val file = when (val itemFileResult = itemFile.find(item)) {
            is IntellijDiscoveryItemFileResult.Found -> itemFileResult.file
            IntellijDiscoveryItemFileResult.Unsupported -> {
                qualify(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
                return true
            }
        }
        if (!compiledScope.nativeScope.contains(file)) {
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
            IntellijDiscoveryItemAdmission.FILTERED -> return true
            IntellijDiscoveryItemAdmission.UNSUPPORTED -> {
                qualify(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
                return true
            }
        }
        when (itemAdmission.admit(item)) {
            IntellijDiscoveryItemAdmission.ADMITTED -> Unit
            IntellijDiscoveryItemAdmission.FILTERED -> return true
            IntellijDiscoveryItemAdmission.UNSUPPORTED -> {
                qualify(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
                return true
            }
        }
        val projectionStartedAt = clock.now()
        val projected = projector.project(request, item, file)
        projectionNanoseconds = saturatedAdd(
            projectionNanoseconds,
            elapsedSince(projectionStartedAt),
        )
        val candidate = when (projected) {
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
        encodedBytes += candidateBytes.value
        return true
    }

    fun qualify(qualification: SymbolDiscoveryQualification) {
        qualifications += qualification
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
        val timings = SymbolDiscoveryTimings(
            nativeQuery = (totalNanoseconds - projectionNanoseconds)
                .coerceAtLeast(0L)
                .elapsedMeasure(),
            projection = projectionNanoseconds.elapsedMeasure(),
        )
        val orderedCandidates = candidates.sorted()
        val batch = when (
            val creation = SymbolDiscoveryBatch.create(
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
                    IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT,
                )
        }
        val outcome = if (qualifications.isEmpty()) {
            SymbolDiscoveryOutcome.Complete(batch)
        } else {
            val typedQualifications = when (
                val refinement = SymbolDiscoveryQualifications.from(qualifications)
            ) {
                is Refinement.Refined -> refinement.value
                is Refinement.Rejected ->
                    return IntellijNativeDiscoveryExecution.Rejected(
                        IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT,
                    )
            }
            SymbolDiscoveryOutcome.Qualified(batch, typedQualifications)
        }
        return IntellijNativeDiscoveryExecution.Produced(outcome)
    }

    private fun elapsedSince(start: Long): Long = (clock.now() - start).coerceAtLeast(0L)
}

private fun SymbolDiscoveryConstraints.admit(
    item: NavigationItem,
    filePath: String,
    workspaceRoot: String,
    compiledScope: CompiledIntellijSearchScope,
    itemCompilerKind: IntellijDiscoveryItemCompilerKind,
    itemPackage: IntellijDiscoveryItemPackage,
): IntellijDiscoveryItemAdmission {
    when (val selection = sourceSets) {
        SymbolDiscoverySourceSets.All -> Unit
        is SymbolDiscoverySourceSets.Exact -> {
            val file = runCatching { Path.of(filePath) }.getOrNull()
                ?: return IntellijDiscoveryItemAdmission.UNSUPPORTED
            if (!file.isAbsolute) return IntellijDiscoveryItemAdmission.UNSUPPORTED
            val normalizedFile = file.normalize()
            val owners = compiledScope.ownershipRoots.filter {
                normalizedFile.startsWith(Path.of(it.sourceRoot.value))
            }
            val deepest = owners.maxOfOrNull { Path.of(it.sourceRoot.value).nameCount }
                ?: return IntellijDiscoveryItemAdmission.UNSUPPORTED
            val exactOwners = owners.filter { Path.of(it.sourceRoot.value).nameCount == deepest }
            // A shared root can have several proven owners. Intersect readable ownership with
            // the requested names, without falling back to an ancestor when none is readable.
            if (exactOwners.none { it in compiledScope.sourceRoots && it.sourceSet in selection.values }) {
                return IntellijDiscoveryItemAdmission.FILTERED
            }
        }
    }
    directory?.let { restriction ->
        val root = runCatching { Path.of(workspaceRoot).toAbsolutePath().normalize() }.getOrNull()
            ?: return IntellijDiscoveryItemAdmission.UNSUPPORTED
        val file = runCatching { Path.of(filePath).toAbsolutePath().normalize() }.getOrNull()
            ?: return IntellijDiscoveryItemAdmission.UNSUPPORTED
        val requested = root.resolve(restriction.directory.value).normalize()
        val inDirectory = when (restriction.containment) {
            SymbolDiscoveryContainment.DIRECT -> file.parent == requested
            SymbolDiscoveryContainment.DESCENDANTS -> file.startsWith(requested)
        }
        if (!inDirectory) return IntellijDiscoveryItemAdmission.FILTERED
    }
    when (packageName.admitPackage { itemPackage.inspect(item) }) {
        IntellijDiscoveryItemAdmission.ADMITTED -> Unit
        IntellijDiscoveryItemAdmission.FILTERED -> return IntellijDiscoveryItemAdmission.FILTERED
        IntellijDiscoveryItemAdmission.UNSUPPORTED -> return IntellijDiscoveryItemAdmission.UNSUPPORTED
    }
    declarationKinds?.let { restriction ->
        val kind = when (val classified = itemCompilerKind.classify(item)) {
            is IntellijDiscoveryItemCompilerKindResult.Found -> classified.kind
            IntellijDiscoveryItemCompilerKindResult.Unsupported ->
                return IntellijDiscoveryItemAdmission.UNSUPPORTED
        }
        if (kind !in restriction.values) return IntellijDiscoveryItemAdmission.FILTERED
    }
    return IntellijDiscoveryItemAdmission.ADMITTED
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
): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

internal const val MAX_NATIVE_DISCOVERY_NAMES = 10_000
internal const val MAX_NATIVE_DISCOVERY_CANDIDATES = 10_000
