package kast.baseline.verification

import kast.baseline.program.*

fun main(args: Array<String>) {
    when (args.singleOrNull()) {
        "projection" -> { println(programProjection()); return }
        "program-schema" -> { println(programSchema()); return }
        "receipt-schema" -> { println(receiptSchema()); return }
    }
    val checks = when (args.singleOrNull()) {
        "policy" -> policyChecks()
        "readiness" -> readinessChecks()
        "trust" -> trustChecks()
        "graph" -> graphChecks()
        else -> error("expected policy, readiness, trust, graph, projection, program-schema, or receipt-schema")
    }
    checks.forEach { println("PASS $it") }
}
