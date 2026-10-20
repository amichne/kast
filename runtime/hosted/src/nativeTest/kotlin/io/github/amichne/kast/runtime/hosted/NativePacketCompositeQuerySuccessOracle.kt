package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.EmptyImpactValuePath
import io.github.amichne.kast.protocol.contract.ImpactCallOccurrenceDocument
import io.github.amichne.kast.protocol.contract.ImpactCallTargetDocument
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactFormalOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactFormalPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePredicateDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import junit.framework.TestCase.assertTrue

/** Every required success row must occur on one contiguous original-S2 witness. */
internal class NativePacketCompositeQuerySuccessOracle(private val facts: NativePacketCompositeQueryFacts) {
    fun assertSourcePrefixThroughC26(paths: List<ImpactPathDocument>) {
        val assessments = paths.map(::rows)
        assertTrue(
            "Original S2 lacks one contiguous C2.1/C2.2/C2.5/C2.6 source route: $assessments",
            assessments.any { rows ->
                rows.take(SOURCE_PREFIX_ROW_COUNT).size == SOURCE_PREFIX_ROW_COUNT &&
                    rows.take(SOURCE_PREFIX_ROW_COUNT).all { it.passed }
            },
        )
    }

    fun assertRequiredRows(paths: List<ImpactPathDocument>) {
        val assessments = paths.map(::rows)
        assertTrue(
            "Original S2 lacks a single complete C2.1/C2.2/C2.5-C2.9 route: $assessments",
            assessments.any { rows -> rows.all { it.passed } },
        )
    }

    fun assertForbiddenWitnesses(paths: List<ImpactPathDocument>) {
        val sites = paths.flatMap { it.compositeSites() }
        val requestArguments = sites.filter { site ->
            val argument = site.role as? ImpactValueRoleDocument.Argument
            argument?.invocation?.callable in setOf(facts.apiReadOffers, facts.wireConstructor, facts.headerBuild)
        }
        assertTrue("C2.N1 emitted request-side arguments: $requestArguments", requestArguments.isEmpty())
        val unguardedLinks = sites.filter { isFeatureLink(it) && !guardedNonNull(it, sites) }
        assertTrue(
            "C2.8/C2.9 emitted a feature link without its exact guards: $unguardedLinks",
            unguardedLinks.isEmpty(),
        )
        val backendErrorLinks = sites.filter { site ->
            isFeatureLink(site) &&
                site.predicates.values.any { predicate ->
                    predicate is ImpactValuePredicateDocument.Subtype &&
                        predicate.subject == facts.executeReceiver.compositeSubject() &&
                        predicate.resolvedType == facts.errorType
                }
        }
        assertTrue("C2.N2 emitted a backend-error feature link: $backendErrorLinks", backendErrorLinks.isEmpty())
    }

    private fun rows(path: ImpactPathDocument): List<CompositeRequiredRow> {
        if (!path.compositeContiguous()) return listOf(CompositeRequiredRow("CONTIGUITY", false))
        val route = path.steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().map { it.transfer }
        val stages = CompositeSuccessRows(route, facts)
        return listOf(
            stages.row("C2.1", ::callbackEntry),
            stages.row("C2.2") { clientMapping(it) },
            stages.row("C2.5") { clientReturn(it) },
            stages.row("C2.6") { matchedCallerReturn(it) },
            stages.row("C2.7") { responseBinding(it) },
            stages.row("C2.8") { featureConstruction(it, path.compositeSites()) },
            stages.row("C2.9") { featureReturn(it, path.compositeSites()) },
        )
    }

    private fun callbackEntry(transfer: ImpactCompilerTransferDocument): Boolean {
        val operation = transfer.compositeOperation() as? ImpactSourceOperationDocument.Enter ?: return false
        val callback = operation.frame.target as? ImpactCallTargetDocument.Callback ?: return false
        val selected =
            callback.selected.occurrence.range.startInclusive == facts.transformInvoke.start.value &&
                callback.selected.occurrence.range.endExclusive == facts.transformInvoke.end.value
        val supplied =
            callback.supplyingFrame.invocation ==
                (facts.executeReceiver.role as ImpactValueRoleDocument.Receiver).invocation
        val formal = implicitMapFormal(operation.position, transfer.target.executionOwner)
        val guarded = guardedSuccess(transfer.target)
        println("COMPOSITE_CALLBACK_ORACLE selected=$selected supplied=$supplied formal=$formal guarded=$guarded")
        return transfer.kind == ImpactTransferKindDocument.SOURCE_CALL_ENTRY &&
            selected &&
            supplied &&
            formal &&
            guarded
    }

    private fun implicitMapFormal(
        position: ImpactFormalPositionDocument,
        execution: ImpactExecutionOwnerDocument,
    ): Boolean {
        val formal = (position as? ImpactFormalPositionDocument.Parameter)?.parameter ?: return false
        val owner = execution as? ImpactExecutionOwnerDocument.Anonymous ?: return false
        return owner.body.range == facts.mapImplicitBody &&
            formal.executionOwner == owner &&
            formal.origin == ImpactFormalOriginDocument.Implicit &&
            formal.index.value == 0 &&
            formal.name == packetQueryText("it")
    }

    private fun clientMapping(transfer: ImpactCompilerTransferDocument): Boolean {
        val returned = transfer.compositeOperation() as? ImpactSourceOperationDocument.Return ?: return false
        return transfer.kind == ImpactTransferKindDocument.SOURCE_CALL_RETURN &&
            returned.frame.callee.enclosing == facts.mapper &&
            path(transfer.target, facts.clientLinks) &&
            guardedSuccess(transfer.target)
    }

    private fun clientReturn(transfer: ImpactCompilerTransferDocument): Boolean =
        transfer.kind == ImpactTransferKindDocument.RETURN &&
            transfer.target.enclosing == facts.clientMethod &&
            transfer.target.range == facts.clientReturn &&
            path(transfer.target, facts.successValue, facts.clientLinks) &&
            guardedSuccess(transfer.target)

    private fun matchedCallerReturn(transfer: ImpactCompilerTransferDocument): Boolean {
        val operation = transfer.compositeOperation()
        val frame =
            when (operation) {
                is ImpactSourceOperationDocument.Return -> operation.frame
                is ImpactSourceOperationDocument.SeedReturn -> operation.frame
                else -> return false
            }
        return transfer.kind == ImpactTransferKindDocument.SOURCE_CALL_RETURN &&
            facts.callerFrame.matches(frame) &&
            transfer.target.range == facts.callerFrame.invocation.range &&
            path(transfer.target, facts.successValue, facts.clientLinks) &&
            guardedSuccess(transfer.target)
    }

    private fun responseBinding(transfer: ImpactCompilerTransferDocument): Boolean =
        transfer.target.enclosing == facts.getOfferReturn.enclosing &&
            transfer.target.range == facts.responseBinding &&
            transfer.target.role == ImpactValueRoleDocument.LocalBinding &&
            path(transfer.target, facts.clientLinks) &&
            guardedSuccess(transfer.target)

    private fun featureConstruction(
        transfer: ImpactCompilerTransferDocument,
        sites: List<ImpactValueSiteReferenceDocument>,
    ): Boolean {
        val operation = transfer.compositeOperation() as? ImpactSourceOperationDocument.ConstructField ?: return false
        return transfer.kind == ImpactTransferKindDocument.CONSTRUCTOR_FIELD &&
            operation.storage.constructor == facts.featureConstructor &&
            operation.storage.field == facts.featureLink &&
            operation.storage.parameter.index.value == 0 &&
            transfer.target.range == facts.featureResult &&
            path(transfer.target, facts.featureLink) &&
            guardedNonNull(transfer.target, sites)
    }

    private fun featureReturn(
        transfer: ImpactCompilerTransferDocument,
        sites: List<ImpactValueSiteReferenceDocument>,
    ): Boolean =
        transfer.kind == ImpactTransferKindDocument.RETURN &&
            transfer.target.copy(valuePath = EmptyImpactValuePath, predicates = facts.getOfferReturn.predicates) ==
                facts.getOfferReturn &&
            path(transfer.target, facts.successValue, facts.featureLink) &&
            guardedNonNull(transfer.target, sites)

    private fun guardedSuccess(site: ImpactValueSiteReferenceDocument) =
        site.predicates.values.any { predicate ->
            predicate is ImpactValuePredicateDocument.Subtype &&
                predicate.subject == facts.executeReceiver.compositeSubject() &&
                predicate.resolvedType == facts.successType
        }

    private fun guardedNonNull(
        site: ImpactValueSiteReferenceDocument,
        sites: List<ImpactValueSiteReferenceDocument>,
    ): Boolean =
        guardedSuccess(site) &&
            site.predicates.values.any { predicate ->
                predicate is ImpactValuePredicateDocument.NonNull &&
                    predicate.subject.enclosing == facts.getOfferReturn.enclosing &&
                    predicate.subject.range == facts.firstOrNullResult &&
                    predicate.subject.valuePath == EmptyImpactValuePath &&
                    (predicate.subject.executionOwner as? ImpactExecutionOwnerDocument.Anonymous)?.body?.range ==
                        facts.sequentialBody &&
                    sites.any { candidate ->
                        candidate.copy(valuePath = EmptyImpactValuePath).compositeSubject() == predicate.subject
                    }
            }

    private fun isFeatureLink(site: ImpactValueSiteReferenceDocument) =
        site.enclosing == facts.getOfferReturn.enclosing &&
            site.valuePath.values.any { it == ImpactValuePathSegmentDocument.Field(facts.featureLink) }

    private fun path(site: ImpactValueSiteReferenceDocument, vararg fields: ImpactDeclarationReferenceDocument) =
        site.valuePath.values == fields.map(ImpactValuePathSegmentDocument::Field)

    private companion object {
        const val SOURCE_PREFIX_ROW_COUNT = 4
    }
}

/** The cursor only assesses a fixed list of emitted transfers and keeps the first missing row. */
private class CompositeSuccessRows(
    private val route: List<ImpactCompilerTransferDocument>,
    private val facts: NativePacketCompositeQueryFacts,
) {
    private var next = 0
    private var blocked = false

    fun row(id: String, matches: (ImpactCompilerTransferDocument) -> Boolean): CompositeRequiredRow {
        if (blocked) return CompositeRequiredRow("$id:NOT_REACHED", false)
        val selected = route.indices.firstOrNull { it >= next && matches(route[it]) }
        if (selected == null) {
            blocked = true
            return CompositeRequiredRow("$id:MISSING", false)
        }
        val proofMissing =
            when (id) {
                "C2.1" -> !payloadProof(next, selected)
                "C2.2" -> !mappingProofs(next, selected)
                "C2.5" -> !carrierProof(next, selected)
                "C2.7" -> !bindingProof(next, selected)
                else -> false
            }
        if (proofMissing) {
            blocked = true
            return CompositeRequiredRow("$id:MISSING_SOURCE_PROOF", false)
        }
        next = selected + 1
        return CompositeRequiredRow(id, true)
    }

    private fun payloadProof(start: Int, end: Int) =
        route.subList(start, end + 1).any { transfer ->
            val operation = transfer.compositeOperation() as? ImpactSourceOperationDocument.ReadField
            transfer.kind == ImpactTransferKindDocument.FIELD_READ &&
                operation?.field == facts.successValue &&
                transfer.target.range == facts.transformArgument
        }

    private fun carrierProof(start: Int, end: Int): Boolean {
        val prefix = route.subList(start, end + 1)
        return listOf(facts.mapInvocation, facts.mapErrorInvocation).all { invocation ->
            val expected = ImpactCallOccurrenceDocument.Declared(invocation)
            val rebuilt = prefix.any { transfer ->
                val storage = (transfer.compositeOperation() as? ImpactSourceOperationDocument.ConstructField)?.storage
                transfer.kind == ImpactTransferKindDocument.CONSTRUCTOR_FIELD &&
                    storage?.field == facts.successValue &&
                    storage.parameter.index.value == 0 &&
                    storage.parameter.name == packetQueryText("value") &&
                    storage.parameter.range == facts.successValue.range &&
                    transfer.source.callStack.values.any { it.invocation == expected }
            }
            val returned = prefix.any { transfer ->
                val operation = transfer.compositeOperation() as? ImpactSourceOperationDocument.Return
                transfer.kind == ImpactTransferKindDocument.SOURCE_CALL_RETURN &&
                    operation?.frame?.invocation == expected
            }
            rebuilt && returned
        }
    }

    private fun bindingProof(start: Int, end: Int): Boolean {
        val prefix = route.subList(start, end + 1)
        val callees = prefix.mapNotNull {
            (it.compositeOperation() as? ImpactSourceOperationDocument.Enter)?.frame?.callee?.enclosing
        }
        val payloads = prefix.mapNotNull {
            (it.compositeOperation() as? ImpactSourceOperationDocument.ReadField)?.field
        }
        return facts.component1 in callees && facts.bind in callees && facts.successValue in payloads
    }

    private fun mappingProofs(start: Int, end: Int): Boolean {
        val prefix = route.subList(start, end + 1)
        val fields = prefix.mapNotNull { (it.compositeOperation() as? ImpactSourceOperationDocument.ReadField)?.field }
        val storage = prefix.mapNotNull {
            (it.compositeOperation() as? ImpactSourceOperationDocument.ConstructField)?.storage?.field
        }
        return facts.envelopeBody in fields && facts.wireLinks in fields && facts.clientLinks in storage
    }
}
