package io.github.amichne.kast.workspace.intellij.read.hosted

/** Detached observation boundary inside the request budget, after K2 read access has ended. */
internal fun interface HostedReadCheckpoint {
    /** Bounded observer inside native K2 analysis; the default performs no effect. */
    fun duringSemanticRead() = Unit

    suspend fun afterSemanticRead()

    companion object {
        val Unobserved = HostedReadCheckpoint {}
    }
}
