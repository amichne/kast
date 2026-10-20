package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingFailure
import io.github.amichne.kast.protocol.contract.ImpactCallOccurrenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingProvenanceDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingRepresentationDocument
import io.github.amichne.kast.protocol.contract.ImpactModelExecutionDocument
import io.github.amichne.kast.protocol.contract.ImpactLibraryOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedLibraryBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationCurrentDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationHistoryDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationUnknownDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValueModelApplicationDocument
import io.github.amichne.kast.protocol.contract.ImpactValueModelConditionDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.WireDocumentConversion
import io.github.amichne.kast.protocol.contract.toContract
import io.github.amichne.kast.protocol.contract.validateImpactAccounting
import io.github.amichne.kast.protocol.contract.validateImpactCompletion
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactLedgerFailure
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.relation.contract.LibraryModelModuleName
import io.github.amichne.kast.relation.contract.ReviewedLibraryBinding
import io.github.amichne.kast.relation.contract.ReviewedValueModel
import io.github.amichne.kast.relation.contract.ValueExecutionBody
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueModelApplication
import io.github.amichne.kast.relation.contract.ValueModelApplicationFailure
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Detached end-to-end conservation uses scripted compiler facts and makes no native-library claim. */
class RetainedModeledValueOracleTest {
    @Test
    fun `automatic application proof survives linked retained paths findings history and native witnesses`() = runTest {
        val case = RetainedModeledValueOracleCase()
        case.execute()
        val paths = case.paths()
        val findings = case.witness(ImpactWitnessSectionDocument.FINDINGS)
        val native = case.witness(ImpactWitnessSectionDocument.NATIVE_READS)
        val applications = assertLinkedHistory(case, paths, findings)
        val records = native.items.values.map { (it as QueryResultItemDocument.ImpactWitness).item }
        assertEquals((0L..5L).toList(), records.map { it.ordinal.value })
        val witness = records[3].witness as ImpactWitnessDocument.ValueModelApplication
        assertEquals(1L, witness.observationOrdinal.value)
        assertEquals(0L, witness.applicationOrdinal.value)
        for (application in (applications + witness.application)) assertApplicationProof(case, application)
        assertNativeCounts(records.map { it.witness })
        for (page in listOf(paths, findings, native)) assertQualifiedAccounting(page)
        assertEquals(paths.question, findings.question)
        assertEquals(paths.question, native.question)
        assertEquals(3, case.nativeCalls)
        assertEquals(0, case.semanticReplayCalls)
    }

    private fun assertLinkedHistory(
        case: RetainedModeledValueOracleCase,
        paths: QueryRunResult,
        findings: QueryRunResult,
    ): List<ImpactValueModelApplicationDocument> {
        val pathRow = paths.items.values.single() as QueryResultItemDocument.ValuePath
        val pathApplication = (pathRow.path.steps.values[1] as ImpactPathStepDocument.ModeledValue).application
        val history = (pathRow.path.representation as ImpactRepresentationEvidenceDocument.Present).branches.values.single().history.values
        val historyApplication = assertHistory(case, history)
        val finding = ((findings.items.values.single() as QueryResultItemDocument.ImpactWitness).item.witness as ImpactWitnessDocument.Finding).finding
        assertEquals(case.issued.rowIds.single(), pathRow.rowId)
        assertEquals(pathRow.rowId, finding.path.pathRowId)
        assertEquals(0L, finding.path.pathOrdinal.value)
        val branch = (finding.representation as ImpactFindingRepresentationDocument.Present).branches.values.single()
        assertEquals(ImpactRepresentationCurrentDocument.Unknown(ImpactRepresentationUnknownDocument.UNMODELED_TRANSFORMATION), branch.current)
        val modeled = branch.provenance.values.filterIsInstance<ImpactFindingProvenanceDocument.ModeledValue>().single()
        assertEquals("collections.list.firstOrNull.select-zero", modeled.reference.rule.value)
        return listOf(pathApplication, historyApplication)
    }

    private fun assertHistory(
        case: RetainedModeledValueOracleCase,
        history: List<ImpactRepresentationHistoryDocument>,
    ): ImpactValueModelApplicationDocument {
        assertEquals(3, history.size)
        assertEquals("origin", (history[0] as ImpactRepresentationHistoryDocument.Origin).reference.rule.value)
        val transfer = (history[1] as ImpactRepresentationHistoryDocument.CompilerTransfer).transfer
        assertEquals(ImpactTransferKindDocument.RECEIVER, transfer.kind)
        assertTrue(transfer.source.matchesSite(case.producer.site, case.references))
        assertTrue(transfer.target.matchesSite(case.receiver, case.references))
        return (history[2] as ImpactRepresentationHistoryDocument.ModeledValue).application
    }

    @Test
    fun `retained ledger cannot substitute another actual binding or selected call`() = runTest {
        val case = RetainedModeledValueOracleCase()
        case.execute()
        val artifact = case.application.binding.artifact.copy(moduleName = LibraryModelModuleName.parse("other-module").modelValue())
        val binding = ReviewedLibraryBinding.fromCompiler(case.application.invocation, ReviewedValueModel.FirstOrNull, artifact).modelValue()
        val changed = ValueModelApplication.CollectionSelection.selectFirst(case.receiver, binding).modelValue()
        assertNotEquals(case.application.binding, changed.binding)
        val evidence = case.origin.transfer(case.intoReceiver).modelValue().modeledValue(changed).modelValue()
        val path = QueryImpactPath.fromEvidence(case.producer.site,
            listOf(QueryImpactStep.Compiler(case.intoReceiver), QueryImpactStep.ModeledValue(changed)),
            QueryImpactRepresentation.Present(evidence), case.ledger.paths.single().terminal).modelValue()
        assertEquals(Refinement.Rejected(QueryImpactLedgerFailure.UNPROVEN_MODELED_VALUE_STEP),
            QueryImpactLedger.fromEvidence(listOf(case.producer.site), case.ledger.domain, case.ledger.semantics,
                listOf(case.originRule), emptyList(), case.ledger.observations, listOf(path), originalProducers = listOf(case.producer)))
        val otherCall = ValueInvocation.fromCompiler(case.metadata.host, ExactDeclarationTextRange.parse(45, 71).modelValue(),
            case.application.invocation.callable, case.application.invocation.executionOwner).modelValue()
        val otherBinding = ReviewedLibraryBinding.fromCompiler(otherCall, ReviewedValueModel.FirstOrNull, case.application.binding.artifact).modelValue()
        assertEquals(Refinement.Rejected(ValueModelApplicationFailure.SOURCE_MISMATCH),
            ValueModelApplication.CollectionSelection.selectFirst(case.receiver, otherBinding))
        assertEquals(3, case.nativeCalls)
        assertEquals(0, case.semanticReplayCalls)
    }

    private fun assertApplicationProof(case: RetainedModeledValueOracleCase, application: ImpactValueModelApplicationDocument) {
        val selection = application as ImpactValueModelApplicationDocument.CollectionSelection
        assertTrue(selection.source.matchesSite(case.receiver, case.references))
        assertTrue(selection.target.matchesSite(case.result, case.references))
        val binding = selection.binding
        val call = case.application.invocation
        assertEquals(call.enclosing.fingerprint.value, binding.enclosing.fingerprint.value)
        assertEquals(call.callable.fingerprint.value, binding.callable.fingerprint.value)
        assertEquals(call.executionOwner.enclosing.fingerprint.value, binding.execution.enclosing.fingerprint.value)
        assertEquals(call.signature, (binding.callable.compilerTarget.compilerEvidence.signature.toContract() as WireDocumentConversion.Converted).value)
        assertEquals(QueryDiscoveryInclusionPolicyDocument.INCLUDE, binding.callable.domain.libraries)
        val declared = binding.invocation as ImpactCallOccurrenceDocument.Declared
        assertEquals(call.range.startInclusive, declared.invocation.range.start.value)
        assertEquals(call.range.endExclusive, declared.invocation.range.end.value)
        assertEquals(binding.callable.declaration, declared.invocation.callable)
        assertLibraryArtifact(binding)
        assertAnonymousExecution(case, binding.execution as ImpactModelExecutionDocument.Anonymous)
        val condition = selection.condition as ImpactValueModelConditionDocument.SelectedElement
        assertEquals(0, condition.index.value)
        assertTrue(condition.receiver.valuePath.values.isEmpty())
        assertTrue(condition.result.valuePath.values.isEmpty())
        assertTrue(condition.receiver.matchesSite(case.receiver, case.references))
        assertTrue(condition.result.matchesSite(case.result, case.references))
    }

    private fun assertLibraryArtifact(binding: ImpactReviewedLibraryBindingDocument) {
        assertEquals(ImpactValueModelProjectionCase.ROOT, binding.artifact.root.value)
        assertEquals("packet-stdlib", binding.artifact.moduleName.value)
        assertEquals(ImpactValueModelProjectionCase.BINARY_SHA, binding.artifact.binaryDigest.value)
        assertEquals(ImpactValueModelProjectionCase.VERSION, binding.artifact.version.value)
        assertEquals(ImpactLibraryOriginDocument.LIBRARY, binding.artifact.origin)
        assertEquals(ImpactValueModelProjectionCase.SOURCE_SHA, binding.model.artifact.sourceDigest.value)
        assertEquals(binding.artifact.binaryDigest, binding.model.artifact.binaryDigest)
        assertEquals(binding.callable.compilerTarget.compilerEvidence.signature, binding.model.callable)
        assertEquals("kotlin-stdlib-value-flow", binding.model.reference.model.id.value)
        assertEquals(1, binding.model.reference.model.version.value)
        assertEquals("source-sha256:${ImpactValueModelProjectionCase.SOURCE_SHA}", binding.model.reference.model.provenance.value)
        assertEquals("collections.list.firstOrNull.select-zero", binding.model.reference.rule.value)
    }

    private fun assertAnonymousExecution(case: RetainedModeledValueOracleCase, execution: ImpactModelExecutionDocument.Anonymous) {
        val body = (case.application.invocation.executionOwner.body as ValueExecutionBody.Anonymous).callable
        assertEquals(body.compilerIdentity.value, execution.body.compilerEvidence.identity)
        assertEquals(body.signature, (execution.body.compilerEvidence.signature.toContract() as WireDocumentConversion.Converted).value)
        assertEquals(body.file.stableValue, execution.body.occurrence.file)
        assertEquals(body.range.startInclusive, execution.body.occurrence.range.startInclusive)
        assertEquals(body.range.endExclusive, execution.body.occurrence.range.endExclusive)
    }

    private fun assertNativeCounts(records: List<ImpactWitnessDocument>) {
        val reads = records.filterIsInstance<ImpactWitnessDocument.NativeRead>()
        assertEquals(listOf(0L, 1L, 2L), reads.map { it.observationOrdinal.value })
        assertEquals(listOf(1L, 0L, 0L), reads.map { it.transferCount.value })
        assertEquals(listOf(0L, 1L, 0L), reads.map { it.applicationCount.value })
        assertEquals(listOf(0L, 0L, 1L), reads.map { it.obligationCount.value })
        assertEquals(listOf(1L, 1L, 1L), reads.map { it.examinedWorkUnits.value })
    }

    private fun assertQualifiedAccounting(page: QueryRunResult) {
        val accounting = page.impactAccounting as ImpactAccountingDocument.Investigated
        assertEquals(1L, accounting.originalPathCount.value)
        assertEquals(3L, accounting.originalObservationCount.value)
        assertEquals(Refinement.Refined(Unit), page.validateImpactAccounting())
        assertEquals(Refinement.Rejected(ImpactAccountingFailure.COMPLETION_NOT_CONSERVED), page.validateImpactCompletion())
    }
}
