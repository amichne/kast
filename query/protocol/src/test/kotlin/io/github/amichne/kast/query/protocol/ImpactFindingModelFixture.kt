package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.BoundaryContractIdentity
import io.github.amichne.kast.relation.contract.BoundaryKind
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.ContractModelIdentity
import io.github.amichne.kast.relation.contract.ExactModelCallablePosition
import io.github.amichne.kast.relation.contract.ModelCallableReference
import io.github.amichne.kast.relation.contract.ModelIdentifier
import io.github.amichne.kast.relation.contract.ModelRuleReference
import io.github.amichne.kast.relation.contract.ModelValuePosition
import io.github.amichne.kast.relation.contract.ModelVersion
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RepresentationDomain
import io.github.amichne.kast.relation.contract.RepresentationEvidence
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueInvocation
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

internal class ImpactFindingModelFixture(val root: String = "/workspace", generation: Long = 1) {
    val lease =
        SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(root)).refined(),
            EvidenceGeneration.parse(generation).refined(),
        )
    val owner = endpoint("owner", 0, 200)
    val call =
        ValueInvocation.fromCompiler(
                owner,
                ExactDeclarationTextRange.parse(30, 40).refined(),
                endpoint("encrypt", 210, 250),
            )
            .refined()
    val producer = site(30, 40, ValueRole.ExpressionResult)
    val model = ContractModelIdentity(id("encryption"), ModelVersion.parse(1).refined(), id("review:913"))
    val domain = RepresentationDomain.admit(model, listOf(id("HIPED"), id("PLAINTEXT"))).refined()
    val origin =
        RepresentationEvidence.origin(
                producer,
                call,
                RepresentationRule.Origin.admit(
                        rule("origin"),
                        bind(ModelValuePosition.Result),
                        domain.state(id("HIPED")).refined(),
                    )
                    .refined(),
            )
            .refined()

    fun id(raw: String) = ModelIdentifier.parse(raw).refined()

    fun rule(raw: String) = ModelRuleReference(model, id(raw))

    fun site(start: Int, end: Int, role: ValueRole) =
        ValueSite.fromCompiler(owner, ExactDeclarationTextRange.parse(start, end).refined(), role).refined()

    fun boundary(site: ValueSite) =
        BoundaryPosition.at(
            site,
            BoundaryKind.PERSISTENCE,
            BoundaryContractIdentity(id("cache"), ModelVersion.parse(1).refined()),
            id("ciphertext"),
        )

    fun bind(position: ModelValuePosition): ExactModelCallablePosition {
        val callable = call.callable as RelationEndpoint.Resolved
        return ExactModelCallablePosition.admit(
                ModelCallableReference(
                    callable.lease.identity,
                    callable.compilerIdentity,
                    callable.file,
                    callable.range,
                    position,
                ),
                RevalidatedRelationEndpoint.validate(callable, callable.evidence).refined(),
            )
            .refined()
    }

    private fun endpoint(name: String, start: Int, end: Int): RelationEndpoint {
        val candidate =
            SymbolDiscoveryCandidate.fromBoundary(
                    SymbolDiscoveryKind.SYMBOL,
                    name,
                    lease,
                    Path.of("$root/File.kt"),
                    "file://$root/File.kt",
                    start,
                )
                .refined()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    (candidate.location as SymbolDiscoveryCandidateLocation.Declaration).file,
                    start,
                    end,
                    name,
                    "fixture.$name",
                    CompilerSymbolKind.FUNCTION,
                    CanonicalCompilerSignature.function("fixture.$name", null, emptyList(), listOf("String"), 0)
                        .refined(),
                )
                .refined()
        return RelationEndpoint.resolve(
                lease,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.EXCLUDE,
                ),
                evidence,
            )
            .refined()
    }
}

private fun <T> Refinement<T, *>.refined(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
