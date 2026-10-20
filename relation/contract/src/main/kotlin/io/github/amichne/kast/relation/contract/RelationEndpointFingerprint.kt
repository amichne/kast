package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.fingerprintFields
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val RELATION_ENDPOINT_FINGERPRINT_LENGTH = 64
private const val HEX_RADIX = 16
private const val HEX_BYTE_WIDTH = 2
private const val UNSIGNED_BYTE_MASK = 0xff

enum class RelationEndpointFingerprintFailure {
    INVALID_SHA256
}

@JvmInline
value class RelationEndpointFingerprint private constructor(val value: String) {
    init {
        require(
            value.length == RELATION_ENDPOINT_FINGERPRINT_LENGTH &&
                value.all { character -> character in '0'..'9' || character in 'a'..'f' }
        )
    }

    companion object {
        fun parse(raw: String): Refinement<RelationEndpointFingerprint, RelationEndpointFingerprintFailure> =
            if (
                raw.length == RELATION_ENDPOINT_FINGERPRINT_LENGTH &&
                    raw.all { character -> character in '0'..'9' || character in 'a'..'f' }
            ) {
                Refinement.Refined(RelationEndpointFingerprint(raw))
            } else {
                Refinement.Rejected(RelationEndpointFingerprintFailure.INVALID_SHA256)
            }

        internal fun established(raw: String): RelationEndpointFingerprint = RelationEndpointFingerprint(raw)
    }
}

internal fun relationEndpointFingerprint(
    lease: SemanticReadAuthority,
    scope: SymbolSearchScope,
    evidence: CompilerGroundedSymbolEvidence,
    constraints: SymbolDiscoveryConstraints,
): RelationEndpointFingerprint {
    val canonical = buildString {
        appendFactField(lease.workspaceRoot.value)
        appendFactField(lease.identity.revisionKey.value)
        scope.appendEndpointFields(this)
        constraints.fingerprintFields().forEach(::appendFactField)
        appendFactField(evidence.file.stableValue)
        appendFactField(evidence.range.startInclusive.toString())
        appendFactField(evidence.range.endExclusive.toString())
        appendFactField(evidence.name.value)
        appendFactField(evidence.kind.name)
        appendFactField(evidence.compilerIdentity.value)
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
    return RelationEndpointFingerprint.established(
        digest.joinToString(separator = "") { byte ->
            (byte.toInt() and UNSIGNED_BYTE_MASK).toString(HEX_RADIX).padStart(HEX_BYTE_WIDTH, '0')
        }
    )
}

private fun SymbolSearchScope.appendEndpointFields(target: StringBuilder) {
    when (this) {
        is SymbolSearchScope.ExactFile -> {
            target.appendFactField("exact-file")
            target.appendFactField(file.value)
        }
        is SymbolSearchScope.Module -> {
            target.appendFactField("module")
            target.appendFactField(module.value)
        }
        is SymbolSearchScope.SourceSet -> {
            target.appendFactField("source-set")
            target.appendFactField(project.buildRoot.value)
            target.appendFactField(project.projectPath.value)
            target.appendFactField(sourceSet.value)
        }
        is SymbolSearchScope.GradleProject -> {
            target.appendFactField("gradle-project")
            target.appendFactField(project.buildRoot.value)
            target.appendFactField(project.projectPath.value)
        }
        is SymbolSearchScope.Workspace -> {
            target.appendFactField("workspace")
            target.appendFactField(libraries.name)
        }
    }
    target.appendFactField(sourceKinds.name)
    target.appendFactField(generatedSources.name)
}
