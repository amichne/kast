package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.EmptyImpactCallStack
import io.github.amichne.kast.protocol.contract.EmptyImpactValuePath
import io.github.amichne.kast.protocol.contract.ImpactCallOccurrenceDocument
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFormalPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactReceiverKindDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePredicateDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.hasSameCallStackIdentity
import junit.framework.TestCase.assertTrue

/** C2.4 assesses one original-S2 witness. It never explores or requests another native hop. */
internal class NativePacketCompositeQueryErrorOracle(private val facts: NativePacketCompositeQueryErrorFacts) {
    fun assertRequiredRow(paths: List<ImpactPathDocument>) {
        val stages = paths.map(::stage)
        assertTrue("C2.4 has no complete original error mapper route: $stages", C24Stage.PASSED in stages)
    }

    fun assertForbiddenWitnesses(paths: List<ImpactPathDocument>) {
        val codeSites = paths.flatMap { it.compositeSites() }.filter(::carriesClientCode)
        val unguardedSites = codeSites.filterNot(::guardedError)
        assertTrue(
            "C2.4 emitted ClientError.code without the original Error predicate: ${unguardedSites.map { it.range }}",
            unguardedSites.isEmpty(),
        )
        val successSites = codeSites.filter { guarded(it, facts.successType) }
        assertTrue(
            "C2.4 emitted a Success-qualified ClientError.code: ${successSites.map { it.range }}",
            successSites.isEmpty(),
        )
        val debugPaths = paths.filter { path ->
            val transfers = path.compilerTransfers()
            transfers.any { reads(it, facts.wireDebugMessage) } && transfers.any(::storesClientCode)
        }
        assertTrue("C2.4 emitted debugMessage into ClientError.code on ${debugPaths.size} paths", debugPaths.isEmpty())
    }

    private fun stage(path: ImpactPathDocument): C24Stage {
        if (!path.compositeContiguous()) return C24Stage.CONTIGUITY_MISSING
        val route = path.compilerTransfers()
        val payload =
            route.next(0) { reads(it, facts.errorValue) && guardedError(it.target) }
                ?: return C24Stage.ERROR_PAYLOAD_MISSING
        val entry = route.next(payload + 1, ::entersMapper) ?: return C24Stage.MAPPER_ENTRY_MISSING
        val context = route[entry].target
        val code =
            route.next(entry + 1) { reads(it, facts.wireCode) && sameContext(context, it.source) }
                ?: return C24Stage.WIRE_CODE_MISSING
        val stored = route.next(code + 1, ::storesClientCode) ?: return C24Stage.CLIENT_CODE_STORAGE_MISSING
        if (!sameContext(context, route[stored].target)) return C24Stage.MAPPER_CONTEXT_CHANGED
        val returned = route.next(stored + 1) { returnsMapper(it, context) } ?: return C24Stage.MAPPER_RETURN_MISSING
        return if (route.subList(payload, returned + 1).all { guardedError(it.target) }) C24Stage.PASSED
        else C24Stage.ORIGINAL_ERROR_GUARD_MISSING
    }

    private fun entersMapper(transfer: ImpactCompilerTransferDocument): Boolean {
        val operation = transfer.compositeOperation() as? ImpactSourceOperationDocument.Enter ?: return false
        val declared = operation.frame.invocation as? ImpactCallOccurrenceDocument.Declared ?: return false
        if (
            transfer.kind != ImpactTransferKindDocument.SOURCE_CALL_ENTRY ||
                operation.frame.callee.enclosing != facts.mapper
        )
            return false
        val exactInvocation =
            declared.invocation.callable == facts.mapper && declared.invocation.range == facts.mapperInvocation
        val extension =
            operation.position == ImpactFormalPositionDocument.Receiver(ImpactReceiverKindDocument.EXTENSION)
        return exactInvocation && extension && guardedError(transfer.target)
    }

    private fun storesClientCode(transfer: ImpactCompilerTransferDocument): Boolean {
        val storage =
            (transfer.compositeOperation() as? ImpactSourceOperationDocument.ConstructField)?.storage ?: return false
        val exactField = storage.field == facts.clientCode && storage.parameter.range == facts.clientCode.range
        val exactParameter = storage.parameter.index.value == 0 && storage.parameter.name == packetQueryText("code")
        if (
            transfer.kind != ImpactTransferKindDocument.CONSTRUCTOR_FIELD ||
                storage.constructor != facts.constructor ||
                storage.parameter.callable != facts.constructor
        )
            return false
        return exactField && exactParameter && exactClientCodePath(transfer.target)
    }

    private fun returnsMapper(
        transfer: ImpactCompilerTransferDocument,
        context: ImpactValueSiteReferenceDocument,
    ): Boolean {
        val target = transfer.target
        val occurrence =
            target.copy(
                valuePath = EmptyImpactValuePath,
                callStack = EmptyImpactCallStack,
                predicates = facts.mapperReturn.predicates,
            )
        if (transfer.kind != ImpactTransferKindDocument.RETURN || occurrence != facts.mapperReturn) return false
        return exactClientCodePath(target) && sameContext(context, target) && guardedError(target)
    }

    private fun sameContext(first: ImpactValueSiteReferenceDocument, second: ImpactValueSiteReferenceDocument) =
        first.enclosing == second.enclosing &&
            first.executionOwner == second.executionOwner &&
            first.callStack.hasSameCallStackIdentity(second.callStack)

    private fun guardedError(site: ImpactValueSiteReferenceDocument) = guarded(site, facts.errorType)

    private fun guarded(site: ImpactValueSiteReferenceDocument, type: ImpactDeclarationReferenceDocument) =
        site.predicates.values.any { predicate ->
            predicate is ImpactValuePredicateDocument.Subtype &&
                predicate.subject == facts.executeReceiver.compositeSubject() &&
                predicate.resolvedType == type
        }

    private fun reads(transfer: ImpactCompilerTransferDocument, field: ImpactDeclarationReferenceDocument) =
        transfer.kind == ImpactTransferKindDocument.FIELD_READ &&
            (transfer.compositeOperation() as? ImpactSourceOperationDocument.ReadField)?.field == field

    private fun carriesClientCode(site: ImpactValueSiteReferenceDocument) =
        ImpactValuePathSegmentDocument.Field(facts.clientCode) in site.valuePath.values

    private fun exactClientCodePath(site: ImpactValueSiteReferenceDocument) =
        site.valuePath.values == listOf(ImpactValuePathSegmentDocument.Field(facts.clientCode))

    private fun ImpactPathDocument.compilerTransfers() =
        steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().map { it.transfer }

    private fun List<ImpactCompilerTransferDocument>.next(
        start: Int,
        matches: (ImpactCompilerTransferDocument) -> Boolean,
    ) = indices.firstOrNull { it >= start && matches(this[it]) }
}

private enum class C24Stage {
    CONTIGUITY_MISSING,
    ERROR_PAYLOAD_MISSING,
    MAPPER_ENTRY_MISSING,
    WIRE_CODE_MISSING,
    CLIENT_CODE_STORAGE_MISSING,
    MAPPER_CONTEXT_CHANGED,
    MAPPER_RETURN_MISSING,
    ORIGINAL_ERROR_GUARD_MISSING,
    PASSED,
}
