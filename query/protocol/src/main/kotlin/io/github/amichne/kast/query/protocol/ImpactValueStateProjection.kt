package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactCallFrameDocument
import io.github.amichne.kast.protocol.contract.ImpactConstructorStorageDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFormalParameterDocument
import io.github.amichne.kast.protocol.contract.ImpactFormalPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactReceiverKindDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueCallStack
import io.github.amichne.kast.relation.contract.ValueConstructorStorage
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueSourceOperation

internal fun ValueAccessPath.impactDocument(): ImpactProjected<BoundedProtocolList<ImpactValuePathSegmentDocument>> =
    segments.impactEach { segment ->
        when (segment) {
            ValuePathSegment.Element -> Refinement.Refined(ImpactValuePathSegmentDocument.Element)
            is ValuePathSegment.Field ->
                segment.field.declaration.impactDeclaration().impactMap(ImpactValuePathSegmentDocument::Field)
        }
    }

internal fun ValueCallStack.impactDocument(): ImpactProjected<BoundedProtocolList<ImpactCallFrameDocument>> =
    frames.impactEach {
        it.impactDocument()
    }

internal fun ValueCallFrame.impactDocument(): ImpactProjected<ImpactCallFrameDocument> =
    invocation.enclosing
        .impactDeclaration()
        .impactZip(invocation.impactDocument())
        .impactZip(callee.impactDocument())
        .impactZip(target.impactDocument())
        .impactMap { (call, target) ->
            ImpactCallFrameDocument(call.first.first, call.first.second, call.second, target)
        }

internal fun ValueExecutionOwner.impactDocument(): ImpactProjected<ImpactExecutionReferenceDocument> =
    enclosing.impactDeclaration().impactZip(identity.impactDocument()).impactMap { (enclosing, execution) ->
        ImpactExecutionReferenceDocument(enclosing, execution)
    }

internal fun ValueFormalParameter.impactDocument(): ImpactProjected<ImpactFormalParameterDocument> =
    callable
        .impactDeclaration()
        .impactZip(position.value.impactOffset())
        .impactZip(name.value.impactText())
        .impactZip(range.impactDocument())
        .impactZip(executionOwner.identity.impactDocument())
        .impactMap { (parameter, execution) ->
            val (declaration, range) = parameter
            ImpactFormalParameterDocument(
                declaration.first.first,
                declaration.first.second,
                declaration.second,
                range,
                execution,
            )
        }

internal fun ValueFormalPosition.impactDocument(): ImpactProjected<ImpactFormalPositionDocument> =
    when (this) {
        is ValueFormalPosition.Parameter ->
            parameter.impactDocument().impactMap(ImpactFormalPositionDocument::Parameter)
        is ValueFormalPosition.Receiver ->
            Refinement.Refined(ImpactFormalPositionDocument.Receiver(kind.impactDocument()))
    }

internal fun ValueReceiverKind.impactDocument(): ImpactReceiverKindDocument =
    when (this) {
        ValueReceiverKind.DISPATCH -> ImpactReceiverKindDocument.DISPATCH
        ValueReceiverKind.EXTENSION -> ImpactReceiverKindDocument.EXTENSION
    }

internal fun ValueSourceOperation.impactDocument(): ImpactProjected<ImpactSourceOperationDocument> =
    when (this) {
        is ValueSourceOperation.Enter ->
            frame.impactDocument().impactZip(position.impactDocument()).impactMap { (frame, position) ->
                ImpactSourceOperationDocument.Enter(frame, position)
            }
        is ValueSourceOperation.Return -> frame.impactDocument().impactMap(ImpactSourceOperationDocument::Return)
        is ValueSourceOperation.SeedReturn ->
            frame.impactDocument().impactMap(ImpactSourceOperationDocument::SeedReturn)
        is ValueSourceOperation.CallbackReturn ->
            invocation.impactCapture().impactZip(frame.impactDocument()).impactMap { (invocation, frame) ->
                ImpactSourceOperationDocument.CallbackReturn(invocation, frame)
            }
        is ValueSourceOperation.CallbackArgumentEntry ->
            invocation
                .impactCapture()
                .impactZip(supplierFrame.impactDocument())
                .impactZip(parameter.impactDocument())
                .impactZip(argumentRange.impactDocument())
                .impactMap { (entry, argument) ->
                    ImpactSourceOperationDocument.CallbackArgumentEntry(
                        entry.first.first,
                        entry.first.second,
                        entry.second,
                        argument,
                    )
                }
        is ValueSourceOperation.ReadField ->
            field.declaration.impactDeclaration().impactMap(ImpactSourceOperationDocument::ReadField)
        is ValueSourceOperation.ConstructField ->
            call.impactDocument().impactZip(storage.impactDocument()).impactMap { (call, storage) ->
                ImpactSourceOperationDocument.ConstructField(call, storage)
            }
    }

private fun ValueConstructorStorage.impactDocument(): ImpactProjected<ImpactConstructorStorageDocument> =
    constructor
        .impactDeclaration()
        .impactZip(parameter.impactDocument())
        .impactZip(field.declaration.impactDeclaration())
        .impactMap { (binding, field) -> ImpactConstructorStorageDocument(binding.first, binding.second, field) }
