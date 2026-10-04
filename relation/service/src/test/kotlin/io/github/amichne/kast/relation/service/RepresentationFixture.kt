package io.github.amichne.kast.relation.service

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.BoundaryContractIdentity
import io.github.amichne.kast.relation.contract.BoundaryKind
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.ContractModelIdentity
import io.github.amichne.kast.relation.contract.ModelIdentifier
import io.github.amichne.kast.relation.contract.ModelRuleReference
import io.github.amichne.kast.relation.contract.ModelVersion
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path

/** Explicit detached observations for pure contract checks, never native semantic proof. */
internal class RepresentationFixture(root: String = "/workspace", generation: Long = 1) {
    val version = ModelVersion.parse(1).refined()
    val reference =
        ModelRuleReference(ContractModelIdentity(id("boundary-model"), version, id("review:914")), id("reader-writer"))
    private val lease =
        SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(root)).refined(),
            EvidenceGeneration.parse(generation).refined(),
        )
    private val candidate =
        SymbolDiscoveryCandidate.fromBoundary(
                SymbolDiscoveryKind.SYMBOL,
                "owner",
                lease,
                Path.of("$root/File.kt"),
                "file://$root/File.kt",
                0,
            )
            .refined()
    private val file = (candidate.location as SymbolDiscoveryCandidateLocation.Declaration).file
    private val evidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                file,
                0,
                100,
                "owner",
                "fixture.owner",
                CompilerSymbolKind.FUNCTION,
                CanonicalCompilerSignature.function("fixture.owner", null, emptyList(), emptyList(), 0).refined(),
            )
            .refined()
    private val owner =
        RelationEndpoint.resolve(
                lease,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.INCLUDE,
                    SymbolLibraryPolicy.EXCLUDE,
                ),
                evidence,
            )
            .refined()

    fun id(raw: String) = ModelIdentifier.parse(raw).refined()

    fun boundary(kind: BoundaryKind, slot: String = "ciphertext", offset: Int = 10): BoundaryPosition =
        BoundaryPosition.at(
            ValueSite.fromCompiler(
                    owner,
                    ExactDeclarationTextRange.parse(offset, offset + 1).refined(),
                    ValueRole.PropertyAssignment,
                )
                .refined(),
            kind,
            BoundaryContractIdentity(id("response-schema"), version),
            id(slot),
        )

    private fun <S, F> Refinement<S, F>.refined(): S =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
