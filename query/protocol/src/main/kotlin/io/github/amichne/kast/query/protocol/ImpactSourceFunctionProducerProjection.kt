package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.query.contract.QueryImpactProducer
import io.github.amichne.kast.relation.contract.ValueProducerOrigin

internal fun QueryImpactProducer.sourceFunctionDocument(
    origin: ValueProducerOrigin.SourceFunction,
    references: QueryReferenceAuthority,
): ImpactProjected<ImpactWitnessDocument.ProducerSourceFunction> =
    site
        .impactDocument(references)
        .impactZip(origin.invocation.impactDocument())
        .impactZip(origin.invocation.enclosing.impactModelEndpoint())
        .impactZip(origin.invocation.executionOwner.impactModelExecution(references))
        .impactZip(origin.property.endpoint.impactModelEndpoint())
        .impactZip(origin.reference.impactImplementationOccurrence())
        .impactMap { (proof, reference) ->
            ImpactWitnessDocument.ProducerSourceFunction(
                site = proof.first.first.first.first,
                invocation = proof.first.first.first.second,
                enclosing = proof.first.first.second,
                execution = proof.first.second,
                property = proof.second,
                reference = reference,
            )
        }
