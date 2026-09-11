package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val SELECTOR_HEX_RADIX = 16

/** Computes detached identity only; this function cannot create or restore a read capability. */
fun symbolSelectorFingerprint(
    identity: SemanticReadIdentity,
    scope: SymbolSearchScope,
    evidence: CompilerGroundedSymbolEvidence,
    constraints: SymbolDiscoveryConstraints,
): SymbolSelectorFingerprint {
    val canonical = buildString {
        appendSelectorField(identity.workspaceRoot.value)
        appendSelectorField(identity.revisionKey.value)
        scope.appendSelectorFields(this)
        constraints.fingerprintFields().forEach(::appendSelectorField)
        appendSelectorField(evidence.file.stableValue)
        appendSelectorField(evidence.range.startInclusive.toString())
        appendSelectorField(evidence.range.endExclusive.toString())
        appendSelectorField(evidence.name.value)
        appendSelectorField(evidence.kind.name)
        appendSelectorField(evidence.compilerIdentity.value)
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
    val raw =
        digest.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(SELECTOR_HEX_RADIX).padStart(2, '0')
        }
    return when (val parsed = SymbolSelectorFingerprint.parse(raw)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("SHA-256 projection is a canonical selector fingerprint")
    }
}

private fun SymbolSearchScope.appendSelectorFields(target: StringBuilder) {
    when (this) {
        is SymbolSearchScope.ExactFile -> {
            target.appendSelectorField("exact-file")
            target.appendSelectorField(file.value)
        }
        is SymbolSearchScope.Module -> {
            target.appendSelectorField("module")
            target.appendSelectorField(module.value)
        }
        is SymbolSearchScope.SourceSet -> {
            target.appendSelectorField("source-set")
            target.appendSelectorField(project.buildRoot.value)
            target.appendSelectorField(project.projectPath.value)
            target.appendSelectorField(sourceSet.value)
        }
        is SymbolSearchScope.GradleProject -> {
            target.appendSelectorField("gradle-project")
            target.appendSelectorField(project.buildRoot.value)
            target.appendSelectorField(project.projectPath.value)
        }
        is SymbolSearchScope.Workspace -> {
            target.appendSelectorField("workspace")
            target.appendSelectorField(libraries.name)
        }
    }
    target.appendSelectorField(sourceKinds.name)
    target.appendSelectorField(generatedSources.name)
}

private fun StringBuilder.appendSelectorField(value: String) {
    append(value.toByteArray(StandardCharsets.UTF_8).size)
    append(':')
    append(value)
}
