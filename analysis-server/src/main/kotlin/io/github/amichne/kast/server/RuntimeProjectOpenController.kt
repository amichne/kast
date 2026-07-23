package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.RuntimeOpenProjectRequest
import io.github.amichne.kast.api.contract.RuntimeOpenProjectResponse
import io.github.amichne.kast.api.protocol.CapabilityNotSupportedException

fun interface RuntimeProjectOpenController {
    fun openProject(request: RuntimeOpenProjectRequest): RuntimeOpenProjectResponse

    object Unavailable : RuntimeProjectOpenController {
        override fun openProject(request: RuntimeOpenProjectRequest): RuntimeOpenProjectResponse =
            throw CapabilityNotSupportedException(
                capability = "RUNTIME_OPEN_PROJECT",
                message = "Opening another project is not available for this backend host",
            )
    }
}
