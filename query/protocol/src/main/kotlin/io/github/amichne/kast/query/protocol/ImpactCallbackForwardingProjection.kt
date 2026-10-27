package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactCallbackForwardingDocument
import io.github.amichne.kast.relation.contract.CallbackParameterForwarding

internal fun CallbackParameterForwarding.impactForwarding(): ImpactProjected<ImpactCallbackForwardingDocument> =
    source.parameter.range
        .impactDocument()
        .impactZip(source.position.value.impactOffset())
        .impactZip(argument.range.impactDocument())
        .impactZip(target.frame.impactDocument())
        .impactZip(target.parameter.range.impactDocument())
        .impactZip(target.position.value.impactOffset())
        .impactZip(callableTransfers.impactEach { it.impactDocument() })
        .impactMap { (route, transfers) ->
            val (parameter, position) = route
            val (target, parameterRange) = parameter
            val (argument, frame) = target
            val (source, argumentRange) = argument
            ImpactCallbackForwardingDocument(
                sourceParameter = source.first,
                sourcePosition = source.second,
                argument = argumentRange,
                targetFrame = frame,
                parameter = parameterRange,
                position = position,
                callableTransfers = transfers,
            )
        }
