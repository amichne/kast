package io.github.amichne.kast.source.contract

import io.github.amichne.kast.symbol.contract.CandidateSelector
import io.github.amichne.kast.symbol.contract.detachedIdentityBytes
import io.github.amichne.kast.symbol.contract.fingerprintFields

internal fun SourceReadAnchor.detachedRetentionBytes(): Long =
    when (this) {
        is SourceReadAnchor.Source -> selector.detachedRetentionBytes()
        is SourceReadAnchor.Candidate -> {
            val candidateBytes =
                when (val value = selector) {
                    is CandidateSelector.Declaration ->
                        value.selection.candidate.projectedUtf8Size().value * SOURCE_ENCODING_BOUND_MULTIPLIER
                    is CandidateSelector.Range ->
                        value.file.path.value.length * SOURCE_RETAINED_TEXT_UNIT_BYTES + SOURCE_FIELD_STRUCTURE_BYTES
                }
            candidateBytes +
                selector.scope.detachedIdentityBytes() * SOURCE_ENCODING_BOUND_MULTIPLIER +
                selector.constraints.fingerprintFields().sumOf {
                    it.length * SOURCE_RETAINED_TEXT_UNIT_BYTES + SOURCE_FIELD_STRUCTURE_BYTES
                }
        }
        is SourceReadAnchor.Symbol -> {
            val value = selector
            SOURCE_ENCODING_BOUND_MULTIPLIER *
                (value.lease.toString().length +
                    value.file.stableValue.length +
                    value.name.value.length +
                    value.signature.canonicalEncoding().value.length +
                    value.compilerIdentity.value.length +
                    value.fingerprint.value.length +
                    value.qualifiedIdentity.toString().length) +
                value.scope.detachedIdentityBytes() * SOURCE_ENCODING_BOUND_MULTIPLIER +
                value.constraints.fingerprintFields().sumOf {
                    it.length * SOURCE_RETAINED_TEXT_UNIT_BYTES + SOURCE_FIELD_STRUCTURE_BYTES
                }
        }
    }

internal fun SourceSelector.detachedRetentionBytes(): Long {
    var current: SourceSelector = this
    var bytes =
        snapshot.toString().length * SOURCE_RETAINED_TEXT_UNIT_BYTES +
            snapshot.readScope.fingerprintFields().sumOf {
                it.length * SOURCE_RETAINED_TEXT_UNIT_BYTES + SOURCE_FIELD_STRUCTURE_BYTES
            }
    while (true) {
        bytes += SOURCE_STRUCTURAL_NODE_BYTES + current.fingerprint.value.length * SOURCE_RETAINED_TEXT_UNIT_BYTES
        current =
            when (val value = current) {
                is SourceSelector.RootRegion -> return bytes
                is SourceSelector.NestedRegion -> value.parent
                is SourceSelector.Entity -> {
                    bytes += value.name.toString().length * SOURCE_RETAINED_TEXT_UNIT_BYTES
                    value.parent
                }
            }
    }
}

internal const val SOURCE_ENCODING_BOUND_MULTIPLIER = 4L
internal const val SOURCE_RETAINED_TEXT_UNIT_BYTES = 8L
internal const val SOURCE_FIELD_STRUCTURE_BYTES = 128L
internal const val SOURCE_STRUCTURAL_NODE_BYTES = 512L
