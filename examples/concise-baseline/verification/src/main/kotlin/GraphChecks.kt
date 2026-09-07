package kast.baseline.verification

import kast.baseline.program.*

fun graphChecks(): List<String> {
    val checks = mutableListOf<String>()
    fun prove(name: String, predicate: Boolean) { check(predicate) { name }; checks += name }
    val nodes = BaselineProgram.tasks
    prove("task-graph-admits", TaskGraph.parse(nodes) is GraphAdmission.Admitted)
    prove("duplicate-task-rejects", TaskGraph.parse(nodes + nodes.first()) == GraphAdmission.Rejected(GraphFailure.DUPLICATE_NODE))
    prove("missing-predecessor-rejects", TaskGraph.parse(nodes.drop(1)) == GraphAdmission.Rejected(GraphFailure.MISSING_DEPENDENCY))
    val cyclic = nodes.map { if (it.id == GateId.POLICY) it.copy(dependencies = setOf(GateId.TRUST)) else it }
    prove("cycle-rejects", TaskGraph.parse(cyclic) == GraphAdmission.Rejected(GraphFailure.CYCLE))
    prove("waves-are-mechanically-derived", BaselineProgram.graph().waves.first().toSet() ==
        setOf(GateId.POLICY, GateId.READINESS, GateId.GRAPH, GateId.CLI))
    prove("projection-is-deterministic", programProjection() == programProjection())
    prove("every-original-requirement-is-mapped", BaselineProgram.requirements.map { it.requirement }.toSet() == Requirement.entries.toSet())
    prove("read-module-cannot-reach-preparation", ExampleModule.READ.dependencies == setOf(":model"))
    val coordinates = ReceiptCoordinates("a".repeat(64), "b".repeat(40), "c".repeat(40),
        "d".repeat(64), "e".repeat(64), mapOf(GateId.POLICY to "f".repeat(64)))
    val artifacts = mapOf("output.log" to "0".repeat(64))
    val document = ReceiptDocument(coordinates, "PASS", listOf("observed-test"), artifacts)
    prove("matching-receipt-admits", VerifiedReceipt.parse(document, coordinates, artifacts, listOf("observed-test")) is ReceiptAdmission.Verified)
    prove("changed-head-rejects-receipt", VerifiedReceipt.parse(document, coordinates.copy(head = "1".repeat(40)), artifacts, listOf("observed-test")) ==
        ReceiptAdmission.Rejected(ReceiptFailure.COORDINATES_CHANGED))
    prove("changed-command-rejects-receipt", VerifiedReceipt.parse(document, coordinates.copy(command = "2".repeat(64)), artifacts, listOf("observed-test")) ==
        ReceiptAdmission.Rejected(ReceiptFailure.COORDINATES_CHANGED))
    prove("changed-dependency-rejects-receipt", VerifiedReceipt.parse(document, coordinates.copy(dependencies = emptyMap()), artifacts, listOf("observed-test")) ==
        ReceiptAdmission.Rejected(ReceiptFailure.COORDINATES_CHANGED))
    prove("changed-input-rejects-receipt", VerifiedReceipt.parse(document, coordinates.copy(inputs = "3".repeat(64)), artifacts, listOf("observed-test")) ==
        ReceiptAdmission.Rejected(ReceiptFailure.COORDINATES_CHANGED))
    prove("changed-program-rejects-receipt", VerifiedReceipt.parse(document, coordinates.copy(program = "4".repeat(64)), artifacts, listOf("observed-test")) ==
        ReceiptAdmission.Rejected(ReceiptFailure.COORDINATES_CHANGED))
    prove("changed-artifact-rejects-receipt", VerifiedReceipt.parse(document, coordinates, mapOf("output.log" to "5".repeat(64)), listOf("observed-test")) ==
        ReceiptAdmission.Rejected(ReceiptFailure.ARTIFACT_CHANGED))
    prove("qualified-is-not-pass", VerifiedReceipt.parse(document.copy(status = "Qualified"), coordinates, artifacts, listOf("observed-test")) ==
        ReceiptAdmission.Rejected(ReceiptFailure.NOT_PASS))
    prove("unimplemented-gates-are-not-success", nodes.filter { it.action is GateAction.Unimplemented }.map { it.id }.toSet() ==
        setOf(GateId.CLI, GateId.INSTALLED, GateId.CODEX, GateId.RETIREMENT, GateId.CLEAN_CHECKOUT,
            GateId.EXACT_HEAD_CI, GateId.INDEPENDENT_REVIEW, GateId.REVALIDATION))
    prove("invented-observation-rejects", VerifiedReceipt.parse(document.copy(observations = listOf("invented")),
        coordinates, artifacts, listOf("observed-test")) == ReceiptAdmission.Rejected(ReceiptFailure.MISSING_OBSERVATION))
    return checks
}
