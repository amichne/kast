package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.project.Project
import io.github.amichne.kast.diagnostic.intellij.ProjectBoundIntellijDiagnosticPorts
import io.github.amichne.kast.diagnostic.service.DiagnosticScanService
import io.github.amichne.kast.diagnostic.service.DiagnosticService
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.query.protocol.CanonicalQueryReferences
import io.github.amichne.kast.query.protocol.CanonicalSelectorDecoding
import io.github.amichne.kast.query.protocol.ExactReferenceReacquisition
import io.github.amichne.kast.query.protocol.QueryReferenceTransport
import io.github.amichne.kast.query.protocol.ReacquiringQueryReferences
import io.github.amichne.kast.query.protocol.decodingFailure
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.ValueFlowCompilerPort
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueModelDeclarationRead
import io.github.amichne.kast.relation.contract.ValueModelSiteRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedCompilerPort
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
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
import io.github.amichne.kast.symbol.contract.ExactRevalidationPolicy
import io.github.amichne.kast.symbol.contract.ExactRevalidationResult
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.intellij.IntellijExactRevalidationCapture
import io.github.amichne.kast.symbol.intellij.ProjectBoundExactRevalidationPort
import io.github.amichne.kast.symbol.intellij.ProjectBoundIntellijSymbolPorts
import io.github.amichne.kast.symbol.service.ExactRevalidationService
import io.github.amichne.kast.symbol.service.SymbolDiscoveryService
import io.github.amichne.kast.symbol.service.SymbolExactService
import io.github.amichne.kast.workspace.contract.LiveSemanticReadFailure
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadValidation
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext

/** Ports share one request's admitted authority, model, scope, policy and observation. Never retained across reads. */
internal class HostedSemanticServices(
    private val project: Project,
    private val context: HostedSemanticReadContext,
    transport: QueryReferenceTransport,
) {
    val budgets = HostedSemanticBudgets(context.limits, context.executionBudget)
    private val capture =
        IntellijExactRevalidationCapture(
            context.model,
            context.observation,
            context.executionBudget.work.effective.value,
            context.limits,
        )
    private val revalidationStore = project.getService(HostedExactRevalidationStore::class.java)
    val revalidationReferences = revalidationStore.references()
    val revalidation =
        ExactRevalidationService(
            context.validation,
            ProjectBoundExactRevalidationPort(
                project,
                context.model,
                context.sourceFiles,
                capture,
                context.observation,
                context.limits,
            ),
        )
    private val symbols by lazy {
        ProjectBoundIntellijSymbolPorts.create(
            project,
            context.authority,
            context.model,
            context.sourceFiles,
            context.observation,
            context.limits,
            capture,
        )
    }
    val discovery = SymbolDiscoveryService(context.validation, symbols.discovery)
    val exact = SymbolExactService(context.validation, symbols.exact)
    val relations =
        RelationService(
            context.validation,
            ProjectBoundIntellijRelationPort.create(
                project = project,
                authority = context.authority,
                model = context.model,
                fileAdmission = context.sourceFiles,
                observation = context.observation,
                limits = context.limits,
            ),
        )
    val references =
        CanonicalQueryReferences(
            context.model,
            transport,
            exactIssued = { selector, token, canonical ->
                val retained =
                    when (val locator = capture.locator(selector)) {
                        is Refinement.Refined -> revalidationStore.retain(token, canonical, locator.value)
                        is Refinement.Rejected -> locator
                    }
                context.observation.count(
                    when (retained) {
                        is Refinement.Refined -> IntellijReadCounter.REVALIDATION_LOCATORS_RETAINED
                        is Refinement.Rejected -> IntellijReadCounter.REVALIDATION_LOCATORS_REJECTED
                    }
                )
            },
        )

    private val acquisitionAccounting = ReadAcquisitionAccounting()
    private val valueCompiler = IntellijValueFlowCompilerAdapter(context.observation, context.limits)
    private val valueModel = WorkspaceSearchScopeModelCompilation.Compiled(context.model)
    val valueFlow =
        object : ValueFlowCompilerPort {
            override suspend fun read(request: ValueFlowRequest) =
                valueCompiler.read(project, context.authority, request, valueModel)
        }
    val producerSeeds =
        object : ValueProducerSeedCompilerPort {
            override suspend fun seed(request: ValueProducerSeedRequest): ValueProducerSeedRead {
                val started = System.nanoTime()
                val result = valueCompiler.seed(project, context.authority, request, valueModel)
                if (result is ValueProducerSeedRead.Seeded)
                    acquisitionAccounting.record(
                        result.examinedWorkUnits.value,
                        (System.nanoTime() - started).coerceAtLeast(0L),
                    )
                return result
            }

            override suspend fun revalidate(
                selector: SymbolSelector,
                budget: RelationBudget,
            ): ValueModelDeclarationRead {
                val started = System.nanoTime()
                val result = valueCompiler.revalidate(project, context.authority, selector, budget, valueModel)
                if (result is ValueModelDeclarationRead.Revalidated)
                    acquisitionAccounting.record(
                        result.examinedWorkUnits.value,
                        (System.nanoTime() - started).coerceAtLeast(0L),
                    )
                return result
            }

            override suspend fun revalidateSite(request: ValueSiteRevalidationRequest): ValueModelSiteRead {
                val started = System.nanoTime()
                val result = valueCompiler.revalidateSite(project, context.authority, request, valueModel)
                if (result is ValueModelSiteRead.Revalidated)
                    acquisitionAccounting.record(
                        result.examinedWorkUnits.value,
                        (System.nanoTime() - started).coerceAtLeast(0L),
                    )
                return result
            }
        }
    val readReferences =
        ReacquiringQueryReferences(
            references,
            ExactReferenceReacquisition { token, current ->
                reacquireRead(token, current)
            },
            acquisitionAccounting::remaining,
        )

    private suspend fun reacquireRead(
        token: ProtocolText,
        current: SemanticReadAuthority,
    ): CanonicalSelectorDecoding<SymbolSelector> {
        val budget =
            when (val remaining = acquisitionAccounting.remaining(context.executionBudget.resources)) {
                is Refinement.Refined -> remaining.value
                is Refinement.Rejected -> return CanonicalSelectorDecoding.Rejected(remaining.failure.decodingFailure())
            }
        val locator =
            when (val located = revalidationReferences.locate(token)) {
                is Refinement.Refined -> located.value
                is Refinement.Rejected -> return CanonicalSelectorDecoding.Rejected(located.failure.decodingFailure())
            }
        val port =
            ProjectBoundExactRevalidationPort(
                project,
                context.model,
                context.sourceFiles,
                capture,
                context.observation,
                context.limits,
                ExactRevalidationPolicy.CURRENT_DECLARATION,
                budget,
            )
        val service =
            ExactRevalidationService(
                context.validation,
                port,
                ExactRevalidationPolicy.CURRENT_DECLARATION,
            )
        val started = System.nanoTime()
        val capturedWork = capture.chargedWork
        val result = service.revalidate(locator, current)
        acquisitionAccounting.record(
            capture.chargedWork - capturedWork + port.examinedReacquisitionWork,
            (System.nanoTime() - started).coerceAtLeast(0L),
        )
        return when (result) {
            is ExactRevalidationResult.Reacquired -> CanonicalSelectorDecoding.Decoded(result.selector)
            is ExactRevalidationResult.Rejected -> CanonicalSelectorDecoding.Rejected(result.reason.decodingFailure())
        }
    }

    fun source(continuations: SourceReadContinuationPort) =
        SourceReadService(
            SourceReadContextPort { expected ->
                when (context.validation.validate(expected)) {
                    SemanticReadValidation.CURRENT -> Refinement.Refined(SourceReadContext.Live(context.authority))
                    SemanticReadValidation.UNAVAILABLE -> Refinement.Rejected(SourceReadRejection.WORKSPACE_NOT_READY)
                    SemanticReadValidation.ROOT_MISMATCH ->
                        Refinement.Rejected(SourceReadRejection.WORKSPACE_ROOT_MISMATCH)
                    SemanticReadValidation.MOVED -> Refinement.Rejected(SourceReadRejection.STALE_GENERATION)
                }
            },
            ProjectBoundIntellijSourceReadPort.create(
                project,
                context.authority,
                context.model,
                context.sourceFiles,
                continuations,
                context.limits,
                context.observation,
            ),
        )

    val diagnosticPorts by lazy {
        ProjectBoundIntellijDiagnosticPorts.create(
            project = project,
            authority = context.authority,
            model = context.model,
            fileAdmission = context.sourceFiles,
            limits = context.limits,
            scopeTimeLimit = context.timeAllowance.diagnosticScope,
            observation = context.observation,
        )
    }
    val diagnostics by lazy { DiagnosticService(context.validation, diagnosticPorts.compiler) }
    val diagnosticScans by lazy {
        DiagnosticScanService(
            context.validation,
            diagnosticPorts.enumeration,
            diagnostics,
        )
    }
}

internal fun admitHostedSemanticServices(
    project: Project,
    context: HostedSemanticReadContext,
): Refinement<HostedSemanticServices, LiveSemanticReadFailure> =
    when (
        val admitted =
            project
                .getService(HostedReferenceStore::class.java)
                .transport(context.authority, context.limits, context.observation)
    ) {
        is Refinement.Refined -> Refinement.Refined(HostedSemanticServices(project, context, admitted.value))
        is Refinement.Rejected -> admitted
    }
