package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.project.Project
import io.github.amichne.kast.diagnostic.intellij.ProjectBoundIntellijDiagnosticPorts
import io.github.amichne.kast.diagnostic.service.DiagnosticService
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.protocol.CanonicalQueryReferences
import io.github.amichne.kast.relation.intellij.ProjectBoundIntellijRelationPort
import io.github.amichne.kast.relation.service.RelationService
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadContextPort
import io.github.amichne.kast.source.contract.SourceReadRejection
import io.github.amichne.kast.source.intellij.IntellijSourceReadContinuations
import io.github.amichne.kast.source.intellij.ProjectBoundIntellijSourceReadPort
import io.github.amichne.kast.source.service.SourceReadService
import io.github.amichne.kast.symbol.intellij.ProjectBoundIntellijSymbolPorts
import io.github.amichne.kast.symbol.service.SymbolDiscoveryService
import io.github.amichne.kast.symbol.service.SymbolExactService
import io.github.amichne.kast.workspace.contract.SemanticReadValidation
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext

/** Ports share one request's admitted authority, model, scope, policy and observation. Never retained across reads. */
internal class HostedSemanticServices(private val project: Project, private val context: HostedSemanticReadContext) {
    val budgets = HostedSemanticBudgets(context.limits, context.executionBudget)
    private val capture =
        io.github.amichne.kast.symbol.intellij.IntellijExactRevalidationCapture(
            context.model,
            context.observation,
            context.executionBudget.work.effective.value,
            context.limits,
        )
    private val revalidationStore = project.getService(HostedExactRevalidationStore::class.java)
    val revalidationReferences = revalidationStore.references()
    val revalidation =
        io.github.amichne.kast.symbol.service.ExactRevalidationService(
            context.validation,
            io.github.amichne.kast.symbol.intellij.ProjectBoundExactRevalidationPort(
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
            project
                .getService(HostedReferenceStore::class.java)
                .transport(context.authority.reference, context.limits, context.observation),
            exactIssued = { selector, token, canonical ->
                val retained =
                    when (val locator = capture.locator(selector)) {
                        is Refinement.Refined -> revalidationStore.retain(token, canonical, locator.value)
                        is Refinement.Rejected -> locator
                    }
                context.observation.count(
                    when (retained) {
                        is Refinement.Refined ->
                            io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
                                .REVALIDATION_LOCATORS_RETAINED
                        is Refinement.Rejected ->
                            io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
                                .REVALIDATION_LOCATORS_REJECTED
                    }
                )
            },
        )

    fun source(continuations: IntellijSourceReadContinuations) =
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
        )
    }
    val diagnostics by lazy { DiagnosticService(context.validation, diagnosticPorts.compiler) }
    val diagnosticScans by lazy {
        io.github.amichne.kast.diagnostic.service.DiagnosticScanService(
            context.validation,
            diagnosticPorts.enumeration,
            diagnostics,
        )
    }
}
