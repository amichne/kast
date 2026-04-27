package io.github.amichne.kast.cli

import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import kotlinx.serialization.Serializable

@Serializable
internal enum class SmokeCheckStatus { PASS, FAIL, SKIP }

@Serializable
internal data class SmokeCheck(
    val name: String,
    val status: SmokeCheckStatus,
    val message: String? = null,
)

@Serializable
internal data class SmokeReport(
    val workspaceRoot: String,
    val cliVersion: String,
    val checks: List<SmokeCheck>,
    val passCount: Int,
    val failCount: Int,
    val skipCount: Int,
    val ok: Boolean,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    fun toMarkdown(): String = buildString {
        val icon = if (ok) "✅" else "❌"
        appendLine("# Kast Smoke Report $icon")
        appendLine()
        appendLine("**CLI version:** $cliVersion")
        appendLine("**Workspace root:** $workspaceRoot")
        appendLine("**Result:** $passCount passed, $failCount failed, $skipCount skipped")
        appendLine()
        appendLine("| Check | Status | Message |")
        appendLine("|-------|--------|---------|")
        for (check in checks) {
            val statusIcon = when (check.status) {
                SmokeCheckStatus.PASS -> "✅ PASS"
                SmokeCheckStatus.FAIL -> "❌ FAIL"
                SmokeCheckStatus.SKIP -> "⏭ SKIP"
            }
            appendLine("| ${check.name} | $statusIcon | ${check.message.orEmpty().replace("|", "\\|")} |")
        }
    }.trimEnd()
}
