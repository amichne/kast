package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.query.protocol.CanonicalQueryProtocol
import io.github.amichne.kast.query.protocol.CanonicalQueryReferences
import io.github.amichne.kast.query.service.QueryService
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.intellij.ProjectBoundIntellijRelationPort
import io.github.amichne.kast.relation.service.RelationService
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadContextPort
import io.github.amichne.kast.source.contract.SourceReadContinuationPort
import io.github.amichne.kast.source.contract.SourceReadRejection
import io.github.amichne.kast.source.intellij.ProjectBoundIntellijSourceReadPort
import io.github.amichne.kast.source.service.SourceReadService
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.intellij.ProjectBoundIntellijSymbolPorts
import io.github.amichne.kast.symbol.service.SymbolDiscoveryService
import io.github.amichne.kast.symbol.service.SymbolExactService
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.SemanticReadValidation
import io.github.amichne.kast.workspace.contract.SemanticReadValidationPort
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.IntellijSemanticSourceFileAdmission
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale

/** Native files and an authored imported-model observation own this compiler fixture's published basis. */
internal class NativeScopedTraceFixture(
    private val project: Project,
    root: CanonicalWorkspaceRoot,
    private val model: WorkspaceSearchScopeModel,
    private val files: List<VirtualFile>,
) {
    val lease = SemanticReadLease(root, EvidenceGeneration.parse(1L).traceRefined())
    private val stamps = files.map { it.modificationStamp }
    private val sourceState =
        WorkspaceStateIdentity.parse(
                MessageDigest.getInstance("SHA-256")
                    .digest(files.flatMap { it.contentsToByteArray().asIterable() }.toByteArray())
                    .joinToString("") { "%02x".format(Locale.ROOT, it) }
            )
            .traceRefined()
    val observation = NativeTraceCounters()

    private fun current() =
        ReadAction.compute<Boolean, RuntimeException> {
            !project.isDisposed && files.map { it.modificationStamp } == stamps
        }

    private val validation = SemanticReadValidationPort { expected ->
        when {
            expected.workspaceRoot != lease.workspaceRoot -> SemanticReadValidation.ROOT_MISMATCH
            expected != lease || !current() -> SemanticReadValidation.MOVED
            else -> SemanticReadValidation.CURRENT
        }
    }
    private val admission = IntellijSemanticSourceFileAdmission { path, sourceSets ->
        model.sourceRoots.any { owner ->
            path.startsWith(Path.of(owner.sourceRoot.value)) &&
                when (sourceSets) {
                    SymbolDiscoverySourceSets.All -> true
                    is SymbolDiscoverySourceSets.Exact -> owner.sourceSet in sourceSets.values
                }
        }
    }
    private val limits = ReadLimits.Default

    fun protocol(): CanonicalQueryProtocol {
        val symbols =
            ProjectBoundIntellijSymbolPorts.create(
                project = project,
                authority = lease,
                model = model,
                fileAdmission = admission,
                observation = observation,
                limits = limits,
            )
        val relations =
            RelationService(
                validation,
                ProjectBoundIntellijRelationPort.create(
                    project = project,
                    authority = lease,
                    model = model,
                    fileAdmission = admission,
                    observation = observation,
                    limits = limits,
                ),
            )
        return CanonicalQueryProtocol(
            QueryService(
                discovery = SymbolDiscoveryService(validation, symbols.discovery),
                exact = SymbolExactService(validation, symbols.exact),
                source = source(),
                relations = relations,
                traversal = hostedTraversalOperations(relations, observation),
                traversalCeiling = traversalBudget,
            ),
            CanonicalQueryReferences(model),
        )
    }

    private fun source() =
        SourceReadService(
            SourceReadContextPort { expected ->
                if (expected == lease && current()) Refinement.Refined(SourceReadContext(lease, sourceState))
                else Refinement.Rejected(SourceReadRejection.SOURCE_STATE_MISMATCH)
            },
            ProjectBoundIntellijSourceReadPort.create(
                project = project,
                authority = lease,
                model = model,
                fileAdmission = admission,
                continuations = SourceReadContinuationPort.Cursorless,
                limits = limits,
                observation = observation,
            ),
        )

    private val traversalBudget =
        TraversalBudget(
            records = resources.resultLimit,
            returnedBytes = TraversalByteLimit.parse(RETURNED_BYTES).traceRefined(),
            workUnits = resources.workUnitLimit,
            elapsedTime = resources.elapsedTimeLimit,
            depth = TraversalDepthLimit.parse(limits[ReadLimitParameter.TRAVERSAL_DEPTH].value).traceRefined(),
            frontier = TraversalFrontierLimit.parse(limits[ReadLimitParameter.TRAVERSAL_FRONTIER].value).traceRefined(),
            oneHop = RelationBudget(resources, RelationByteLimit.parse(RETURNED_BYTES).traceRefined()),
        )

    companion object {
        const val RETURNED_BYTES = 524_288L
        val resources =
            ResourceBudget(
                ResultLimit.parse(100).traceRefined(),
                WorkUnitLimit.parse(100_000L).traceRefined(),
                ElapsedTimeLimitMillis.parse(15_000L).traceRefined(),
            )
        const val SOURCE_DIRECTORY = "src/backend-service-clients"
        const val cacheText = "package scoped\nclass CacheManager {\n    fun operation() {}\n    fun execute() {}\n}\n"
        const val clientText =
            "package scoped\nfun client() { kotlin.with(CacheManager()) { operation() } }\nfun entry() { client() }\n"
        const val outsideText = "package unrelated\nfun outside() { scoped.CacheManager().operation() }\n"
    }
}

internal class NativeTraceCounters : IntellijReadObservation {
    private val counts = mutableMapOf<IntellijReadCounter, Long>()
    val terminations = mutableListOf<IntellijReadTermination>()

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        counts[counter] = (counts[counter] ?: 0L) + amount
    }

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
        terminations += reason
    }

    operator fun get(counter: IntellijReadCounter): Long = counts[counter] ?: 0L

    fun snapshot(): Map<IntellijReadCounter, Long> = counts.toMap()
}

internal fun <Value, Failure> Refinement<Value, Failure>.traceRefined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid native TRACE fixture: $failure")
    }
