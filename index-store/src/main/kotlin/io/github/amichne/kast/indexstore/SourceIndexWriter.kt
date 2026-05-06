package io.github.amichne.kast.indexstore

/**
 * Write-through boundary used by hot in-memory indexes without depending on a concrete store.
 */
interface SourceIndexWriter {
    fun saveFileIndex(update: FileIndexUpdate)

    fun removeFile(path: String)
}
