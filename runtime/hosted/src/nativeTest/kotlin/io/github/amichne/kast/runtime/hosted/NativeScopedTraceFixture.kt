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
import io.github.amichne.kast.query.contract.QueryBranchObservation
import io.github.amichne.kast.query.protocol.CanonicalQueryProtocol
import io.github.amichne.kast.query.protocol.CanonicalQueryReferences
import io.github.amichne.kast.query.protocol.CanonicalSelectorDecoding
import io.github.amichne.kast.query.protocol.CanonicalSourceReadProtocol
import io.github.amichne.kast.query.protocol.QueryReferenceAuthority
import io.github.amichne.kast.query.protocol.ReacquiringQueryReferences
import io.github.amichne.kast.query.protocol.decodingFailure
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
import io.github.amichne.kast.symbol.contract.ExactRevalidationPolicy
import io.github.amichne.kast.symbol.contract.ExactRevalidationResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.intellij.IntellijExactRevalidationCapture
import io.github.amichne.kast.symbol.intellij.ProjectBoundExactRevalidationPort
import io.github.amichne.kast.symbol.intellij.ProjectBoundIntellijSymbolPorts
import io.github.amichne.kast.symbol.service.ExactRevalidationService
import io.github.amichne.kast.symbol.service.SymbolDiscoveryService
import io.github.amichne.kast.symbol.service.SymbolExactService
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.SemanticReadValidation
import io.github.amichne.kast.workspace.contract.SemanticReadValidationPort
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.IntellijReadUnexpectedFailure
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

    private fun validation(authority: SemanticReadAuthority) = SemanticReadValidationPort { expected ->
        when {
            expected.workspaceRoot != authority.workspaceRoot -> SemanticReadValidation.ROOT_MISMATCH
            expected != authority || !current() -> SemanticReadValidation.MOVED
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
    private val references = CanonicalQueryReferences(model)

    fun sourceProtocol() = CanonicalSourceReadProtocol(source(), references)

    fun valueServices() =
        NativePacketQueryServices(project = project, root = lease.workspaceRoot, model = model, files = files)

    fun protocol(branchObservation: QueryBranchObservation = QueryBranchObservation.None): CanonicalQueryProtocol =
        protocol(authority = lease, references = references, capture = null, branchObservation = branchObservation)

    fun reacquiringProtocol(
        authority: LiveSemanticReadAuthority,
        records: ExactRevalidationRecords,
    ): CanonicalQueryProtocol {
        val read =
            NativeScopedTraceFixture(project = project, root = authority.workspaceRoot, model = model, files = files)
        return read.liveProtocol(authority, records)
    }

    private fun liveProtocol(
        authority: LiveSemanticReadAuthority,
        records: ExactRevalidationRecords,
    ): CanonicalQueryProtocol {
        val capture = IntellijExactRevalidationCapture(model, observation)
        val strict =
            CanonicalQueryReferences(
                model,
                exactIssued = { selector, token, canonical ->
                    records.retain(token, canonical, capture.locator(selector).traceRefined()).traceRefined()
                },
            )
        val policy = ExactRevalidationPolicy.CURRENT_DECLARATION
        val port =
            ProjectBoundExactRevalidationPort(
                project = project,
                model = model,
                files = admission,
                capture = capture,
                observation = observation,
                policy = policy,
            )
        val service = ExactRevalidationService(validation(authority), port, policy)
        val references =
            ReacquiringQueryReferences(
                strict,
                { token, current ->
                    val locator = records.locate(token).traceRefined()
                    when (val result = service.revalidate(locator, current)) {
                        is ExactRevalidationResult.Reacquired -> CanonicalSelectorDecoding.Decoded(result.selector)
                        is ExactRevalidationResult.Rejected ->
                            CanonicalSelectorDecoding.Rejected(result.reason.decodingFailure())
                    }
                },
            )
        return protocol(authority, references, capture)
    }

    private fun protocol(
        authority: SemanticReadAuthority,
        references: QueryReferenceAuthority,
        capture: IntellijExactRevalidationCapture?,
        branchObservation: QueryBranchObservation = QueryBranchObservation.None,
    ): CanonicalQueryProtocol {
        val validation = validation(authority)
        val symbols =
            ProjectBoundIntellijSymbolPorts.create(
                project = project,
                authority = authority,
                model = model,
                fileAdmission = admission,
                observation = observation,
                limits = limits,
                capture = capture,
            )
        val relations =
            RelationService(
                validation,
                ProjectBoundIntellijRelationPort.create(
                    project = project,
                    authority = authority,
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
                source = source(authority),
                relations = relations,
                traversal = hostedTraversalOperations(relations, observation),
                traversalCeiling = traversalBudget,
                branchObservation = branchObservation,
            ),
            references,
        )
    }

    private fun source(authority: SemanticReadAuthority = lease) =
        SourceReadService(
            SourceReadContextPort { expected ->
                if (expected != authority || !current()) Refinement.Rejected(SourceReadRejection.SOURCE_STATE_MISMATCH)
                else
                    Refinement.Refined(
                        when (authority) {
                            is LiveSemanticReadAuthority -> SourceReadContext.Live(authority)
                            is SemanticReadLease -> SourceReadContext(authority, sourceState)
                        }
                    )
            },
            ProjectBoundIntellijSourceReadPort.create(
                project = project,
                authority = authority,
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
    val unexpectedFailures = mutableListOf<IntellijReadUnexpectedFailure>()

    override fun unexpected(failure: IntellijReadUnexpectedFailure) {
        unexpectedFailures += failure
    }

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
