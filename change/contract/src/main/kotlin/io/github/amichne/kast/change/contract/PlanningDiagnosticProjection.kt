package io.github.amichne.kast.change.contract

import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticFact

internal fun diagnosticProjection(result: DiagnosticCheckResult.Complete): String = buildString {
    val scope = result.batch.scope
    appendPlanningField(scope.lease.workspaceRoot.value)
    appendPlanningField(scope.lease.identity.revisionKey.value)
    scope.files.forEach { file -> appendPlanningField(file.value) }
    result.coverage.analyzedFiles.forEach { file -> appendPlanningField(file.value) }
    result.batch.facts.sortedBy(::diagnosticFactProjection).forEach { fact ->
        appendPlanningField(diagnosticFactProjection(fact))
    }
}

private fun diagnosticFactProjection(fact: DiagnosticFact): String = buildString {
    appendPlanningField(fact.location.file.value)
    appendPlanningField(fact.location.range.start.value.toString())
    appendPlanningField(fact.location.range.endExclusive.value.toString())
    appendPlanningField(fact.severity.name)
    appendPlanningField(fact.code.value)
    appendPlanningField(fact.message.value)
}
