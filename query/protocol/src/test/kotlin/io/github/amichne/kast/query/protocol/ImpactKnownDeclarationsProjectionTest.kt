package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactKnownDeclarationDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactReadRejection
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryImpactWitnessSection
import io.github.amichne.kast.query.contract.QueryImpactWitnessView
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowWorkReceipt
import io.github.amichne.kast.relation.contract.ValueKnownDeclarations
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ImpactKnownDeclarationsProjectionTest {
    @Test
    fun `the investigated question and native receipts keep full variant proofs in original order`() {
        val f = KnownProjectionCase()
        val observed =
            f.base.ledger.observations.map { original ->
                ValueFlowStep.fromCompiler(
                        original.source,
                        original.transfers,
                        original.obligations,
                        original.terminal,
                        original.domain,
                        original.examinedWorkUnits,
                        knownDeclarations = f.known,
                    )
                    .knownProjection()
            }
        val original = f.base.ledger.paths.single()
        val end = QueryImpactTerminal.SupportedDomainEnd.admit(observed.last()).knownProjection()
        val path =
            QueryImpactPath.fromEvidence(original.producer, original.steps, original.representation, end)
                .knownProjection()
        val ledger = f.ledger(observed, path)
        val accounting =
            QueryRows.ValuePaths.fromInvestigation(ledger)
                .knownProjection()
                .impactAccountingDocument(f.references)
                .knownProjection() as ImpactAccountingDocument.Investigated
        f.assertFullProofs(accounting.knownDeclarations.values)
        val view = QueryImpactWitnessView.create(ledger, QueryImpactWitnessSection.NATIVE_READS, 0, 3).knownProjection()
        assertEquals(3, view.sectionCount.value)
        val items = QueryRows.ImpactWitness.of(view).projectWitnessItems(f.references) as QueryProjection.Projected
        val witnesses = items.values.map { (it as QueryResultItemDocument.ImpactWitness).item.witness }
        assertEquals(3, witnesses.size)
        assertInstanceOf(ImpactWitnessDocument.CompilerTransfer::class.java, witnesses[1])
        for (index in listOf(0, 2)) {
            val witness = assertInstanceOf(ImpactWitnessDocument.NativeRead::class.java, witnesses[index])
            assertEquals(accounting.knownDeclarations, witness.receipts.values.single().knownDeclarations)
            f.assertFullProofs(witness.receipts.values.single().knownDeclarations.values)
        }
    }

    @Test
    fun `a rejected native read preserves the actual original known question and raw finite cause`() {
        val f = KnownProjectionCase()
        val source = f.base.producer.site
        val domain = f.base.ledger.observations.first().domain
        val work = RelationWorkCount.parse(1).knownProjection()
        val rejection =
            QueryImpactReadRejection.Native(source, domain.boundary, ValueFlowRejection.OWNER_UNAVAILABLE, work)
        val receipt =
            ValueFlowWorkReceipt.rejected(
                ValueFlowRequest(source, domain.budget, domain.boundary, knownDeclarations = f.known),
                work,
            )
        val path =
            QueryImpactPath.fromEvidence(
                    source,
                    emptyList(),
                    f.base.ledger.paths.single().representation,
                    QueryImpactTerminal.Unresolved.ReadRejected(rejection),
                )
                .knownProjection()
        val ledger = f.ledger(emptyList(), path, listOf(rejection), mapOf(source to listOf(receipt)))
        val view =
            QueryImpactWitnessView.create(ledger, QueryImpactWitnessSection.READ_REJECTIONS, 0, 1).knownProjection()
        val items = QueryRows.ImpactWitness.of(view).projectWitnessItems(f.references) as QueryProjection.Projected
        val witness =
            (items.values.single() as QueryResultItemDocument.ImpactWitness).item.witness
                as ImpactWitnessDocument.ReadRejection
        f.assertFullProofs(witness.receipts.values.single().knownDeclarations.values)
        assertEquals(
            "OWNER_UNAVAILABLE",
            (witness.rejection as io.github.amichne.kast.protocol.contract.ImpactReadRejectionDocument.Native)
                .cause
                .name,
        )
        assertEquals(0L, witness.receipts.values.single().returnedResults.value)
    }
}

private class KnownProjectionCase {
    val base = ImpactWitnessPresentationFixture()
    val references = CanonicalQueryReferences()
    private val subject = RelationEndpoint.subject(base.symbols.selector)
    private val evidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                subject.file,
                subject.range.startInclusive,
                subject.range.endExclusive,
                subject.name.value,
                (subject.qualifiedIdentity
                        as io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity.Available)
                    .value,
                subject.kind,
                subject.signature,
            )
            .knownProjection()
    private val resolved =
        RelationEndpoint.resolve(subject.lease, subject.scope, evidence, subject.constraints).knownProjection()
    val known =
        ValueKnownDeclarations.admit(listOf(current(subject), current(resolved), current(subject)), subject.lease)
            .knownProjection()

    fun ledger(
        observed: List<ValueFlowStep>,
        path: QueryImpactPath,
        rejected: List<QueryImpactReadRejection> = emptyList(),
        receipts: Map<io.github.amichne.kast.relation.contract.ValueSite, List<ValueFlowWorkReceipt>> =
            observed.associate {
                it.source to it.receipts
            },
    ) =
        QueryImpactLedger.fromEvidence(
                base.ledger.seeds,
                base.ledger.domain,
                base.ledger.semantics,
                emptyList(),
                emptyList(),
                observed,
                listOf(path),
                rejected,
                listOf(base.producer),
                readReceipts = receipts,
                knownDeclarations = known,
            )
            .knownProjection()

    fun assertFullProofs(actual: List<ImpactKnownDeclarationDocument>) {
        assertEquals(3, actual.size)
        assertInstanceOf(ImpactKnownDeclarationDocument.Subject::class.java, actual[0])
        assertInstanceOf(ImpactKnownDeclarationDocument.Resolved::class.java, actual[1])
        assertEquals(actual[0], actual[2])
        for ((proof, output) in known.declarations.zip(actual)) {
            val endpoint = proof.endpoint
            assertEquals(endpoint.file.stableValue, output.endpoint.declaration.file.value)
            assertEquals(endpoint.range.startInclusive, output.endpoint.declaration.range.start.value)
            assertEquals(endpoint.range.endExclusive, output.endpoint.declaration.range.end.value)
            assertEquals(endpoint.compilerIdentity.value, output.endpoint.compilerTarget.compilerEvidence.identity)
            assertEquals(endpoint.fingerprint.value, output.endpoint.fingerprint.value)
            assertEquals(relationDomainDocument(endpoint.scope, endpoint.constraints), output.endpoint.domain)
        }
    }

    private fun current(endpoint: RelationEndpoint) =
        RevalidatedRelationEndpoint.validate(endpoint, evidence).knownProjection()
}

private fun <T, F> Refinement<T, F>.knownProjection(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Known declaration projection fixture rejected: $failure")
    }
