package io.github.amichne.kast.workspace.intellij.read.hosted

/** Detached observation boundary inside the request budget, after K2 read access has ended. */
internal fun interface HostedReadCheckpoint {
    suspend fun afterSemanticRead()

    companion object {
        val Unobserved = HostedReadCheckpoint { }
    }
}
