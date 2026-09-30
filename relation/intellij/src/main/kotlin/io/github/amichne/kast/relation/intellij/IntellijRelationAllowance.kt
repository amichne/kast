package io.github.amichne.kast.relation.intellij

/** One invocation's accounting survives platform read-action re-entry; its facts remain attempt-local. */
internal class IntellijRelationAllowance(clockNanoseconds: () -> Long) {
    val startedAt: Long = clockNanoseconds()
    var examined: Long = 0L
        private set

    var nativeCandidates: Int = 0
        private set

    fun examine() {
        examined += 1L
    }

    fun collectCandidate() {
        nativeCandidates += 1
    }
}
