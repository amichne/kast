package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryPresentationExecution
import io.github.amichne.kast.query.protocol.CanonicalQueryProtocol
import io.github.amichne.kast.query.protocol.CanonicalQueryReferences
import io.github.amichne.kast.query.protocol.QueryResultRetentionEvidence
import io.github.amichne.kast.query.protocol.QueryResultRetentionObservation
import io.github.amichne.kast.query.protocol.QueryStateStore
import io.github.amichne.kast.query.service.QueryNanoClock
import io.github.amichne.kast.query.service.QueryService
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.ValueFlowCompilerPort
import io.github.amichne.kast.relation.contract.ValueProducerSeedCompilerPort
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.intellij.IntellijValueFlowCompilerAdapter
import io.github.amichne.kast.relation.intellij.ProjectBoundIntellijRelationPort
import io.github.amichne.kast.relation.service.RelationService
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadContextPort
import io.github.amichne.kast.source.contract.SourceReadContinuationPort
import io.github.amichne.kast.source.contract.SourceReadRejection
import io.github.amichne.kast.source.intellij.ProjectBoundIntellijSourceReadPort
import io.github.amichne.kast.source.service.SourceReadService
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolSelector
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
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijSemanticSourceFileAdmission
import java.nio.file.Path
import java.security.MessageDigest

/** One case owns production ports and the existing retained-query store. */
internal class NativePacketQueryServices(
    private val project: Project,
    root: CanonicalWorkspaceRoot,
    private val model: WorkspaceSearchScopeModel,
    private val files: List<VirtualFile>,
) {
    constructor(
        project: Project,
        root: CanonicalWorkspaceRoot,
        model: WorkspaceSearchScopeModel,
        file: VirtualFile,
    ) : this(project = project, root = root, model = model, files = listOf(file))

    val lease = SemanticReadLease(root, EvidenceGeneration.parse(1).packetQueryRefined())
    private val stamps = files.associateWith { it.modificationStamp }
    private val sourceState =
        WorkspaceStateIdentity.parse(java.util.HexFormat.of().formatHex(sourceDigest())).packetQueryRefined()

    private fun current() =
        ReadAction.compute<Boolean, RuntimeException> {
            !project.isDisposed && stamps.all { (file, stamp) -> file.isValid && file.modificationStamp == stamp }
        }

    private fun sourceDigest(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        files
            .sortedBy { it.url }
            .forEach { file ->
                digest.update(file.url.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                digest.update(MessageDigest.getInstance("SHA-256").digest(file.contentsToByteArray()))
            }
        return digest.digest()
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
    val observation = io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation.None
    val symbols =
        ProjectBoundIntellijSymbolPorts.create(
            project = project,
            authority = lease,
            model = model,
            fileAdmission = admission,
            observation = observation,
            limits = ReadLimits.Default,
        )
    val references = CanonicalQueryReferences(model)
    private val compiler = IntellijValueFlowCompilerAdapter(observation)
    private val compiledModel = WorkspaceSearchScopeModelCompilation.Compiled(model)
    var nativeFlowReads = 0
        private set

    var lastExecution: QueryExecutionResult? = null
        private set

    var lastRetention: QueryResultRetentionEvidence? = null
        private set

    private val valueFlow = ValueFlowCompilerPort { request ->
        compiler.read(project = project, current = lease, request = request, model = compiledModel).also { read ->
            nativeFlowReads++
        }
    }
    private val producers =
        object : ValueProducerSeedCompilerPort {
            override suspend fun seed(request: ValueProducerSeedRequest) =
                compiler.seed(project = project, current = lease, request = request, model = compiledModel)

            override suspend fun revalidate(selector: SymbolSelector, budget: RelationBudget) =
                compiler.revalidate(
                    project = project,
                    current = lease,
                    selector = selector,
                    budget = budget,
                    model = compiledModel,
                )

            override suspend fun revalidateSite(request: ValueSiteRevalidationRequest) =
                compiler.revalidateSite(project = project, current = lease, request = request, model = compiledModel)
        }
    private val relations =
        RelationService(
            validation,
            ProjectBoundIntellijRelationPort.create(
                project = project,
                authority = lease,
                model = model,
                fileAdmission = admission,
                observation = observation,
                limits = ReadLimits.Default,
            ),
        )
    private val source =
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
                limits = ReadLimits.Default,
                observation = observation,
            ),
        )
    private val traversal =
        TraversalBudget(
            records = resources.resultLimit,
            returnedBytes = TraversalByteLimit.parse(RETURNED_BYTES).packetQueryRefined(),
            workUnits = resources.workUnitLimit,
            elapsedTime = resources.elapsedTimeLimit,
            depth = TraversalDepthLimit.parse(8).packetQueryRefined(),
            frontier = TraversalFrontierLimit.parse(100).packetQueryRefined(),
            oneHop = RelationBudget(resources, RelationByteLimit.parse(RETURNED_BYTES).packetQueryRefined()),
        )

    private fun operations(presentation: QueryPresentationExecution?, checkpoint: NativePacketQueryCheckpoint?) =
        QueryService(
            discovery = SymbolDiscoveryService(validation, symbols.discovery),
            exact = SymbolExactService(validation, symbols.exact),
            source = source,
            relations = relations,
            traversal = hostedTraversalOperations(relations, observation),
            traversalCeiling = traversal,
            clock = checkpoint ?: QueryNanoClock(System::nanoTime),
            valueFlow =
                ValueFlowCompilerPort { request ->
                    valueFlow.read(request).also { checkpoint?.observed(it) }
                },
            presentation = presentation,
        )

    private val state = QueryStateStore()
    val protocol = protocol()

    fun protocol(
        presentation: QueryPresentationExecution? = null,
        checkpoint: NativePacketQueryCheckpoint? = null,
    ): CanonicalQueryProtocol {
        val operations = operations(presentation, checkpoint)
        return CanonicalQueryProtocol(
            operations =
                QueryOperations { request ->
                    checkpoint?.entering(request)
                    operations.run(request).also { result ->
                        lastExecution = result
                        checkpoint?.completed(result)
                    }
                },
            authority = references,
            state = state,
            producerSeeds = producers,
            retentionObservation = QueryResultRetentionObservation { lastRetention = it },
        )
    }

    companion object {
        const val RETURNED_BYTES = 4_000_000L
        val resources =
            ResourceBudget(
                ResultLimit.parse(256).packetQueryRefined(),
                WorkUnitLimit.parse(100_000).packetQueryRefined(),
                ElapsedTimeLimitMillis.parse(60_000).packetQueryRefined(),
            )
        val budget = QueryBudget(resources, QueryByteLimit.parse(RETURNED_BYTES).packetQueryRefined())
    }
}

internal fun <V, F> Refinement<V, F>.packetQueryRefined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Packet query fixture prerequisite failed: $failure")
    }
