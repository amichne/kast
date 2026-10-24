package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument

internal fun PublicToolExecutionBudget.lower(): ExecutionBudgetDocument =
    ExecutionBudgetDocument(
        maxElapsedMillis = maxElapsedMs?.let { proven(ElapsedTimeLimitMillis.parse(it)) },
        maxWorkUnits = maxWorkUnits?.let { proven(WorkUnitLimit.parse(it)) },
        maxResults = maxResults?.let { proven(ResultLimit.parse(it)) },
        maxReturnedBytes = maxReturnedBytes?.let { proven(ReturnedByteLimit.parse(it)) },
    )
