package io.github.amichne.kast.change.journal.sqlite

internal class ThrowBeforeRollback(
    private val operation: SqliteJournalCommitOperation,
) : SqliteJournalConnectionObserver {
    override fun opened() = Unit

    override fun closed() = Unit

    override fun rollingBack(operation: SqliteJournalCommitOperation) {
        if (operation == this.operation) error("simulated rollback failure")
    }
}
