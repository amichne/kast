package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.AdmittedImpactModelSyntax
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactBoundaryCompatibilityDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryContractDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryKindDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactCallablePositionDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactModelDocument
import io.github.amichne.kast.protocol.contract.ImpactModelFormatDocument
import io.github.amichne.kast.protocol.contract.ImpactModelIdentifierDocument
import io.github.amichne.kast.protocol.contract.ImpactModelIdentityDocument
import io.github.amichne.kast.protocol.contract.ImpactModelValuePositionDocument
import io.github.amichne.kast.protocol.contract.ImpactModelVersionDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.relation.contract.BoundaryContractIdentity
import io.github.amichne.kast.relation.contract.BoundaryKind
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.ModelBindingFailure
import io.github.amichne.kast.relation.contract.ModelIdentifier
import io.github.amichne.kast.relation.contract.ModelVersion
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
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
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Detached compiler observations test admission only; they do not establish native evaluation. */
class ImpactModelAdmissionTest {
    @Test
    fun `all four rules preserve exact current callable and model identity`() {
        val current = endpoint()
        val admitted = AdmittedRepresentationModel.admit(model(current), listOf(current)).refined()
        assertEquals(
            listOf("origin", "transfer", "decrypt", "consumer"),
            admitted.rules.map { it.reference.rule.value },
        )
        assertEquals(
            listOf(
                RepresentationRule.Origin::class,
                RepresentationRule.Transfer::class,
                RepresentationRule.Transformation::class,
                RepresentationRule.ConsumerExpectation::class,
            ),
            admitted.rules.map { it::class },
        )
        assertEquals(setOf("HIPED", "PLAINTEXT"), admitted.domain.states.map { it.value }.toSet())
        assertEquals(current.endpoint, (admitted.rules.first() as RepresentationRule.Origin).output.endpoint)
        assertEquals("review:913", admitted.domain.model.provenance.value)
    }

    @Test
    fun `same signature in another file cannot replace exact declared callable`() {
        val expected = endpoint()
        val other = endpoint(fileName = "Other.kt")
        assertEquals(expected.endpoint.compilerIdentity, other.endpoint.compilerIdentity)
        assertEquals(
            Refinement.Rejected(ImpactModelAdmissionFailure.MISSING_DECLARATION),
            AdmittedRepresentationModel.admit(model(expected), listOf(other)),
        )
    }

    @Test
    fun `old generation and unavailable formal argument reject with precise causes`() {
        val expected = endpoint()
        assertEquals(
            Refinement.Rejected(ImpactModelAdmissionFailure.STALE_DECLARATION),
            AdmittedRepresentationModel.admit(model(expected), listOf(endpoint(generation = 2))),
        )
        assertEquals(
            Refinement.Rejected(ImpactModelAdmissionFailure.Callable(ModelBindingFailure.POSITION_UNAVAILABLE)),
            AdmittedRepresentationModel.admit(model(expected, argument = 1), listOf(expected)),
        )
    }

    @Test
    fun `scope only repeated current witnesses preserve rule equality`() {
        val broad = endpoint()
        val narrowEndpoint =
            RelationEndpoint.resolve(
                    broad.endpoint.lease,
                    SymbolSearchScope.ExactFile(
                        CanonicalWorkspaceFilePath.fromCanonicalPath(
                                broad.endpoint.lease.workspaceRoot,
                                Path.of("/workspace/File.kt"),
                            )
                            .refined(),
                        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                        SymbolGeneratedSourcePolicy.EXCLUDE,
                    ),
                    (broad.endpoint as RelationEndpoint.Resolved).evidence,
                )
                .refined()
        val narrow =
            RevalidatedRelationEndpoint.validate(narrowEndpoint, (narrowEndpoint as RelationEndpoint.Resolved).evidence)
                .refined()
        val syntax = model(broad)
        assertEquals(
            AdmittedRepresentationModel.admit(syntax, listOf(broad)).refined().rules,
            AdmittedRepresentationModel.admit(syntax, listOf(narrow, broad, narrow)).refined().rules,
        )
    }

    @Test
    fun `boundary syntax binds separate exact repository bases and refuses missing same name target`() {
        val source = boundary(endpoint(root = "/server"))
        val target = boundary(endpoint(root = "/client", generation = 9))
        val document =
            ImpactModelDocument.Boundary(
                ImpactModelFormatDocument.Current,
                identity(),
                bounded(
                    listOf(
                        ImpactBoundaryRuleDocument.Continuation(
                            id("wire"),
                            boundaryDocument(source),
                            boundaryDocument(target),
                            bounded(listOf(ImpactBoundaryCompatibilityDocument.REPRESENTATION_PRESERVED)),
                        )
                    )
                ),
            )
        val syntax = AdmittedImpactModelSyntax.admit(document).refined()
        val rule =
            AdmittedBoundaryModel.admit(syntax, listOf(source, target)).refined().rules.single()
                as BoundaryModel.Continuation
        assertEquals(source.reference, rule.source.reference)
        assertEquals(target.reference, rule.target.reference)
        assertEquals(
            Refinement.Rejected(ImpactModelAdmissionFailure.MISSING_BOUNDARY_POSITION),
            AdmittedBoundaryModel.admit(
                syntax,
                listOf(source, boundary(endpoint(root = "/unrelated", generation = 9))),
            ),
        )
    }

    private fun model(current: RevalidatedRelationEndpoint, argument: Int = 0): AdmittedImpactModelSyntax {
        val output =
            ImpactCallablePositionDocument(declaration(current.endpoint), ImpactModelValuePositionDocument.Result)
        val input = output.copy(position = ImpactModelValuePositionDocument.Argument(offset(argument)))
        return AdmittedImpactModelSyntax.admit(
                ImpactModelDocument.Representation(
                    ImpactModelFormatDocument.Current,
                    identity(),
                    bounded(listOf(id("HIPED"), id("PLAINTEXT"))),
                    bounded(
                        listOf(
                            ImpactRepresentationRuleDocument.Origin(id("origin"), output, id("HIPED")),
                            ImpactRepresentationRuleDocument.Transfer(id("transfer"), input, output),
                            ImpactRepresentationRuleDocument.Transformation(
                                id("decrypt"),
                                input,
                                output,
                                id("HIPED"),
                                id("PLAINTEXT"),
                            ),
                            ImpactRepresentationRuleDocument.ConsumerExpectation(
                                id("consumer"),
                                input,
                                id("PLAINTEXT"),
                            ),
                        )
                    ),
                )
            )
            .refined()
    }

    private fun endpoint(
        root: String = "/workspace",
        generation: Long = 1,
        fileName: String = "File.kt",
    ): RevalidatedRelationEndpoint {
        val lease =
            SemanticReadLease(
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(root)).refined(),
                EvidenceGeneration.parse(generation).refined(),
            )
        val candidate =
            SymbolDiscoveryCandidate.fromBoundary(
                    SymbolDiscoveryKind.SYMBOL,
                    "decrypt",
                    lease,
                    Path.of("$root/$fileName"),
                    "file://$root/$fileName",
                    0,
                )
                .refined()
        val file = (candidate.location as SymbolDiscoveryCandidateLocation.Declaration).file
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file,
                    0,
                    100,
                    "decrypt",
                    "fixture.decrypt",
                    CompilerSymbolKind.FUNCTION,
                    CanonicalCompilerSignature.function("fixture.decrypt", null, emptyList(), listOf("String"), 0)
                        .refined(),
                )
                .refined()
        val endpoint =
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
        return RevalidatedRelationEndpoint.validate(endpoint, evidence).refined()
    }

    private fun boundary(current: RevalidatedRelationEndpoint) =
        BoundaryPosition.at(
            ValueSite.fromCompiler(
                    current.endpoint,
                    ExactDeclarationTextRange.parse(10, 11).refined(),
                    ValueRole.PropertyAssignment,
                )
                .refined(),
            BoundaryKind.SERIALIZATION,
            BoundaryContractIdentity(ModelIdentifier.parse("payload").refined(), ModelVersion.parse(1).refined()),
            ModelIdentifier.parse("ciphertext").refined(),
        )

    private fun boundaryDocument(current: BoundaryPosition) =
        ImpactBoundaryPositionDocument(
            ImpactValueSiteReferenceDocument(
                declaration(current.site.enclosing),
                ImpactSourceRangeDocument(offset(10), offset(11)),
                ImpactValueRoleDocument.PropertyAssignment,
            ),
            ImpactBoundaryKindDocument.SERIALIZATION,
            ImpactBoundaryContractDocument(id("payload"), ImpactModelVersionDocument.parse(1).refined()),
            id("ciphertext"),
        )

    private fun declaration(current: RelationEndpoint): ImpactDeclarationReferenceDocument {
        val basis = current.lease.identity as SemanticReadIdentity.Published
        return ImpactDeclarationReferenceDocument(
            ImpactSemanticBasisDocument.Published(
                text(basis.workspaceRoot.value),
                ImpactEvidenceRevisionDocument.parse(basis.lease.generation.value).refined(),
            ),
            text(current.file.stableValue),
            ImpactSourceRangeDocument(offset(current.range.startInclusive), offset(current.range.endExclusive)),
            text(current.compilerIdentity.value),
        )
    }

    private fun identity() =
        ImpactModelIdentityDocument(id("encryption"), ImpactModelVersionDocument.parse(1).refined(), id("review:913"))

    private fun id(raw: String) = ImpactModelIdentifierDocument.parse(raw).refined()

    private fun text(raw: String) = ProtocolText.parse(raw).refined()

    private fun offset(raw: Int) = ProtocolOffset.parse(raw).refined()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()

    private fun <S, F> Refinement<S, F>.refined(): S =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
