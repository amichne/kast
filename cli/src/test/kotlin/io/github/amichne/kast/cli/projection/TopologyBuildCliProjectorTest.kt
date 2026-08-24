package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.ProjectedCliOutcome
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.TopologyBuildRejection
import io.github.amichne.kast.protocol.contract.TopologyCoverageCandidateEvidenceMismatch
import io.github.amichne.kast.protocol.contract.TopologyCoverageFailure
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

class TopologyBuildCliProjectorTest {
    @Test
    fun `extraction rejection retains stable file and finite failure fields`() {
        val projected = topologyBuildCliProjector.project(
            OperationOutcome.Rejected(
                TopologyBuildRejection.ExtractionFailed(
                    ProtocolText.parse(
                        "topology/intellij/src/main/kotlin/TopologyK2Projection.kt",
                    ).refined(),
                    TopologyExtractionRejection.SOURCE_CONTENT_MOVED,
                ),
            ),
        ) as ProjectedCliOutcome.Rejected

        assertEquals(
            "{\"operation\":\"topology.build\",\"status\":\"rejected\"," +
                "\"reason\":\"extraction-failed\"," +
                "\"file\":\"topology/intellij/src/main/kotlin/TopologyK2Projection.kt\"," +
                "\"failure\":\"source-content-moved\"}",
            projected.document.value,
        )
    }

    @Test
    fun `coverage projection rejection retains its typed failure`() {
        val projected = topologyBuildCliProjector.project(
            OperationOutcome.Rejected(
                TopologyBuildRejection.CoverageProjectionFailed(
                    TopologyCoverageProjectionRejection.UNREPRESENTABLE_CONTENT_HASH,
                ),
            ),
        ) as ProjectedCliOutcome.Rejected

        assertEquals(
            "{\"operation\":\"topology.build\",\"status\":\"rejected\"," +
                "\"reason\":\"coverage-projection-failed\"," +
                "\"failure\":\"unrepresentable-content-hash\"}",
            projected.document.value,
        )
    }

    @Test
    fun `coverage rejection retains all exact mismatch evidence`() {
        val node = TopologyCoverageNode(
            compilerIdentity = text("compiler"),
            file = text("File.kt"),
            range = SourceRangeDocument.create(
                ProtocolOffset.parse(1).refined(),
                ProtocolOffset.parse(2).refined(),
            ).refined(),
        )
        val symbol = TopologyCoverageSymbol(
            node = node,
            fileEvidence = fileEvidence("File.kt", 'b'),
            name = text("name"),
            qualifiedIdentity = TopologyCoverageQualifiedIdentity.Available(text("qualified")),
            kind = TopologyCoverageSymbolKind.FUNCTION,
        )
        val failure = TopologyCoverageFailure.admit(
            missing = setOf(text("missing")),
            unexpected = setOf(text("unexpected")),
            duplicateCandidates = setOf(text("candidate")),
            duplicateCompletions = setOf(text("completion")),
            workspaceMismatches = setOf(text("workspace")),
            candidateEvidenceMismatches = setOf(
                TopologyCoverageCandidateEvidenceMismatch(
                    candidate = fileEvidence("File.kt", 'a'),
                    completed = fileEvidence("File.kt", 'b'),
                ),
            ),
            duplicateSymbols = setOf(node),
            missingEdgeTargets = setOf(node),
            mismatchedEdgeEndpoints = setOf(symbol),
        ).refined()

        val projected = topologyBuildCliProjector.project(
            OperationOutcome.Rejected(TopologyBuildRejection.CoverageIncomplete(failure)),
        ) as ProjectedCliOutcome.Rejected

        val nodeJson = "{\"compilerIdentity\":\"compiler\",\"file\":\"File.kt\"," +
            "\"range\":{\"startInclusive\":1,\"endExclusive\":2}}"
        val workspaceJson = "{\"root\":\"/workspace\",\"generation\":17," +
            "\"sourceState\":\"published\"}"
        val sourceRootJson = "{\"module\":\"fixture\",\"buildRoot\":\"/workspace\"," +
            "\"projectPath\":\":\",\"sourceSet\":\"main\"," +
            "\"location\":\"src/main/kotlin\",\"provenance\":\"authored\"}"
        fun fileEvidenceJson(hash: Char) = "{\"workspace\":$workspaceJson," +
            "\"sourceRoot\":$sourceRootJson,\"path\":\"File.kt\"," +
            "\"contentHash\":\"${hash.toString().repeat(64)}\"}"
        assertEquals(
            "{\"operation\":\"topology.build\",\"status\":\"rejected\"," +
                "\"reason\":\"coverage-incomplete\",\"missing\":[\"missing\"]," +
                "\"unexpected\":[\"unexpected\"],\"duplicateCandidates\":[\"candidate\"]," +
                "\"duplicateCompletions\":[\"completion\"]," +
                "\"workspaceMismatches\":[\"workspace\"]," +
                "\"candidateEvidenceMismatches\":[{\"candidate\":${fileEvidenceJson('a')}," +
                "\"completed\":${fileEvidenceJson('b')}}]," +
                "\"duplicateSymbols\":[$nodeJson],\"missingEdgeTargets\":[$nodeJson]," +
                "\"mismatchedEdgeEndpoints\":[{\"node\":$nodeJson," +
                "\"fileEvidence\":${fileEvidenceJson('b')},\"name\":\"name\"," +
                "\"qualifiedIdentity\":{\"state\":\"available\",\"value\":\"qualified\"}," +
                "\"kind\":\"function\"}]}",
            projected.document.value,
        )
    }

    @Test
    fun `coverage projection sorts structurally across delimiter-shaped text`() {
        val structurallyFirst = coverageNode("a", "b\u0000c")
        val structurallySecond = coverageNode("a\u0000b", "c")
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
        ).refined()

        val projected = topologyBuildCliProjector.project(
            OperationOutcome.Rejected(TopologyBuildRejection.CoverageIncomplete(failure)),
        ) as ProjectedCliOutcome.Rejected

        val first = projected.document.value.indexOf(
            "\"compilerIdentity\":\"a\",\"file\":\"b\\u0000c\"",
        )
        val second = projected.document.value.indexOf(
            "\"compilerIdentity\":\"a\\u0000b\",\"file\":\"c\"",
        )
        assertTrue(first >= 0 && second > first, projected.document.value)
    }

    private fun coverageNode(compilerIdentity: String, file: String): TopologyCoverageNode =
        TopologyCoverageNode(
            compilerIdentity = text(compilerIdentity),
            file = text(file),
            range = SourceRangeDocument.create(
                ProtocolOffset.parse(1).refined(),
                ProtocolOffset.parse(2).refined(),
            ).refined(),
        )

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refined()

    private fun fileEvidence(path: String, hashCharacter: Char): TopologyCoverageFileEvidence =
        TopologyCoverageFileEvidence(
            workspace = TopologyCoverageWorkspaceEvidence(
                root = text("/workspace"),
                generation = EvidenceGeneration.parse(17).refined(),
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
                .refined(),
        )

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
