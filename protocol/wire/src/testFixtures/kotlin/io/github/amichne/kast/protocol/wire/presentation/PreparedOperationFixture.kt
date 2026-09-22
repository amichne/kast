package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.CanonicalOperation

/** A sentinel completion detects accidental projection of a pre-authority rejection. */
fun preparedOperationFixture(
    operation: CanonicalOperation,
    effect: HostedRequestEffect,
    document: String,
    completion: (String) -> OperationCompletion,
): PreparedOperationRequest = PreparedOperationRequest(operation, effect, document, completion)
