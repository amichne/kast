package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.workspace.contract.CurrentWorkspaceReadLease
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val SHA_256_HEX_LENGTH = 64
private const val HEX_RADIX = 16

@JvmInline
value class ExactDeclarationFingerprint internal constructor(
    val value: String,
) {
    init {
        require(value.length == SHA_256_HEX_LENGTH && value.all(Char::isLowerCaseHexDigit))
    }
}

/**
 * Proof transition:
 * CurrentWorkspaceReadLease + SymbolSearchScope + ExactDeclarationEvidence to
 * ExactDeclarationFingerprint.
 *
 * Establishes a deterministic SHA-256 identity over an unambiguous, length-prefixed encoding of
 * the root, current epoch, complete scope policy, and detached native declaration evidence. Raw bytes
 * and digest text are created only inside this contract-owned sealing boundary.
 */
internal fun exactDeclarationFingerprint(
    lease: CurrentWorkspaceReadLease,
    scope: SymbolSearchScope,
    evidence: ExactDeclarationEvidence,
): ExactDeclarationFingerprint {
    val canonical = buildString {
        appendFingerprintField(lease.workspaceRoot.value)
        appendFingerprintField(lease.epoch.value.toString())
        scope.appendFingerprintFields(this)
        when (val file = evidence.file) {
            is SymbolDiscoveryFileIdentity.Workspace -> {
                appendFingerprintField("workspace-file")
                appendFingerprintField(file.path.value)
            }
            is SymbolDiscoveryFileIdentity.External -> {
                appendFingerprintField("external-file")
                appendFingerprintField(file.url.value)
            }
        }
        appendFingerprintField(evidence.range.startInclusive.toString())
        appendFingerprintField(evidence.range.endExclusive.toString())
        appendFingerprintField(evidence.name.value)
        when (val identity = evidence.qualifiedIdentity) {
            is ExactDeclarationQualifiedIdentity.Available -> {
                appendFingerprintField("qualified")
                appendFingerprintField(identity.value)
            }
            ExactDeclarationQualifiedIdentity.Unavailable ->
                appendFingerprintField("qualified-unavailable")
        }
        appendFingerprintField(evidence.runtimeType.value)
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
    return ExactDeclarationFingerprint(
        digest.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(HEX_RADIX).padStart(2, '0')
        },
    )
}

private fun SymbolSearchScope.appendFingerprintFields(target: StringBuilder) {
    when (this) {
        is SymbolSearchScope.ExactFile -> {
            target.appendFingerprintField("exact-file")
            target.appendFingerprintField(file.value)
        }
        is SymbolSearchScope.Module -> {
            target.appendFingerprintField("module")
            target.appendFingerprintField(module.value)
        }
        is SymbolSearchScope.SourceSet -> {
            target.appendFingerprintField("source-set")
            target.appendFingerprintField(project.buildRoot.value)
            target.appendFingerprintField(project.projectPath.value)
            target.appendFingerprintField(sourceSet.value)
        }
        is SymbolSearchScope.GradleProject -> {
            target.appendFingerprintField("gradle-project")
            target.appendFingerprintField(project.buildRoot.value)
            target.appendFingerprintField(project.projectPath.value)
        }
        is SymbolSearchScope.Workspace -> {
            target.appendFingerprintField("workspace")
            target.appendFingerprintField(libraries.name)
        }
    }
    target.appendFingerprintField(sourceKinds.name)
    target.appendFingerprintField(generatedSources.name)
}

private fun StringBuilder.appendFingerprintField(value: String) {
    val utf8Length = value.toByteArray(StandardCharsets.UTF_8).size
    append(utf8Length)
    append(':')
    append(value)
}

private fun Char.isLowerCaseHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'
