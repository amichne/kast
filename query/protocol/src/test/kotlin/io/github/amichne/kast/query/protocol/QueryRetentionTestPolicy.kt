package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit

/** Finite presentation and storage grants for tests that assert retained output ownership. */
internal fun retainedQueryTestPolicy(budget: QueryBudget): QueryInvocationPolicy =
    QueryInvocationPolicy(
        previewRows = budget.resources.resultLimit,
        previewBytesLimit = budget.returnedBytes,
        retainedBytes = (QueryByteLimit.parse(10_000_000) as Refinement.Refined).value,
        previewBytes = CanonicalQueryCliDocuments::previewBytes,
        nanoTime = { 0L },
    )
