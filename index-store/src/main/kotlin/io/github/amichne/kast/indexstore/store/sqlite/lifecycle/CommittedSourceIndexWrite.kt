package io.github.amichne.kast.indexstore.store

internal sealed interface CommittedSourceIndexWrite<out Value> {
    data class Committed<Value>(val value: Value) : CommittedSourceIndexWrite<Value>

    data object WorkspaceWriteActive : CommittedSourceIndexWrite<Nothing>
}
