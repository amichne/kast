package io.github.amichne.kast.server.mutation

internal fun interface MutationProgressReporter {
    fun report(event: MutationProgressEvent)

    suspend fun awaitPathAdmission(paths: Collection<String>) = Unit

    companion object {
        val NONE: MutationProgressReporter = MutationProgressReporter { }
    }
}
