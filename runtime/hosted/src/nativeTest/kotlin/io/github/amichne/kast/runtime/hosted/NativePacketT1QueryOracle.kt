package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.EmptyImpactValuePath
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFormalPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import junit.framework.TestCase.assertTrue

/**
 * The full Echo positive needs one original-constructor witness through storage, selection, guards and feature return.
 */
internal class NativePacketT1QueryOracle(private val facts: NativePacketT1QueryFacts) {
    private val dispatch = NativePacketT1DispatchOracle(facts)
    private val conditions = NativePacketT1ValueConditions(facts)

    fun assertCombinedFullEchoRoute(paths: List<ImpactPathDocument>) {
        dispatch.assertCombinedTargets(paths)
        val rows = paths.map(::rows)
        assertTrue(
            "T1 has no complete original-S1 Echo route through the getOffer return: $rows",
            rows.any { it.all { row -> row.passed } },
        )
    }

    fun assertCombinedForbiddenWitnesses(paths: List<ImpactPathDocument>) {
        dispatch.assertCombinedTargets(paths)
        val production = paths.filter { path ->
            dispatch.frames(listOf(path)).any { frame -> frame.callee.enclosing == facts.s1.common.clientMethod }
        }
        assertTrue("The combined control needs actual production evidence", production.isNotEmpty())
        NativePacketS1QueryOracle(facts.s1).assertProductionForbiddenWitnesses(production)
        NativePacketS1QueryOracle(facts.s1).assertNoLoggerArguments(paths)
        val field = ImpactValuePathSegmentDocument.Field(facts.s1.common.featureLink)
        val wrongGuards = paths.flatMap { path ->
            path.compositeSites().filter { site ->
                field in site.valuePath.values && !conditions.featureGuards(site, path)
            }
        }
        assertTrue(
            "The combined domain emitted feature links without exact Echo-success and checked-value guards",
            wrongGuards.isEmpty(),
        )
        val token = paths.filter { path ->
            path.compilerTransfers().any {
                (it.compositeOperation() as? ImpactSourceOperationDocument.ReadField)?.field == facts.s1.token
            } && path.compositeSites().any { field in it.valuePath.values }
        }
        assertTrue("The Echo link acquired the same-typed sessionToken field", token.isEmpty())
    }

    private fun rows(path: ImpactPathDocument): List<CompositeRequiredRow> {
        if (path.producer != facts.s1.origin || !path.compositeContiguous())
            return listOf(CompositeRequiredRow("S1_ORIGINAL", false))
        val cursor = T1RetainedRows(path.steps.values)
        val entered = cursor.compiler("T1:Echo-implementation-entry", ::echoEntry)
        val destination = cursor.compiler("T1:exact-destination-read", ::destinationRead)
        val list = cursor.row("T1:reviewed-single-element-listOf", conditions::listInsertion)
        val stored = cursor.compiler("T1:ClientResponse.links[0]", ::clientStorage)
        val echoReturn = cursor.compiler("T1:matched-Echo-return", ::echoReturn)
        val binding =
            cursor.compiler("T1:source-component-and-bind") { transfer ->
                val entered = transfer.compositeOperation() as? ImpactSourceOperationDocument.Enter
                transfer.kind == ImpactTransferKindDocument.SOURCE_CALL_ENTRY &&
                    entered?.frame?.callee?.enclosing == facts.s1.common.bind
            }
        val selected = cursor.row("T1:exact-firstOrNull-selection", conditions::selection)
        val feature = cursor.compiler("T1:FeatureResponse.link") { featureStorage(it, path) }
        val returned = cursor.compiler("T1:getOffer.Success.value.link") { featureReturn(it, path) }
        return listOf(entered, destination, list, stored, echoReturn, binding, selected, feature, returned)
    }

    private fun echoEntry(transfer: ImpactCompilerTransferDocument): Boolean {
        val operation = transfer.compositeOperation() as? ImpactSourceOperationDocument.Enter ?: return false
        return transfer.kind == ImpactTransferKindDocument.SOURCE_CALL_ENTRY &&
            operation.position == ImpactFormalPositionDocument.Parameter(facts.echoFormal) &&
            dispatch.matches(operation.frame, facts.testOwnership, NativePacketCompositeQueryPolicy.COMBINED) &&
            transfer.target.valuePath == EmptyImpactValuePath
    }

    private fun destinationRead(transfer: ImpactCompilerTransferDocument) =
        transfer.kind == ImpactTransferKindDocument.FIELD_READ &&
            transfer.target.enclosing == facts.echo &&
            transfer.target.range == facts.echoDestination &&
            (transfer.compositeOperation() as? ImpactSourceOperationDocument.ReadField)?.field == facts.s1.destination

    private fun clientStorage(transfer: ImpactCompilerTransferDocument): Boolean {
        val operation = transfer.compositeOperation() as? ImpactSourceOperationDocument.ConstructField ?: return false
        return transfer.kind == ImpactTransferKindDocument.CONSTRUCTOR_FIELD &&
            operation.invocation.range == facts.echoConstructor &&
            operation.storage.field == facts.s1.common.clientLinks &&
            operation.storage.parameter.index.value == 0 &&
            transfer.target.valuePath.values ==
                listOf(ImpactValuePathSegmentDocument.Field(facts.s1.common.clientLinks), elementZero())
    }

    private fun echoReturn(transfer: ImpactCompilerTransferDocument): Boolean {
        val returned = transfer.compositeOperation() as? ImpactSourceOperationDocument.Return ?: return false
        if (transfer.kind != ImpactTransferKindDocument.SOURCE_CALL_RETURN) return false
        return dispatch.matches(returned.frame, facts.testOwnership, NativePacketCompositeQueryPolicy.COMBINED) &&
            transfer.source.range == facts.echoReturn &&
            transfer.source.role == ImpactValueRoleDocument.Return &&
            transfer.target.range == facts.s1.common.callerFrame.invocation.range &&
            path(transfer.target, facts.s1.common.successValue, facts.s1.common.clientLinks) == listOf(elementZero())
    }

    private fun featureStorage(transfer: ImpactCompilerTransferDocument, witness: ImpactPathDocument): Boolean {
        val field =
            (transfer.compositeOperation() as? ImpactSourceOperationDocument.ConstructField)?.storage ?: return false
        return transfer.kind == ImpactTransferKindDocument.CONSTRUCTOR_FIELD &&
            field.constructor == facts.s1.common.featureConstructor &&
            field.field == facts.s1.common.featureLink &&
            field.parameter.index.value == 0 &&
            transfer.target.range == facts.s1.common.featureResult &&
            path(transfer.target, facts.s1.common.featureLink)?.isEmpty() == true &&
            conditions.featureGuards(transfer.target, witness)
    }

    private fun featureReturn(transfer: ImpactCompilerTransferDocument, witness: ImpactPathDocument): Boolean {
        val expected = facts.s1.common.getOfferReturn
        val target = transfer.target
        return transfer.kind == ImpactTransferKindDocument.RETURN &&
            target.copy(valuePath = EmptyImpactValuePath, predicates = expected.predicates) == expected &&
            path(target, facts.s1.common.successValue, facts.s1.common.featureLink)?.isEmpty() == true &&
            conditions.featureGuards(target, witness)
    }

    private fun path(
        site: ImpactValueSiteReferenceDocument,
        vararg fields: ImpactDeclarationReferenceDocument,
    ): List<ImpactValuePathSegmentDocument>? {
        val prefix = fields.map(ImpactValuePathSegmentDocument::Field)
        return if (site.valuePath.values.take(prefix.size) == prefix) site.valuePath.values.drop(prefix.size) else null
    }

    private fun elementZero() = ImpactValuePathSegmentDocument.Element(packetQueryOffset(0))

    private fun ImpactPathDocument.compilerTransfers() =
        steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().map { it.transfer }
}

private class T1RetainedRows(private val steps: List<ImpactPathStepDocument>) {
    private var cursor = 0
    private var blocked = false

    fun compiler(id: String, matches: (ImpactCompilerTransferDocument) -> Boolean) =
        row(id) { step ->
            step is ImpactPathStepDocument.Compiler && matches(step.transfer)
        }

    fun row(id: String, matches: (ImpactPathStepDocument) -> Boolean): CompositeRequiredRow {
        if (blocked) return CompositeRequiredRow("$id:NOT_REACHED", false)
        val found = steps.indices.firstOrNull { it >= cursor && matches(steps[it]) }
        if (found == null) {
            blocked = true
            return CompositeRequiredRow("$id:MISSING", false)
        }
        cursor = found + 1
        return CompositeRequiredRow(id, true)
    }
}
