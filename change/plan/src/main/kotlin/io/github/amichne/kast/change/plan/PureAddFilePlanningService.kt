package io.github.amichne.kast.change.plan

import io.github.amichne.kast.change.contract.AddFileChangePlan
import io.github.amichne.kast.change.contract.AddFilePlanOperations
import io.github.amichne.kast.change.contract.AddFilePlanRequest
import io.github.amichne.kast.change.contract.AddFilePlanResult

/** Pure AddFile planner; it owns no platform, source-write, or persistence capability. */
class PureAddFilePlanningService : AddFilePlanOperations {
    override fun plan(request: AddFilePlanRequest): AddFilePlanResult =
        AddFilePlanResult.Planned(AddFileChangePlan.issue(request))
}
