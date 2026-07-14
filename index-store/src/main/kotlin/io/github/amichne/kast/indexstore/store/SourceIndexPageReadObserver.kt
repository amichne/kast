package io.github.amichne.kast.indexstore.store

internal fun interface SourceIndexPageReadObserver {
    fun generationRead()

    companion object {
        val Disabled: SourceIndexPageReadObserver = SourceIndexPageReadObserver {}
    }
}
