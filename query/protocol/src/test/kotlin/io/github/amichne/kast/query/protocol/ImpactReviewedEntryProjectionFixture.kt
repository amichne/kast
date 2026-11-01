package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.relation.contract.MapEntrySlot
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ReviewedCallValueBinding
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedEntryComponentInvocation
import io.github.amichne.kast.relation.contract.ReviewedLibraryArtifact
import io.github.amichne.kast.relation.contract.ReviewedOrdinaryLibraryCallable
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

internal class ImpactReviewedEntryProjectionFixture {
    val base = ImpactReviewedComponentFixture()
    private val mapped = ImpactReviewedMappedProjectionFixture()
    val owner = base.owner
    val binding =
        ReviewedCallbackValueBinding.admit(
                owner,
                callbackBody(),
                RelationOccurrence.fromBoundary(owner.file, 105, 190).componentValue(),
                RelationCallableBody.Named.fromCompiler(owner.evidence).componentValue(),
                mapped.binding.selected,
                mapped.binding.artifact,
            )
            .componentValue()
    private val call =
        ValueInvocation.fromCompiler(
                owner,
                binding.occurrence.range,
                RelationEndpoint.resolve(owner.lease, owner.scope, binding.target).componentValue(),
            )
            .componentValue()
    private val formal =
        ValueFormalParameter.fromCompiler(
                owner,
                ValueArgumentPosition.parse(0).componentValue(),
                SymbolDiscoveryCandidateName.parse("entry").componentValue(),
                componentRange(120, 121),
                ValueExecutionOwner.reviewedCallback(binding),
            )
            .componentValue()

    fun receiver(key: Boolean, whole: Boolean = false): ValueSite {
        val original = base.associated(base.construct(first = key)).target
        return ValueSite.fromCompiler(
                owner,
                componentRange(105, 110),
                ValueRole.Receiver(call, ValueReceiverKind.EXTENSION),
                valuePath =
                    if (whole) io.github.amichne.kast.relation.contract.ValueAccessPath.Whole else original.valuePath,
                conditions = original.conditions,
                callStack = original.callStack,
            )
            .componentValue()
    }

    fun input(key: Boolean): ReviewedValueTransfer =
        ReviewedValueTransfer.callbackInput(receiver(key), binding, formal).componentValue()

    fun preserved(): ReviewedValueTransfer =
        ReviewedValueTransfer.mapPreservedKey(receiver(true), binding).componentValue()

    fun contentInput(key: Boolean): ReviewedValueTransfer =
        ReviewedValueTransfer.entryContentInput(
                receiver(false, whole = true),
                binding,
                formal,
                if (key) MapEntrySlot.KEY else MapEntrySlot.VALUE,
            )
            .componentValue()

    fun preservedContent(): ReviewedValueTransfer =
        ReviewedValueTransfer.mapPreservedKeyContent(receiver(false, whole = true), binding).componentValue()

    fun mappedContentReturn(): ReviewedValueTransfer {
        val value = read(false, content = true).target
        val returned =
            ValueSite.fromCompiler(
                    owner,
                    componentRange(150, 160),
                    ValueRole.Return,
                    value.executionOwner,
                    valuePath = value.valuePath,
                    conditions = value.conditions,
                    callStack = value.callStack,
                )
                .componentValue()
        return ReviewedValueTransfer.callbackResult(returned, binding).componentValue()
    }

    fun read(key: Boolean, component: Boolean = false, content: Boolean = false): ReviewedValueTransfer {
        val input = if (content) contentInput(key) else input(key)
        val target = selected(key, component)
        val selected = ReviewedOrdinaryLibraryCallable.admit(target).componentValue()
        val endpoint = RelationEndpoint.resolve(owner.lease, owner.scope, target).componentValue()
        val invocation =
            if (component)
                ValueInvocation.fromCompiler(
                        owner,
                        componentRange(130, 140),
                        endpoint,
                        input.target.executionOwner,
                    )
                    .componentValue()
            else
                ValueInvocation.getterFromCompiler(
                        owner,
                        componentRange(130, 140),
                        endpoint,
                        input.target.executionOwner,
                    )
                    .componentValue()
        val artifact =
            ReviewedLibraryArtifact.admit(
                    selected,
                    binding.artifact.binaryArtifact,
                    binding.artifact.sourceArtifact,
                )
                .componentValue()
        val authority = ReviewedCallValueBinding.admit(invocation, selected, artifact).componentValue()
        val receiver =
            ValueSite.fromCompiler(
                    owner,
                    componentRange(130, 135),
                    ValueRole.Receiver(
                        invocation,
                        if (component) ValueReceiverKind.EXTENSION else ValueReceiverKind.DISPATCH,
                    ),
                    input.target.executionOwner,
                    valuePath = input.target.valuePath,
                    conditions = input.target.conditions,
                    callStack = input.target.callStack,
                )
                .componentValue()
        return ReviewedValueTransfer.entryProject(receiver, authority).componentValue()
    }

    fun destructuredSecond(skippedFirst: Boolean = false): ReviewedValueTransfer {
        val direct = read(false, component = true)
        val current = direct.binding as ReviewedCallValueBinding
        val prefix = if (skippedFirst) emptyList() else listOf(firstComponent(direct.source.executionOwner))
        val binding =
            ReviewedCallValueBinding.admitDestructuredEntry(
                    current.invocation,
                    current.selected,
                    current.artifact,
                    componentRange(120, 145),
                    prefix,
                )
                .componentValue()
        return ReviewedValueTransfer.entryProject(direct.source, binding).componentValue()
    }

    private fun firstComponent(execution: ValueExecutionOwner): ReviewedEntryComponentInvocation {
        val selected = ReviewedOrdinaryLibraryCallable.admit(selected(true, component = true)).componentValue()
        val endpoint = RelationEndpoint.resolve(owner.lease, owner.scope, selected.target).componentValue()
        val invocation =
            ValueInvocation.fromCompiler(owner, componentRange(122, 126), endpoint, execution).componentValue()
        val artifact =
            ReviewedLibraryArtifact.admit(selected, binding.artifact.binaryArtifact, binding.artifact.sourceArtifact)
                .componentValue()
        return ReviewedEntryComponentInvocation.fromCompiler(invocation, selected, artifact).componentValue()
    }

    private fun callbackBody(): RelationCallableBody.Anonymous {
        val range = componentRange(120, 180)
        val signature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(owner.file, range),
                    null,
                    emptyList(),
                    listOf("kotlin/collections/Map.Entry<K,V>"),
                    0,
                )
                .componentValue() as CanonicalCompilerSignature.Function
        return RelationCallableBody.Anonymous.fromCompiler(owner.file, range, signature).componentValue()
    }

    private fun selected(key: Boolean, component: Boolean): CompilerGroundedSymbolEvidence {
        val name = selectedName(key, component)
        val signature = selectedSignature(name, key, component)
        val file =
            if (component) "kotlin/collections/MapsKt.class" else "kotlin/collections/collections.kotlin_builtins"
        return CompilerGroundedSymbolEvidence.fromBoundary(
                SymbolDiscoveryFileIdentity.External(
                    DetachedVirtualFileUrl.parse("jar:///stdlib.jar!/$file").componentValue()
                ),
                1000,
                1100,
                name.substringAfterLast('.'),
                name,
                if (component) CompilerSymbolKind.FUNCTION else CompilerSymbolKind.PROPERTY,
                signature,
            )
            .componentValue()
    }

    private fun selectedName(key: Boolean, component: Boolean): String =
        if (component) "kotlin.collections.component" + componentNumber(key)
        else "kotlin.collections.Map.Entry." + propertyName(key)

    private fun componentNumber(key: Boolean): String = if (key) "1" else "2"

    private fun propertyName(key: Boolean): String = if (key) "key" else "value"

    private fun selectedSignature(name: String, key: Boolean, component: Boolean): CanonicalCompilerSignature =
        if (component)
            CanonicalCompilerSignature.function(
                    name,
                    "kotlin/collections/Map.Entry<K,V>",
                    emptyList(),
                    emptyList(),
                    2,
                )
                .componentValue()
        else CanonicalCompilerSignature.property(name, null, emptyList(), if (key) "K" else "V").componentValue()
}
