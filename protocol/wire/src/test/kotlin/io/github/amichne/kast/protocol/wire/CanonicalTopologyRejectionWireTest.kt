package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.TopologyBuildDigest
import io.github.amichne.kast.protocol.contract.TopologyBuildRejection
import io.github.amichne.kast.protocol.contract.TopologyBuildResult
import io.github.amichne.kast.protocol.contract.TopologyBuildStatus
import io.github.amichne.kast.protocol.contract.TopologyCoverageFailure
import io.github.amichne.kast.protocol.contract.TopologyCoverageCandidateEvidenceMismatch
import io.github.amichne.kast.protocol.contract.TopologyCoverageFileEvidence
import io.github.amichne.kast.protocol.contract.TopologyCoverageNode
import io.github.amichne.kast.protocol.contract.TopologyCoverageQualifiedIdentity
import io.github.amichne.kast.protocol.contract.TopologyCoverageProjectionRejection
import io.github.amichne.kast.protocol.contract.TopologyCoverageSourceHash
import io.github.amichne.kast.protocol.contract.TopologyCoverageSourceRootEvidence
import io.github.amichne.kast.protocol.contract.TopologyCoverageSourceRootProvenance
import io.github.amichne.kast.protocol.contract.TopologyCoverageSymbol
import io.github.amichne.kast.protocol.contract.TopologyCoverageSymbolKind
import io.github.amichne.kast.protocol.contract.TopologyCoverageWorkspaceEvidence
import io.github.amichne.kast.protocol.contract.TopologyExtractionRejection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CanonicalTopologyRejectionWireTest {
    @Test
    fun `generated topology result rejects malformed digest refinement`() {
        val digest = "a".repeat(64)
        val result = TopologyBuildResult(
            status = TopologyBuildStatus.PUBLISHED,
            generation = EvidenceGeneration.parse(17).refinedValue(),
            digest = TopologyBuildDigest.parse(digest).refinedValue(),
        )
        val binding = CanonicalOperationWireBindings.topologyBuild
        val evidence = EvidenceEnvelope(
            operation = binding.operation.id,
            generation = EvidenceGeneration.parse(17).refinedValue(),
            payload = result,
        )
        val encoded = binding.encodeOutcome(OperationOutcome.Complete(evidence)).encodedDocument()

        assertEquals(
            WireDecoding.Decoded(OperationOutcome.Complete(evidence)),
            binding.decodeOutcome(encoded),
        )
        listOf(digest.uppercase(), digest.dropLast(1)).forEach { malformedDigest ->
            assertEquals(
                WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.RESULT)),
                binding.decodeOutcome(encoded.replace(digest, malformedDigest)),
            )
        }
    }

    @Test
    fun `generated topology rejection preserves detail and rejects malformed variants`() {
        val rejection = TopologyBuildRejection.ExtractionFailed(
            text("topology/intellij/src/main/kotlin/TopologyK2Projection.kt"),
            TopologyExtractionRejection.SOURCE_CONTENT_MOVED,
        )
        val binding = CanonicalOperationWireBindings.topologyBuild
        val encoded = binding.encodeOutcome(OperationOutcome.Rejected(rejection)).encodedDocument()

        assertEquals(
            WireDecoding.Decoded(OperationOutcome.Rejected(rejection)),
            binding.decodeOutcome(encoded),
        )
        val malformed = listOf(
            encoded.replace(
                "\"file\":\"topology/intellij/src/main/kotlin/TopologyK2Projection.kt\",",
                "",
            ),
            encoded.replace("source-content-moved", "unknown-failure"),
            encoded.replace("extraction-failed", "unknown-rejection"),
            encoded.replace(
                "\"failure\":\"source-content-moved\"",
                "\"failure\":\"source-content-moved\",\"unexpected\":true",
            ),
        )
        malformed.forEach { document ->
            assertEquals(
                WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.REJECTION)),
                binding.decodeOutcome(document),
            )
        }
    }

    @Test
    fun `generated topology rejection preserves typed coverage projection failure`() {
        val rejection = TopologyBuildRejection.CoverageProjectionFailed(
            TopologyCoverageProjectionRejection.UNREPRESENTABLE_CONTENT_HASH,
        )
        val binding = CanonicalOperationWireBindings.topologyBuild
        val encoded = binding.encodeOutcome(OperationOutcome.Rejected(rejection)).encodedDocument()

        assertEquals(
            WireDecoding.Decoded(OperationOutcome.Rejected(rejection)),
            binding.decodeOutcome(encoded),
        )
        assertTrue(encoded.contains("\"reason\":\"coverage-projection-failed\""), encoded)
        assertTrue(encoded.contains("\"failure\":\"unrepresentable-content-hash\""), encoded)
    }

    @Test
    fun `generated coverage rejection preserves every exact mismatch`() {
        val node = TopologyCoverageNode(
            compilerIdentity = text("function|sample.alpha|-|||0"),
            file = text("src/main/kotlin/Alpha.kt"),
            range = range(10, 15),
        )
        val symbol = TopologyCoverageSymbol(
            node = node,
            fileEvidence = fileEvidence("src/main/kotlin/Alpha.kt", 'b'),
            name = text("alpha"),
            qualifiedIdentity = TopologyCoverageQualifiedIdentity.Available(text("sample.alpha")),
            kind = TopologyCoverageSymbolKind.FUNCTION,
        )
        val failure = TopologyCoverageFailure.admit(
            missing = setOf(text("src/main/kotlin/Missing.kt")),
            unexpected = setOf(text("src/main/kotlin/Unexpected.kt")),
            duplicateCandidates = setOf(text("src/main/kotlin/Duplicate.kt")),
            duplicateCompletions = setOf(text("src/main/kotlin/CompletedTwice.kt")),
            workspaceMismatches = setOf(text("src/main/kotlin/Moved.kt")),
            candidateEvidenceMismatches = setOf(
                TopologyCoverageCandidateEvidenceMismatch(
                    candidate = fileEvidence("src/main/kotlin/Alpha.kt", 'a'),
                    completed = fileEvidence("src/main/kotlin/Alpha.kt", 'b'),
                ),
            ),
            duplicateSymbols = setOf(node),
            missingEdgeTargets = setOf(node),
            mismatchedEdgeEndpoints = setOf(symbol),
        ).refinedValue()
        val rejection = TopologyBuildRejection.CoverageIncomplete(failure)
        val binding = CanonicalOperationWireBindings.topologyBuild

        val encoded = binding.encodeOutcome(OperationOutcome.Rejected(rejection)).encodedDocument()

        assertEquals(
            WireDecoding.Decoded(OperationOutcome.Rejected(rejection)),
            binding.decodeOutcome(encoded),
        )
        assertTrue(encoded.contains("\"candidateEvidenceMismatches\":[{\"candidate\":{\"workspace\":"), encoded)
        assertTrue(encoded.contains("\"sourceRoot\":{\"module\":\"fixture\""), encoded)
        assertTrue(encoded.contains("\"contentHash\":\"${"a".repeat(64)}\""), encoded)
        val malformed = listOf(
            encoded.replace("\"startInclusive\":10", "\"startInclusive\":-1"),
            encoded.replace("src/main/kotlin/Missing.kt", ""),
            encoded.replace("\"contentHash\":\"${"a".repeat(64)}\"", "\"contentHash\":\"bad\""),
        )
        malformed.forEach { document ->
            assertEquals(
                WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.REJECTION)),
                binding.decodeOutcome(document),
            )
        }
    }

    @Test
    fun `coverage nodes use structural ordering when delimiter-shaped text collides`() {
        val structurallyFirst = TopologyCoverageNode(
            compilerIdentity = text("a"),
            file = text("b\u0000c"),
            range = range(1, 2),
        )
        val structurallySecond = TopologyCoverageNode(
            compilerIdentity = text("a\u0000b"),
            file = text("c"),
            range = range(1, 2),
        )
        val failure = TopologyCoverageFailure.admit(
            missing = emptySet(),
            unexpected = emptySet(),
            duplicateCandidates = emptySet(),
            duplicateCompletions = emptySet(),
            workspaceMismatches = emptySet(),
            candidateEvidenceMismatches = emptySet(),
            duplicateSymbols = linkedSetOf(structurallySecond, structurallyFirst),
            missingEdgeTargets = emptySet(),
            mismatchedEdgeEndpoints = emptySet(),
        ).refinedValue()
        val rejection = TopologyBuildRejection.CoverageIncomplete(failure)
        val binding = CanonicalOperationWireBindings.topologyBuild

        val encoded = binding.encodeOutcome(OperationOutcome.Rejected(rejection)).encodedDocument()

        assertEquals(
            WireDecoding.Decoded(OperationOutcome.Rejected(rejection)),
            binding.decodeOutcome(encoded),
        )
        val first = encoded.indexOf("\"compilerIdentity\":\"a\",\"file\":\"b\\u0000c\"")
        val second = encoded.indexOf("\"compilerIdentity\":\"a\\u0000b\",\"file\":\"c\"")
        assertTrue(first >= 0 && second > first, encoded)
    }

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refinedValue()

    private fun range(start: Int, end: Int): SourceRangeDocument = SourceRangeDocument.create(
        ProtocolOffset.parse(start).refinedValue(),
        ProtocolOffset.parse(end).refinedValue(),
    ).refinedValue()

    private fun fileEvidence(path: String, hashCharacter: Char): TopologyCoverageFileEvidence =
        TopologyCoverageFileEvidence(
            workspace = TopologyCoverageWorkspaceEvidence(
                root = text("/workspace"),
                generation = EvidenceGeneration.parse(17).refinedValue(),
                sourceState = text("published"),
            ),
            sourceRoot = TopologyCoverageSourceRootEvidence(
                module = text("fixture"),
                buildRoot = text("/workspace"),
                projectPath = text(":"),
                sourceSet = text("main"),
                location = text("src/main/kotlin"),
                provenance = TopologyCoverageSourceRootProvenance.AUTHORED,
            ),
            path = text(path),
            contentHash = TopologyCoverageSourceHash.parse(hashCharacter.toString().repeat(64))
                .refinedValue(),
        )

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private fun WireEncoding.encodedDocument(): String = when (this) {
        is WireEncoding.Encoded -> document
        is WireEncoding.Rejected -> error("Expected encoded document, got $failure")
    }
}
