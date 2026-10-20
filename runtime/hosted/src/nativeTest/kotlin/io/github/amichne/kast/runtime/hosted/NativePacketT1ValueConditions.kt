package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.CompilerReceiverWireDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureWireDocument
import io.github.amichne.kast.protocol.contract.EmptyImpactValuePath
import io.github.amichne.kast.protocol.contract.ImpactCallOccurrenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFormalPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactLibraryOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactReceiverKindDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedLibraryBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValueModelApplicationDocument
import io.github.amichne.kast.protocol.contract.ImpactValueModelConditionDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePredicateDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.hasSameCallStackIdentity

/** Existing generic application fields express the missing insertion outcome without inventing a new model factory. */
internal class NativePacketT1ValueConditions(private val facts: NativePacketT1QueryFacts) {
    fun listInsertion(step: ImpactPathStepDocument): Boolean {
        val application = (step as? ImpactPathStepDocument.ModeledValue)?.application ?: return false
        val source = application.source
        val target = application.target
        val argument = source.role as? ImpactValueRoleDocument.Argument ?: return false
        if (source.enclosing != facts.echo || source.range != facts.echoDestination) return false
        if (argument.index.value != 0 || argument.invocation.range != facts.echoList) return false
        val exactResult = listResult(target)
        return exactResult &&
            source.valuePath == EmptyImpactValuePath &&
            sameContext(source, target) &&
            source.predicates == target.predicates &&
            listBinding(application)
    }

    private fun listResult(target: ImpactValueSiteReferenceDocument) =
        target.range == facts.echoList &&
            target.role == ImpactValueRoleDocument.ExpressionResult &&
            target.valuePath.values == listOf(ImpactValuePathSegmentDocument.Element(packetQueryOffset(0)))

    fun selection(step: ImpactPathStepDocument): Boolean {
        val application =
            (step as? ImpactPathStepDocument.ModeledValue)?.application
                as? ImpactValueModelApplicationDocument.CollectionSelection ?: return false
        val source = application.source
        val receiver = source.role as? ImpactValueRoleDocument.Receiver ?: return false
        if (
            receiver.kind != ImpactReceiverKindDocument.EXTENSION ||
                receiver.invocation.range != facts.s1.common.firstOrNullResult
        )
            return false
        val condition = application.condition as? ImpactValueModelConditionDocument.SelectedElement ?: return false
        val selected =
            condition.index.value == 0 &&
                condition.receiver == source.copy(valuePath = EmptyImpactValuePath) &&
                condition.result == application.target.copy(valuePath = EmptyImpactValuePath)
        return selected &&
            application.target.range == facts.s1.common.firstOrNullResult &&
            source.predicates == application.target.predicates &&
            guardedSuccess(application.target)
    }

    fun featureGuards(site: ImpactValueSiteReferenceDocument, path: ImpactPathDocument): Boolean {
        if (!guardedSuccess(site)) return false
        return site.predicates.values.filterIsInstance<ImpactValuePredicateDocument.NonNull>().any { predicate ->
            checkedAlias(predicate, path)
        }
    }

    private fun listBinding(application: ImpactValueModelApplicationDocument): Boolean {
        val binding = application.binding
        val declared = (binding.invocation as? ImpactCallOccurrenceDocument.Declared)?.invocation ?: return false
        if (declared.range != facts.echoList || binding.enclosing.declaration != facts.echo) return false
        return exactListCallable(binding) && reviewedListArtifact(binding)
    }

    private fun exactListCallable(binding: ImpactReviewedLibraryBindingDocument) =
        binding.callable.declaration.compilerIdentity == packetQueryText(LIST_IDENTITY) &&
            binding.callable.compilerTarget.compilerEvidence.signature == listSignature() &&
            binding.model.callable == listSignature()

    private fun reviewedListArtifact(binding: ImpactReviewedLibraryBindingDocument): Boolean {
        val artifact = binding.artifact
        val reviewed = binding.model.artifact
        return artifact.origin == ImpactLibraryOriginDocument.LIBRARY &&
            artifact.basis == facts.echo.basis &&
            artifact.binaryDigest.value == BINARY_SHA &&
            reviewed.binaryDigest.value == BINARY_SHA &&
            reviewed.sourceDigest.value == SOURCE_SHA &&
            artifact.version.value == VERSION &&
            reviewed.version.value == VERSION &&
            binding.model.reference.model.provenance.value == "source-sha256:$SOURCE_SHA" &&
            binding.callable.declaration.file.value.startsWith(artifact.root.value)
    }

    private fun checkedAlias(predicate: ImpactValuePredicateDocument.NonNull, path: ImpactPathDocument): Boolean {
        val transfers = path.steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().map { it.transfer }
        val reads = transfers.filter { transfer ->
            transfer.kind == ImpactTransferKindDocument.LOCAL_READ &&
                transfer.target.enclosing == facts.requireNotNull &&
                transfer.target.range.start.value == THIS_START &&
                transfer.target.range.end.value == THIS_END &&
                transfer.target.valuePath == EmptyImpactValuePath
        }
        return reads.any { read ->
            predicate.subject == read.target.compositeSubject() &&
                transfers.any { entry ->
                    entry.kind == ImpactTransferKindDocument.SOURCE_CALL_ENTRY &&
                        entry.target == read.source &&
                        sourceReceiverEntry(entry)
                }
        }
    }

    private fun sourceReceiverEntry(
        transfer: io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
    ): Boolean {
        val operation = transfer.compositeOperation() as? ImpactSourceOperationDocument.Enter ?: return false
        val receiver = transfer.source.role as? ImpactValueRoleDocument.Receiver ?: return false
        val source =
            transfer.source.range == facts.s1.common.firstOrNullResult &&
                transfer.source.executionOwner == facts.s1.origin.executionOwner &&
                transfer.source.valuePath == EmptyImpactValuePath
        return receiver.kind == ImpactReceiverKindDocument.EXTENSION &&
            receiver.invocation.range == facts.requireInvocation &&
            receiver.invocation.callable == facts.requireNotNull &&
            source &&
            operation.frame.invocation == ImpactCallOccurrenceDocument.Declared(receiver.invocation) &&
            operation.frame.callee.enclosing == facts.requireNotNull &&
            operation.position == ImpactFormalPositionDocument.Receiver(ImpactReceiverKindDocument.EXTENSION)
    }

    private fun guardedSuccess(site: ImpactValueSiteReferenceDocument) =
        site.predicates.values.any { predicate ->
            predicate is ImpactValuePredicateDocument.Subtype &&
                predicate.subject == facts.callerSuccessSubject.compositeSubject() &&
                predicate.resolvedType == facts.s1.common.successType
        }

    private fun sameContext(source: ImpactValueSiteReferenceDocument, target: ImpactValueSiteReferenceDocument) =
        source.enclosing == target.enclosing &&
            source.executionOwner == target.executionOwner &&
            source.callStack.hasSameCallStackIdentity(target.callStack)

    private fun listSignature() =
        CompilerSignatureWireDocument.Function(
            qualifiedIdentity = "kotlin.collections.listOf",
            receiver = CompilerReceiverWireDocument.Absent,
            contextReceivers = emptyList(),
            valueParameters = listOf("T"),
            typeParameterCount = 1,
        )

    private companion object {
        const val THIS_START = 1622
        const val THIS_END = 1626
        const val LIST_IDENTITY =
            "canonical-signature-sha256-v1|36cbb72898977f3dac6a92bc3191ff76013f2302a3ba6abb01dee2416c1193f8"
        const val BINARY_SHA = "0ae12504a5040ebaf37703908483420d1a5624dd1d93f357665f8c77c848a01e"
        const val SOURCE_SHA = "5c9707bb04725260c2c03d88a218dd4ea87749aefcc4663e71afa9559430f661"
        const val VERSION = "2.3.20-release-208"
    }
}
