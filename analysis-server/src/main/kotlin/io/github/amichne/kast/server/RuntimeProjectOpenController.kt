package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.RuntimeOpenProjectRequest
import io.github.amichne.kast.api.contract.RuntimeOpenProjectResponse
import io.github.amichne.kast.api.protocol.CapabilityNotSupportedException

fun interface RuntimeProjectOpenController {
    fun openProject(request: RuntimeOpenProjectRequest): RuntimeProjectOpenPlan

    object Unavailable : RuntimeProjectOpenController {
        override fun openProject(request: RuntimeOpenProjectRequest): RuntimeProjectOpenPlan =
            throw CapabilityNotSupportedException(
                capability = "RUNTIME_OPEN_PROJECT",
                message = "Opening another project is not available for this backend host",
            )
    }
}

data class RuntimeProjectOpenPlan(
    val response: RuntimeOpenProjectResponse,
    val afterResponseAction: () -> Unit,
)
