package io.github.amichne.kast.change.journal.sqlite

internal object SqliteAddDeclarationVerificationJournalTestSupport {
    const val ROOT = "/workspace/kast"
    const val TARGET = "$ROOT/indexer/src/main/kotlin/sample/Target.kt"

    class ThrowAfterCommit(
        private val operation: SqliteJournalCommitOperation,
    ) : SqliteJournalConnectionObserver {
        override fun opened() = Unit

        override fun closed() = Unit

        override fun committed(operation: SqliteJournalCommitOperation) {
            if (operation == this.operation) error("simulated lost commit acknowledgement")
        }
    }

    class ThrowBeforeRollback(
        private val operation: SqliteJournalCommitOperation,
    ) : SqliteJournalConnectionObserver {
        override fun opened() = Unit

        override fun closed() = Unit

        override fun rollingBack(operation: SqliteJournalCommitOperation) {
            if (operation == this.operation) error("simulated rollback failure")
        }
    }
}
