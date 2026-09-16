package io.github.amichne.kast.runtime.hosted.lifecycle

/** A close never waits while retaining this monitor; a failed close restores admission. */
internal class HostedProjectAdmission {
    private var active = 0
    private var closing = false

    @Synchronized
    fun enter(): Boolean {
        if (closing) return false
        active++
        return true
    }

    @Synchronized
    fun leave() {
        check(active > 0)
        active--
    }

    @Synchronized
    fun beginClose(): Boolean {
        if (closing || active != 0) return false
        closing = true
        return true
    }

    @Synchronized
    fun restore() {
        closing = false
    }
}
