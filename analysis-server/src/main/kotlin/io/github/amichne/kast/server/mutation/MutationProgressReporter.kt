package io.github.amichne.kast.server.mutation

internal fun interface MutationProgressReporter {
    fun report(event: MutationProgressEvent)

    companion object {
        val NONE: MutationProgressReporter = MutationProgressReporter { }
    }
}
