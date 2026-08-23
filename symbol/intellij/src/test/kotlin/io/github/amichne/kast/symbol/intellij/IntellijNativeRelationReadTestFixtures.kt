package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.ExactDeclarationEvidence
import io.github.amichne.kast.symbol.contract.ExactDeclarationSelector
import io.github.amichne.kast.symbol.contract.ExactRelationEndpoint
import io.github.amichne.kast.symbol.contract.NativeRelationBudget
import io.github.amichne.kast.symbol.contract.NativeRelationByteLimit
import io.github.amichne.kast.symbol.contract.NativeRelationFact
import io.github.amichne.kast.symbol.contract.NativeRelationFamily
import io.github.amichne.kast.symbol.contract.NativeRelationLimitation
import io.github.amichne.kast.symbol.contract.NativeRelationOccurrence
import io.github.amichne.kast.symbol.contract.NativeRelationOutcome
import io.github.amichne.kast.symbol.contract.NativeRelationRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Path

internal fun assertQualified(
    fixture: Fixture,
    limitation: NativeRelationLimitation,
    knownMinimum: Int,
) {
    val qualified = fixture.query.read(fixture.compiledScope, fixture.request).outcome()
        as NativeRelationOutcome.Qualified
    assertEquals(knownMinimum, qualified.knownMinimumCount.value)
    assertTrue(limitation in qualified.limitations.values)
}

internal fun fixture(
    family: NativeRelationFamily = NativeRelationFamily.REFERENCES,
    resultLimit: Int = 10,
    returnedBytes: Long = 10_000L,
    workLimit: Long = 100L,
    elapsedMillis: Long = 1_000L,
    eventCount: Int = 0,
    terminal: Boolean = true,
    providerLimitations: Set<NativeRelationLimitation> = emptySet(),
    providerFails: Boolean = false,
    searchRejection: IntellijNativeRelationSearchRejection? = null,
    environmentState: () -> IntellijDiscoveryEnvironmentState = {
        IntellijDiscoveryEnvironmentState.READY
    },
    cancellationCheck: () -> Unit = {},
    clock: IntellijRelationNanoClock = StepClock(),
): Fixture {
    val request = request(family, resultLimit, returnedBytes, workLimit, elapsedMillis)
    val scope = object : GlobalSearchScope() {
        override fun contains(file: VirtualFile): Boolean = true

        override fun isSearchInModuleContent(aModule: Module): Boolean = true

        override fun isSearchInLibraries(): Boolean = false
    }
    val compiledScope = CompiledIntellijSearchScope(
        lease = request.selector.lease,
        scope = request.selector.scope,
        sourceRoots = emptyList(),
        nativeScope = scope,
    )
    val observedScopes = mutableListOf<CompiledIntellijSearchScope>()
    val observedFamilies = mutableListOf<NativeRelationFamily>()
    val search = IntellijNativeRelationSearch { compiled, relationRequest, consumer ->
        observedScopes += compiled
        observedFamilies += relationRequest.family
        if (providerFails) {
            error("provider failed")
        }
        if (searchRejection != null) {
            IntellijNativeRelationSearchResult.Rejected(searchRejection)
        } else {
            var providerTerminal = terminal
            for (index in 0 until eventCount) {
                if (!consumer(FakeEvent(index))) {
                    providerTerminal = false
                    break
                }
            }
            if (providerTerminal) {
                IntellijNativeRelationSearchResult.Terminal(providerLimitations)
            } else {
                IntellijNativeRelationSearchResult.Halted(providerLimitations)
            }
        }
    }
    val projector = IntellijRelationFactProjector { relationRequest, event ->
        val fake = event as FakeEvent
        val related = evidence(
            path = "/workspace/src/Related" + fake.index + ".kt",
            name = "sameName",
            start = 100 + fake.index * 20,
        )
        NativeRelationFact.create(
            subject = relationRequest.selector,
            family = relationRequest.family,
            related = ExactRelationEndpoint.bind(relationRequest.selector, related),
            occurrence = NativeRelationOccurrence.fromBoundary(
                file = fileIdentity("/workspace/src/Usage" + fake.index + ".kt"),
                rawStartInclusive = 200 + fake.index * 20,
                rawEndExclusive = 205 + fake.index * 20,
            ).refined(),
        ).mapFailure { IntellijRelationProjectionFailure.UNSUPPORTED_ITEM }
    }
    return Fixture(
        request,
        compiledScope,
        observedScopes,
        observedFamilies,
        IntellijNativeRelationQuery(
            search,
            projector,
            environmentState,
            cancellationCheck,
            clock,
        ),
    )
}

internal fun request(
    family: NativeRelationFamily,
    resultLimit: Int,
    returnedBytes: Long,
    workLimit: Long,
    elapsedMillis: Long,
): NativeRelationRequest = NativeRelationRequest(
    selector = selector(),
    family = family,
    budget = NativeRelationBudget(
        resources = ResourceBudget(
            resultLimit = ResultLimit.parse(resultLimit).refined(),
            workUnitLimit = WorkUnitLimit.parse(workLimit).refined(),
            elapsedTimeLimit = ElapsedTimeLimitMillis.parse(elapsedMillis).refined(),
        ),
        returnedBytes = NativeRelationByteLimit.parse(returnedBytes).refined(),
    ),
)

internal fun selector(): ExactDeclarationSelector {
    val scope = SymbolSearchScope.Workspace(
        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
        SymbolGeneratedSourcePolicy.INCLUDE,
        SymbolLibraryPolicy.EXCLUDE,
    )
    val candidate = SymbolDiscoveryCandidate.fromBoundary(
        SymbolDiscoveryKind.SYMBOL,
        "service",
        lease(),
        Path.of("/workspace/src/Service.kt"),
        "file:///workspace/src/Service.kt",
        7,
    ).refined()
    val discoveryRequest = SymbolDiscoveryRequest(
        SymbolSearchScopeRequest(lease(), scope),
        SymbolDiscoveryTarget.Name(
            SymbolNameDiscoveryKind.SYMBOL,
            SymbolDiscoveryPattern.parse("service").refined(),
            SymbolDiscoveryMatch.FUZZY,
        ),
        SymbolDiscoveryBudget(
            ResourceBudget(
                ResultLimit.parse(1).refined(),
                WorkUnitLimit.parse(10L).refined(),
                ElapsedTimeLimitMillis.parse(100L).refined(),
            ),
            SymbolDiscoveryByteLimit.parse(1_000L).refined(),
        ),
    )
    val batch = SymbolDiscoveryBatch.create(
        discoveryRequest,
        listOf(candidate),
        candidate.projectedUtf8Size(),
        SymbolDiscoveryWorkCount.parse(1L).refined(),
        SymbolDiscoveryTimings(
            SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
            SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
        ),
    ).refined()
    return ExactDeclarationSelector.issue(
        SymbolDiscoverySelection.select(batch, 0).refined(),
        evidence("/workspace/src/Service.kt", "service", 7),
    ).refined()
}

internal fun evidence(
    path: String,
    name: String,
    start: Int,
): ExactDeclarationEvidence = ExactDeclarationEvidence.fromBoundary(
    fileIdentity(path),
    start,
    start + 10,
    name,
    "sample.$name",
    "sample.FakeDeclaration",
).refined()

internal fun fileIdentity(path: String) =
    SymbolDiscoveryFileIdentity.fromBoundary(
        root(),
        Path.of(path),
        "file://$path",
    ).refined()

internal fun root(): CanonicalWorkspaceRoot =
    CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()

internal fun lease(): SemanticReadLease =
    SemanticReadLease(root(), EvidenceGeneration.parse(21L).refined())

internal data class FakeEvent(val index: Int) : IntellijNativeRelationEvent

internal data class Fixture(
    val request: NativeRelationRequest,
    val compiledScope: CompiledIntellijSearchScope,
    val observedScopes: List<CompiledIntellijSearchScope>,
    val observedFamilies: List<NativeRelationFamily>,
    val query: IntellijNativeRelationQuery,
)

internal class StepClock(
    private val step: Long = 100L,
) : IntellijRelationNanoClock {
    private var current = 0L

    override fun now(): Long = current.also { current += step }
}

internal fun IntellijNativeRelationExecution.outcome(): NativeRelationOutcome =
    (this as IntellijNativeRelationExecution.Produced).outcome

internal fun <Strong, Failure, OtherFailure> Refinement<Strong, Failure>.mapFailure(
    transform: (Failure) -> OtherFailure,
): Refinement<Strong, OtherFailure> = when (this) {
    is Refinement.Refined -> Refinement.Refined(value)
    is Refinement.Rejected -> Refinement.Rejected(transform(failure))
}

internal fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error(failure.toString())
}
