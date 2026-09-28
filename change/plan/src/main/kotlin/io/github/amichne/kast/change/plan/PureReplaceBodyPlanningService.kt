package io.github.amichne.kast.change.plan

import io.github.amichne.kast.change.contract.AdmittedLiveReplaceBodyPlanInput
import io.github.amichne.kast.change.contract.LiveReplaceBodyChangePlan
import io.github.amichne.kast.change.contract.LiveReplaceBodyPlanRequest
import io.github.amichne.kast.change.contract.LiveReplaceBodyPlanResult
import io.github.amichne.kast.kernel.Refinement

/** Issues one detached body-only source image after live target admission. */
class PureReplaceBodyPlanningService {
    fun plan(request: LiveReplaceBodyPlanRequest): LiveReplaceBodyPlanResult =
        when (val admitted = AdmittedLiveReplaceBodyPlanInput.admit(request)) {
            is Refinement.Refined -> LiveReplaceBodyPlanResult.Planned(LiveReplaceBodyChangePlan.issue(admitted.value))
            is Refinement.Rejected -> LiveReplaceBodyPlanResult.Rejected(admitted.failure)
        }
}
