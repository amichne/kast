package conventions.jsoncontracts

/** Syntax-based ratchet: an allowance admits exactly one named expression fingerprint and occurrence count. */
class JsonContractGuard {
    fun verify(sources: List<JsonContractSource>, baseline: String): JsonContractReport {
        val admitted =
            when (val result = JsonContractBaseline.parse(baseline)) {
                is JsonContractBaseline.Admission.Accepted -> result.baseline
                is JsonContractBaseline.Admission.Rejected ->
                    return report(emptyList(), listOf(JsonContractViolation.InvalidBaseline(result.reason)))
            }
        val findings = mutableListOf<JsonContractFinding>()
        val violations = mutableListOf<JsonContractViolation>()
        KotlinJsonContractScanner().use { scanner ->
            for (source in sources.sortedBy { it.path }) {
                when (val result = scanner.scan(source)) {
                    is KotlinJsonContractScanner.Scan.Accepted -> findings += result.findings
                    is KotlinJsonContractScanner.Scan.Rejected -> violations += result.violation
                }
            }
        }
        for (finding in findings) {
            val allowance = admitted.allowances[finding.fingerprint]
            when {
                allowance == null -> violations += JsonContractViolation.Unapproved(finding)
                finding.count > allowance.count ->
                    violations += JsonContractViolation.CountExceeded(finding, allowance.count)
            }
        }
        val observed = findings.associateBy { it.fingerprint }
        for (allowance in admitted.allowances.values) {
            val count = observed[allowance.fingerprint]?.count ?: 0
            if (count < allowance.count) violations += JsonContractViolation.StaleAllowance(allowance, count)
        }
        return report(findings, violations)
    }

    private fun report(findings: List<JsonContractFinding>, violations: List<JsonContractViolation>) =
        JsonContractReport(JsonContractEvidence.KOTLIN_PSI_SYNTAX, findings, violations)
}
