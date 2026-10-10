package io.github.amichne.kast.runtime.hosted.workspace

/** Visits every prefix with a fresh case oracle, including empty and all interleavings at the requested bound. */
internal fun <Event> forEachBoundedPrefix(alphabet: List<Event>, depth: Int, check: (List<Event>) -> Unit) {
    fun visit(prefix: List<Event>, remaining: Int) {
        check(prefix)
        if (remaining > 0) alphabet.forEach { visit(prefix + it, remaining - 1) }
    }
    visit(emptyList(), depth)
}
