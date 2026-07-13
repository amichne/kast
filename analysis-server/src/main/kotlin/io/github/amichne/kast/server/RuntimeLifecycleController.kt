package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.RuntimeLifecycleAction

fun interface RuntimeLifecycleController {
    fun afterResponseAction(action: RuntimeLifecycleAction): (() -> Unit)?

    object Unavailable : RuntimeLifecycleController {
        override fun afterResponseAction(action: RuntimeLifecycleAction): (() -> Unit)? = null
    }
}
