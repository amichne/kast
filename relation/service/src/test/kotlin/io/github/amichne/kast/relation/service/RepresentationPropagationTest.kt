package io.github.amichne.kast.relation.service

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.BoundaryArrival
import io.github.amichne.kast.relation.contract.BoundaryCompatibilityAssumption
import io.github.amichne.kast.relation.contract.BoundaryKind
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.ContractModelIdentity
import io.github.amichne.kast.relation.contract.ExactModelCallablePosition
import io.github.amichne.kast.relation.contract.ModelBindingFailure
import io.github.amichne.kast.relation.contract.ModelCallableReference
import io.github.amichne.kast.relation.contract.ModelIdentifier
import io.github.amichne.kast.relation.contract.ModelRuleReference
import io.github.amichne.kast.relation.contract.ModelValuePosition
import io.github.amichne.kast.relation.contract.ModelVersion
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RepresentationCurrent
import io.github.amichne.kast.relation.contract.RepresentationDomain
import io.github.amichne.kast.relation.contract.RepresentationDomainFailure
import io.github.amichne.kast.relation.contract.RepresentationEvidence
import io.github.amichne.kast.relation.contract.RepresentationHistory
import io.github.amichne.kast.relation.contract.RepresentationPropagationFailure
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.RepresentationRuleFailure
import io.github.amichne.kast.relation.contract.RepresentationStateFailure
import io.github.amichne.kast.relation.contract.RepresentationUnknownReason
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
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
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RepresentationPropagationTest {
    @Test
    fun `scope policies do not change exact origin owner or model callable identity`() {
        val fixture = Fixture()
        val call = fixture.call("hipedEncrypt", 10, 20)
        val narrowOwner =
            RelationEndpoint.resolve(
                    fixture.owner.lease,
                    SymbolSearchScope.ExactFile(
                        CanonicalWorkspaceFilePath.fromCanonicalPath(
                                fixture.owner.lease.workspaceRoot,
                                Path.of("/workspace/File.kt"),
                            )
                            .refined(),
                        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                        SymbolGeneratedSourcePolicy.EXCLUDE,
                    ),
                    (fixture.owner as RelationEndpoint.Resolved).evidence,
                )
                .refined()
        val output = ValueSite.fromCompiler(narrowOwner, call.range, ValueRole.ExpressionResult).refined()
        val rule =
            RepresentationRule.Origin.admit(
                    fixture.rule("origin"),
                    fixture.bind(call.callable, ModelValuePosition.Result),
                    fixture.hiped,
                )
                .refined()
        assertEquals(fixture.output(call).identity, output.identity)
        assertEquals(
            RepresentationCurrent.Known(fixture.hiped),
            RepresentationEvidence.origin(output, call, rule).refined().branches.single().current,
        )
    }

    @Test
    fun `identical signatures in different files cannot replace exact model callable identity`() {
        val fixture = Fixture()
        val expected = fixture.endpoint("decrypt", fileName = "Expected.kt")
        val other = fixture.endpoint("decrypt", fileName = "Other.kt")
        assertEquals(expected.compilerIdentity, other.compilerIdentity)
        assertEquals(
            Refinement.Rejected(RepresentationRuleFailure.CALLABLE_MISMATCH),
            RepresentationRule.Transfer.admit(
                fixture.rule("transfer"),
                fixture.bind(expected, ModelValuePosition.Argument(ValueArgumentPosition.parse(0).refined())),
                fixture.bind(other, ModelValuePosition.Result),
            ),
        )
        val declared =
            ModelCallableReference(
                expected.lease.identity,
                expected.compilerIdentity,
                expected.file,
                expected.range,
                ModelValuePosition.Result,
            )
        val current =
            RevalidatedRelationEndpoint.validate(other, (other as RelationEndpoint.Resolved).evidence).refined()
        assertEquals(
            Refinement.Rejected(ModelBindingFailure.DECLARATION_MISMATCH),
            ExactModelCallablePosition.admit(declared, current),
        )
    }

    @Test
    fun `different invocation output cannot claim an origin established at the first invocation`() {
        val fixture = Fixture()
        val first = fixture.call("hipedEncrypt", 10, 20)
        val second = fixture.call("hipedEncrypt", 30, 40)
        val rule =
            RepresentationRule.Origin.admit(
                    fixture.rule("hiped-origin"),
                    fixture.bind(first.callable, ModelValuePosition.Result),
                    fixture.hiped,
                )
                .refined()
        assertEquals(
            Refinement.Rejected(RepresentationPropagationFailure.SITE_MISMATCH),
            RepresentationEvidence.origin(fixture.output(second), first, rule),
        )
    }

    @Test
    fun `merge retains distinct representation alternatives at the same exact site`() {
        val fixture = Fixture()
        val first = fixture.call("hipedEncrypt", 10, 20)
        val second = fixture.call("voltageEncrypt", 30, 40)
        val hipedRule =
            RepresentationRule.Origin.admit(
                    fixture.rule("hiped-origin"),
                    fixture.bind(first.callable, ModelValuePosition.Result),
                    fixture.hiped,
                )
                .refined()
        val voltageRule =
            RepresentationRule.Origin.admit(
                    fixture.rule("voltage-origin"),
                    fixture.bind(second.callable, ModelValuePosition.Result),
                    fixture.voltage,
                )
                .refined()
        val hiped = RepresentationEvidence.origin(fixture.output(first), first, hipedRule).refined()
        val voltage = RepresentationEvidence.origin(fixture.output(second), second, voltageRule).refined()
        val join = fixture.output(fixture.call("join", 50, 60))
        val firstArrival =
            hiped
                .transfer(ValueTransfer.fromCompiler(hiped.site, join, ValueTransferKind.BRANCH_ALTERNATIVE).refined())
                .refined()
        val secondArrival =
            voltage
                .transfer(
                    ValueTransfer.fromCompiler(voltage.site, join, ValueTransferKind.BRANCH_ALTERNATIVE).refined()
                )
                .refined()
        val merged = RepresentationEvidence.merge(listOf(firstArrival, secondArrival, firstArrival)).refined()
        assertEquals(2, merged.branches.size)
        assertEquals(
            setOf(RepresentationCurrent.Known(fixture.hiped), RepresentationCurrent.Known(fixture.voltage)),
            merged.branches.map { it.current }.toSet(),
        )
        assertEquals(
            setOf("hiped-origin", "voltage-origin"),
            merged.branches
                .flatMap { it.history }
                .filterIsInstance<RepresentationHistory.Model>()
                .map { it.reference.rule.value }
                .toSet(),
        )
    }

    @Test
    fun `a reviewed boundary keeps provenance model evidence across independent bases`() {
        val fixture = Fixture()
        val call = fixture.call("hipedEncrypt", 10, 20)
        val rule =
            RepresentationRule.Origin.admit(
                    fixture.rule("hiped-origin"),
                    fixture.bind(call.callable, ModelValuePosition.Result),
                    fixture.hiped,
                )
                .refined()
        val original = RepresentationEvidence.origin(fixture.output(call), call, rule).refined()
        val boundaryFixture = RepresentationFixture("/client", 5)
        val target = boundaryFixture.boundary(BoundaryKind.SERIALIZATION)
        val source = BoundaryPosition.at(original.site, BoundaryKind.SERIALIZATION, target.contract, target.slot)
        val model =
            BoundaryModel.Continuation.admit(
                    boundaryFixture.reference,
                    source.reference,
                    target.reference,
                    source,
                    target,
                    setOf(BoundaryCompatibilityAssumption.REPRESENTATION_PRESERVED),
                )
                .refined()
        val transferred = original.throughBoundary(BoundaryArrival.connect(source, model).refined()).refined()
        assertEquals(RepresentationCurrent.Known(fixture.hiped), transferred.branches.single().current)
        assertEquals(original.branches.single().history, transferred.branches.single().history.dropLast(1))
        assertEquals(target.site.basis, transferred.site.basis)
        val history = transferred.branches.single().history.last() as RepresentationHistory.BoundaryModel
        assertEquals(original.site.basis, history.source.basis)
        assertEquals(target.site.basis, history.target.basis)
    }

    @Test
    fun `boundary without representation preservation assumption weakens current state explicitly`() {
        val fixture = Fixture()
        val call = fixture.call("hipedEncrypt", 10, 20)
        val rule =
            RepresentationRule.Origin.admit(
                    fixture.rule("hiped-origin"),
                    fixture.bind(call.callable, ModelValuePosition.Result),
                    fixture.hiped,
                )
                .refined()
        val original = RepresentationEvidence.origin(fixture.output(call), call, rule).refined()
        val boundaryFixture = RepresentationFixture("/client", 5)
        val target = boundaryFixture.boundary(BoundaryKind.SERIALIZATION)
        val source = BoundaryPosition.at(original.site, BoundaryKind.SERIALIZATION, target.contract, target.slot)
        val model =
            BoundaryModel.Continuation.admit(
                    boundaryFixture.reference,
                    source.reference,
                    target.reference,
                    source,
                    target,
                    emptySet(),
                )
                .refined()
        val transferred = original.throughBoundary(BoundaryArrival.connect(source, model).refined()).refined()
        assertEquals(
            RepresentationCurrent.Unknown(RepresentationUnknownReason.BOUNDARY_PRESERVATION_UNPROVEN),
            transferred.branches.single().current,
        )
        assertEquals(original.branches.single().history, transferred.branches.single().history.dropLast(1))
    }

    @Test
    fun `HIPED plaintext Voltage bridge updates current state and keeps complete model history`() {
        val fixture = Fixture()
        val first = hipedOrigin(fixture)
        val decrypt = fixture.call("hipedDecrypt", 30, 40)
        val decryptInput = fixture.argument(decrypt)
        val enteredDecrypt =
            first
                .transfer(ValueTransfer.fromCompiler(first.site, decryptInput, ValueTransferKind.ARGUMENT).refined())
                .refined()
        val decryptRule =
            RepresentationRule.Transformation.admit(
                    fixture.rule("hiped-decrypt"),
                    fixture.bind(
                        decrypt.callable,
                        ModelValuePosition.Argument(ValueArgumentPosition.parse(0).refined()),
                    ),
                    fixture.bind(decrypt.callable, ModelValuePosition.Result),
                    fixture.hiped,
                    fixture.plaintext,
                )
                .refined()
        val plaintext = enteredDecrypt.transform(fixture.output(decrypt), decrypt, decryptRule).refined()
        val encrypt = fixture.call("voltageEncrypt", 50, 60)
        val enteredEncrypt = fixture.enterArgument(plaintext, encrypt)
        val encryptRule =
            RepresentationRule.Transformation.admit(
                    fixture.rule("voltage-encrypt"),
                    fixture.bind(
                        encrypt.callable,
                        ModelValuePosition.Argument(ValueArgumentPosition.parse(0).refined()),
                    ),
                    fixture.bind(encrypt.callable, ModelValuePosition.Result),
                    fixture.plaintext,
                    fixture.voltage,
                )
                .refined()
        val voltage = enteredEncrypt.transform(fixture.output(encrypt), encrypt, encryptRule).refined()
        assertEquals(setOf(RepresentationCurrent.Known(fixture.voltage)), voltage.branches.map { it.current }.toSet())
        assertEquals(
            listOf("hiped-origin", "hiped-decrypt", "voltage-encrypt"),
            voltage.branches.single().history.filterIsInstance<RepresentationHistory.Model>().map {
                it.reference.rule.value
            },
        )
    }

    @Test
    fun `an unmodeled transformation keeps history but cannot preserve a known current state`() {
        val fixture = Fixture()
        val call = fixture.call("hipedEncrypt", 10, 20)
        val origin =
            RepresentationRule.Origin.admit(
                    fixture.rule("hiped-origin"),
                    fixture.bind(call.callable, ModelValuePosition.Result),
                    fixture.hiped,
                )
                .refined()
        val first = RepresentationEvidence.origin(fixture.output(call), call, origin).refined()
        val unknown = first.unmodeled(fixture.output(fixture.call("unmodeled", 30, 40))).refined()
        assertEquals(
            RepresentationCurrent.Unknown(RepresentationUnknownReason.UNMODELED_TRANSFORMATION),
            unknown.branches.single().current,
        )
        assertEquals(first.branches.single().history, unknown.branches.single().history.dropLast(1))
    }

    @Test
    fun `a model cannot invent an undeclared current representation`() {
        val model = ContractModelIdentity(id("encryption"), ModelVersion.parse(1).refined(), id("review:913"))
        val domain = RepresentationDomain.admit(model, listOf(id("PLAINTEXT"), id("HIPED"), id("VOLTAGE"))).refined()
        assertEquals(Refinement.Rejected(RepresentationStateFailure.UNDECLARED_STATE), domain.state(id("String")))
        assertEquals(
            Refinement.Rejected(RepresentationDomainFailure.DUPLICATE_STATE),
            RepresentationDomain.admit(model, listOf(id("HIPED"), id("HIPED"))),
        )
    }

    private fun hipedOrigin(fixture: Fixture): RepresentationEvidence {
        val originCall = fixture.call("hipedEncrypt", 10, 20)
        val origin =
            RepresentationRule.Origin.admit(
                    fixture.rule("hiped-origin"),
                    fixture.bind(originCall.callable, ModelValuePosition.Result),
                    fixture.hiped,
                )
                .refined()
        return RepresentationEvidence.origin(fixture.output(originCall), originCall, origin).refined()
    }

    private fun id(raw: String): ModelIdentifier = ModelIdentifier.parse(raw).refined()

    private fun <S, F> Refinement<S, F>.refined(): S =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }

    private inner class Fixture {
        val lease =
            SemanticReadLease(
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
                EvidenceGeneration.parse(1).refined(),
            )
        val owner = endpoint("owner", 0, 200)
        val model = ContractModelIdentity(id("encryption"), ModelVersion.parse(1).refined(), id("review:913"))
        val domain = RepresentationDomain.admit(model, listOf(id("HIPED"), id("PLAINTEXT"), id("VOLTAGE"))).refined()
        val hiped = domain.state(id("HIPED")).refined()
        val plaintext = domain.state(id("PLAINTEXT")).refined()
        val voltage = domain.state(id("VOLTAGE")).refined()

        fun rule(name: String) = ModelRuleReference(model, id(name))

        fun endpoint(name: String, start: Int = 210, end: Int = 220, fileName: String = "File.kt"): RelationEndpoint {
            val candidate =
                SymbolDiscoveryCandidate.fromBoundary(
                        SymbolDiscoveryKind.SYMBOL,
                        name,
                        lease,
                        Path.of("/workspace/$fileName"),
                        "file:///workspace/$fileName",
                        start,
                    )
                    .refined()
            val file = (candidate.location as SymbolDiscoveryCandidateLocation.Declaration).file
            val signature =
                CanonicalCompilerSignature.function("fixture.$name", null, emptyList(), listOf("kotlin.String"), 0)
                    .refined()
            val evidence =
                CompilerGroundedSymbolEvidence.fromBoundary(
                        file,
                        start,
                        end,
                        name,
                        "fixture.$name",
                        CompilerSymbolKind.FUNCTION,
                        signature,
                    )
                    .refined()
            return RelationEndpoint.resolve(
                    lease,
                    SymbolSearchScope.Workspace(
                        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                        SymbolGeneratedSourcePolicy.INCLUDE,
                        SymbolLibraryPolicy.EXCLUDE,
                    ),
                    evidence,
                )
                .refined()
        }

        fun bind(endpoint: RelationEndpoint, position: ModelValuePosition): ExactModelCallablePosition {
            val evidence = (endpoint as RelationEndpoint.Resolved).evidence
            return ExactModelCallablePosition.admit(
                    ModelCallableReference(
                        endpoint.lease.identity,
                        endpoint.compilerIdentity,
                        endpoint.file,
                        endpoint.range,
                        position,
                    ),
                    RevalidatedRelationEndpoint.validate(endpoint, evidence).refined(),
                )
                .refined()
        }

        fun call(name: String, start: Int, end: Int) =
            ValueInvocation.fromCompiler(owner, ExactDeclarationTextRange.parse(start, end).refined(), endpoint(name))
                .refined()

        fun enterArgument(evidence: RepresentationEvidence, call: ValueInvocation): RepresentationEvidence =
            evidence
                .transfer(
                    ValueTransfer.fromCompiler(evidence.site, argument(call), ValueTransferKind.ARGUMENT).refined()
                )
                .refined()

        fun output(call: ValueInvocation) =
            ValueSite.fromCompiler(owner, call.range, ValueRole.ExpressionResult).refined()

        fun argument(call: ValueInvocation) =
            ValueSite.fromCompiler(
                    owner,
                    ExactDeclarationTextRange.parse(call.range.startInclusive + 1, call.range.endExclusive - 1)
                        .refined(),
                    ValueRole.Argument(call, ValueArgumentPosition.parse(0).refined()),
                )
                .refined()
    }
}
