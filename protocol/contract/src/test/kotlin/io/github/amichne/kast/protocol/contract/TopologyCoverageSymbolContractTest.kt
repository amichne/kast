package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TopologyCoverageSymbolContractTest {
    @Test
    fun `coverage endpoint admits only coherent compiler evidence`() {
        val qualifiedIdentity = text("sample.Alpha")
        val compilerEvidence =
            CompilerSymbolEvidenceDocument.fromSignature(CompilerSignatureDocument.ClassLike(qualifiedIdentity))
                .refined()
        val node =
            TopologyCoverageNode(
                compilerIdentity = compilerEvidence.identity,
                file = text("src/main/kotlin/Alpha.kt"),
                range = range(10, 15),
            )

        val admitted =
            TopologyCoverageSymbol.create(
                node = node,
                fileEvidence = fileEvidence(),
                name = text("Alpha"),
                qualifiedIdentity = TopologyCoverageQualifiedIdentity.Available(qualifiedIdentity),
                kind = TopologyCoverageSymbolKind.CLASSLIKE,
                compilerEvidence = compilerEvidence,
            )

        assertTrue(admitted is Refinement.Refined)
        assertEquals(
            TopologyCoverageSymbolFailure.NODE_COMPILER_IDENTITY_MISMATCH,
            TopologyCoverageSymbol.create(
                    node = node.copy(compilerIdentity = text("compiler-symbol-v1:${"f".repeat(64)}")),
                    fileEvidence = fileEvidence(),
                    name = text("Alpha"),
                    qualifiedIdentity = TopologyCoverageQualifiedIdentity.Available(qualifiedIdentity),
                    kind = TopologyCoverageSymbolKind.CLASSLIKE,
                    compilerEvidence = compilerEvidence,
                )
                .rejected(),
        )
        assertEquals(
            TopologyCoverageSymbolFailure.QUALIFIED_IDENTITY_MISMATCH,
            TopologyCoverageSymbol.create(
                    node = node,
                    fileEvidence = fileEvidence(),
                    name = text("Alpha"),
                    qualifiedIdentity = TopologyCoverageQualifiedIdentity.Available(text("sample.Other")),
                    kind = TopologyCoverageSymbolKind.CLASSLIKE,
                    compilerEvidence = compilerEvidence,
                )
                .rejected(),
        )
    }

    @Test
    fun `local topology endpoints admit source-bound identity and reject contradictory anchors`() {
        val address =
            LocalDeclarationAddressDocument.create(
                    LocalDeclarationFileDocument.Workspace(text("/workspace/Seed.kt")),
                    LocalDeclarationKindDocument.PROPERTY,
                    range(20, 40),
                    text("canonical-signature-sha256-v1|${"a".repeat(64)}"),
                    range(0, 100),
                    BoundedProtocolList.create(listOf(range(10, 90))).refined(),
                )
                .refined()
        val evidence =
            CompilerSymbolEvidenceDocument.fromSignature(
                    CompilerSignatureDocument.LocalProperty(
                        address,
                        text("kotlin.Int"),
                        LocalPropertyMutabilityDocument.VAL,
                    )
                )
                .refined()
        val node = TopologyCoverageNode(evidence.identity, text("/workspace/Seed.kt"), range(20, 40))
        val file = fileEvidence().copy(path = node.file)
        assertTrue(
            TopologyCoverageSymbol.create(
                node,
                file,
                text("local"),
                TopologyCoverageQualifiedIdentity.Unavailable,
                TopologyCoverageSymbolKind.PROPERTY,
                evidence,
            ) is Refinement.Refined
        )
        assertEquals(
            TopologyCoverageSymbolFailure.LOCAL_ADDRESS_MISMATCH,
            TopologyCoverageSymbol.create(
                    node.copy(range = range(21, 40)),
                    file,
                    text("local"),
                    TopologyCoverageQualifiedIdentity.Unavailable,
                    TopologyCoverageSymbolKind.PROPERTY,
                    evidence,
                )
                .rejected(),
        )
    }

    private fun fileEvidence(): TopologyCoverageFileEvidence =
        TopologyCoverageFileEvidence(
            workspace =
                TopologyCoverageWorkspaceEvidence(
                    root = text("/workspace"),
                    generation = EvidenceGeneration.parse(1).refined(),
                    sourceState = text("state"),
                ),
            sourceRoot =
                TopologyCoverageSourceRootEvidence(
                    module = text("main"),
                    buildRoot = text("."),
                    projectPath = text(":"),
                    sourceSet = text("main"),
                    location = text("src/main/kotlin"),
                    provenance = TopologyCoverageSourceRootProvenance.AUTHORED,
                ),
            path = text("src/main/kotlin/Alpha.kt"),
            contentHash = TopologyCoverageSourceHash.parse("a".repeat(64)).refined(),
        )

    private fun range(start: Int, end: Int): SourceRangeDocument =
        SourceRangeDocument.create(
                ProtocolOffset.parse(start).refined(),
                ProtocolOffset.parse(end).refined(),
            )
            .refined()

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }

    private fun <Value, Failure> Refinement<Value, Failure>.rejected(): Failure =
        when (this) {
            is Refinement.Refined -> error("Expected rejection, got $value")
            is Refinement.Rejected -> failure
        }
}
