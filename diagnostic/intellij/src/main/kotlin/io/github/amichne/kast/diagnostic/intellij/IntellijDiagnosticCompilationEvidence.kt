package io.github.amichne.kast.diagnostic.intellij

import com.intellij.openapi.diagnostic.Logger
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerRejection
import io.github.amichne.kast.diagnostic.contract.DiagnosticFact
import io.github.amichne.kast.diagnostic.contract.DiagnosticLimitationReason
import io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bounded detached compiler-factory identity; messages and source can never enter log evidence. */
@JvmInline
internal value class DiagnosticFactoryEvidence private constructor(val value: String) {
    companion object {
        fun observe(fact: DiagnosticFact): DiagnosticFactoryObservation =
            if (Regex("[A-Z][A-Z0-9_]{0,95}").matches(fact.code.value)) {
                DiagnosticFactoryObservation.Named(DiagnosticFactoryEvidence(fact.code.value))
            } else {
                DiagnosticFactoryObservation.Withheld
            }
    }
}

internal sealed interface DiagnosticFactoryObservation {
    data class Named(val factory: DiagnosticFactoryEvidence) : DiagnosticFactoryObservation
    data object Withheld : DiagnosticFactoryObservation
}

@JvmInline
internal value class DiagnosticObservationCount private constructor(val value: Int) {
    companion object {
        fun observed(value: Int): DiagnosticObservationCount {
            require(value >= 0)
            return DiagnosticObservationCount(value)
        }
    }
}

/** Error count stays complete even when the bounded factory-code projection withholds identities. */
internal data class DiagnosticErrorEvidence private constructor(
    val errorCount: DiagnosticObservationCount,
    val factories: List<DiagnosticFactoryEvidence>,
    val withheldFactCount: DiagnosticObservationCount,
) {
    companion object {
        fun observe(facts: List<DiagnosticFact>): DiagnosticErrorEvidence {
            val errors = facts.filter { it.severity == DiagnosticSeverity.ERROR }
            val observations = errors.map(DiagnosticFactoryEvidence::observe)
            val factories = observations.filterIsInstance<DiagnosticFactoryObservation.Named>()
                .map { it.factory }.distinct().sortedBy { it.value }.take(16)
            return DiagnosticErrorEvidence(
                DiagnosticObservationCount.observed(errors.size),
                factories,
                DiagnosticObservationCount.observed(observations.count { observation ->
                    observation !is DiagnosticFactoryObservation.Named || observation.factory !in factories
                }),
            )
        }
    }
}

/** Exact-scope terminal compiler evidence, intentionally excluding files, ranges, and messages. */
internal sealed interface IntellijDiagnosticCompilationEvidence {
    data class Complete(
        val analyzedFiles: DiagnosticObservationCount,
        val errors: DiagnosticErrorEvidence,
    ) : IntellijDiagnosticCompilationEvidence

    data class Qualified(
        val analyzedFiles: DiagnosticObservationCount,
        val errors: DiagnosticErrorEvidence,
        val limitations: Set<DiagnosticLimitationReason>,
    ) : IntellijDiagnosticCompilationEvidence

    data class Rejected(val reason: DiagnosticCompilerRejection) : IntellijDiagnosticCompilationEvidence
    data object Cancelled : IntellijDiagnosticCompilationEvidence
}

internal fun interface IntellijDiagnosticCompilationObserver {
    fun observe(evidence: IntellijDiagnosticCompilationEvidence)
}

/** Total projection retains terminal status and all error counts without exposing semantic payload. */
internal fun DiagnosticCompilation.observation(): IntellijDiagnosticCompilationEvidence = when (this) {
    is DiagnosticCompilation.Complete -> IntellijDiagnosticCompilationEvidence.Complete(
        DiagnosticObservationCount.observed(coverage.analyzedFiles.size),
        DiagnosticErrorEvidence.observe(batch.facts),
    )
    is DiagnosticCompilation.Qualified -> IntellijDiagnosticCompilationEvidence.Qualified(
        DiagnosticObservationCount.observed(coverage.analyzedFiles.size),
        DiagnosticErrorEvidence.observe(batch.facts),
        coverage.limitations.map { it.reason }.toSet(),
    )
    is DiagnosticCompilation.Rejected -> IntellijDiagnosticCompilationEvidence.Rejected(reason)
}

internal object LoggingIntellijDiagnosticCompilationObserver : IntellijDiagnosticCompilationObserver {
    override fun observe(evidence: IntellijDiagnosticCompilationEvidence) {
        Logger.getInstance("io.github.amichne.kast.diagnosticCompilation")
            .info("Kast diagnostic compilation: ${evidence.logFields()}")
    }
}

/** Structured log projection is confined to this adapter and serializes only admitted evidence. */
internal fun IntellijDiagnosticCompilationEvidence.logFields(): JsonObject = buildJsonObject {
    put("stage", "exact-scope")
    when (val evidence = this@logFields) {
        is IntellijDiagnosticCompilationEvidence.Complete -> {
            put("status", "complete")
            put("analyzedFiles", evidence.analyzedFiles.value)
            put("errors", evidence.errors.logFields())
        }
        is IntellijDiagnosticCompilationEvidence.Qualified -> {
            put("status", "qualified")
            put("analyzedFiles", evidence.analyzedFiles.value)
            put("errors", evidence.errors.logFields())
            put("limitations", JsonArray(evidence.limitations.sortedBy { it.name }.map { JsonPrimitive(it.name) }))
        }
        is IntellijDiagnosticCompilationEvidence.Rejected -> {
            put("status", "rejected")
            put("reason", evidence.reason.name)
        }
        IntellijDiagnosticCompilationEvidence.Cancelled -> put("status", "cancelled")
    }
}

private fun DiagnosticErrorEvidence.logFields(): JsonObject = buildJsonObject {
    put("count", errorCount.value)
    put("factoryCodes", JsonArray(factories.map { JsonPrimitive(it.value) }))
    put("withheldFactCount", withheldFactCount.value)
}
