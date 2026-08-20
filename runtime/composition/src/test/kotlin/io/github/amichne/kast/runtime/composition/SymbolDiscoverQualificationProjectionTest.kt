package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SymbolDiscoverLimitation
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverTargetDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolNameKindDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.runtime.composition.protocol.CanonicalProtocolAuthority
import io.github.amichne.kast.runtime.composition.protocol.CanonicalSymbolDiscoverHandler
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualifications
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.startCoroutine
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest as DomainDiscoveryRequest

class SymbolDiscoverQualificationProjectionTest {
    @Test
    fun `work limit qualification survives composition projection`(@TempDir temporary: Path) {
        val root = Files.createDirectories(temporary.resolve("repo")).toRealPath()

        val outcome = runDiscovery(root, SymbolDiscoveryQualification.WORK_LIMIT_REACHED)

        assertEquals(
            SymbolDiscoverQualification.from(setOf(SymbolDiscoverLimitation.WORK_LIMIT)).refined(),
            (outcome as OperationOutcome.Qualified<*, *>).qualification,
        )
    }

    @Test
    fun `provider failure qualification survives composition projection`(@TempDir temporary: Path) {
        val root = Files.createDirectories(temporary.resolve("repo")).toRealPath()

        val outcome = runDiscovery(root, SymbolDiscoveryQualification.PROVIDER_FAILURE)

        assertEquals(
            SymbolDiscoverQualification.from(setOf(SymbolDiscoverLimitation.PROVIDER_FAILURE)).refined(),
            (outcome as OperationOutcome.Qualified<*, *>).qualification,
        )
    }

    @Test
    fun `multiple qualifications survive in deterministic order`(@TempDir temporary: Path) {
        val root = Files.createDirectories(temporary.resolve("repo")).toRealPath()

        val outcome = runDiscovery(
            root,
            SymbolDiscoveryQualification.PROVIDER_FAILURE,
            SymbolDiscoveryQualification.WORK_LIMIT_REACHED,
        )

        assertEquals(
            SymbolDiscoverQualification.from(
                setOf(
                    SymbolDiscoverLimitation.WORK_LIMIT,
                    SymbolDiscoverLimitation.PROVIDER_FAILURE,
                ),
            ).refined(),
            (outcome as OperationOutcome.Qualified<*, *>).qualification,
        )
    }

    @Test
    fun `every domain qualification maps to one public limitation`(@TempDir temporary: Path) {
        val root = Files.createDirectories(temporary.resolve("repo")).toRealPath()

        SymbolDiscoveryQualification.entries.forEach { domain ->
            val outcome = runDiscovery(root, domain)
            val qualification = (outcome as OperationOutcome.Qualified<*, *>).qualification as
                SymbolDiscoverQualification
            assertEquals(1, qualification.limitations.size)
            assertEquals(
                listOf(domain.publicLimitation()),
                qualification.limitations,
            )
        }
    }

    private fun runDiscovery(
        root: Path,
        vararg qualifications: SymbolDiscoveryQualification,
    ): OperationOutcome<SymbolDiscoverResult, SymbolDiscoverQualification, SymbolDiscoverRejection> {
        val published = published(root)
        val discovery = SymbolDiscoveryOperations { request ->
            SymbolDiscoveryResult.Discovered(
                SymbolDiscoveryOutcome.Qualified(
                    batch(root, request),
                    SymbolDiscoveryQualifications.from(qualifications.toSet()).refined(),
                ),
            )
        }
        val handler = CanonicalSymbolDiscoverHandler(
            WorkspaceInspectionOperations { WorkspaceRuntimeState.Ready(published) },
            discovery,
            CanonicalProtocolAuthority(),
        )
        return runImmediate {
            handler.execute(
                SymbolDiscoverRequest(
                    SymbolDiscoverTargetDocument.Name(
                        ProtocolText.parse("sample").refined(),
                        SymbolNameKindDocument.SYMBOL,
                        SymbolDiscoveryMatchDocument.FUZZY,
                    ),
                    ProtocolCount.parse(4).refined(),
                ),
            )
        }
    }

    private fun published(root: Path): PublishedWorkspace {
        val canonical = CanonicalWorkspaceRoot.fromCanonicalPath(root).refined()
        val reconciled = ReconciledWorkspace.admit(
            WorkspaceCandidate(canonical, WorkspaceStateIdentity.parse("symbol-state").refined()),
            WorkspaceEvidenceKind.entries.toSet(),
        ).refined()
        return PublishedWorkspace.publish(reconciled, EvidenceGeneration.parse(11).refined())
    }

    private fun batch(root: Path, request: DomainDiscoveryRequest): SymbolDiscoveryBatch {
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            SymbolDiscoveryKind.SYMBOL,
            "sample",
            request.scope.lease,
            root.resolve("src/main/kotlin/Sample.kt"),
            root.resolve("src/main/kotlin/Sample.kt").toUri().toString(),
            0,
        ).refined()
        return SymbolDiscoveryBatch.create(
            request,
            listOf(candidate),
            SymbolDiscoveryByteCount.parse(candidate.projectedUtf8Size().value).refined(),
            SymbolDiscoveryWorkCount.parse(1).refined(),
            SymbolDiscoveryTimings(
                SymbolDiscoveryElapsedNanoseconds.parse(0).refined(),
                SymbolDiscoveryElapsedNanoseconds.parse(0).refined(),
            ),
        ).refined()
    }

    private fun SymbolDiscoveryQualification.publicLimitation(): SymbolDiscoverLimitation = when (this) {
        SymbolDiscoveryQualification.RESULT_LIMIT_REACHED -> SymbolDiscoverLimitation.RESULT_LIMIT
        SymbolDiscoveryQualification.BYTE_LIMIT_REACHED -> SymbolDiscoverLimitation.BYTE_LIMIT
        SymbolDiscoveryQualification.WORK_LIMIT_REACHED -> SymbolDiscoverLimitation.WORK_LIMIT
        SymbolDiscoveryQualification.TIME_LIMIT_REACHED -> SymbolDiscoverLimitation.TIME_LIMIT
        SymbolDiscoveryQualification.DUMB_MODE_TRANSITION -> SymbolDiscoverLimitation.DUMB_MODE_TRANSITION
        SymbolDiscoveryQualification.PROVIDER_FAILURE -> SymbolDiscoverLimitation.PROVIDER_FAILURE
        SymbolDiscoveryQualification.UNSCOPED_PROVIDER -> SymbolDiscoverLimitation.UNSCOPED_PROVIDER
        SymbolDiscoveryQualification.UNSUPPORTED_ITEM -> SymbolDiscoverLimitation.UNSUPPORTED_ITEM
        SymbolDiscoveryQualification.EXACT_DEFINITION_UNAVAILABLE ->
            SymbolDiscoverLimitation.EXACT_DEFINITION_UNAVAILABLE
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("unexpected rejection: $failure")
}

private fun <Value> runImmediate(block: suspend () -> Value): Value {
    var completed: Result<Value>? = null
    block.startCoroutine(
        object : kotlin.coroutines.Continuation<Value> {
            override val context = kotlin.coroutines.EmptyCoroutineContext

            override fun resumeWith(result: Result<Value>) {
                completed = result
            }
        },
    )
    return checkNotNull(completed) { "operation suspended unexpectedly" }.getOrThrow()
}
