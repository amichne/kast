package io.github.amichne.kast.testing

import io.github.amichne.kast.api.continuation.*
import io.github.amichne.kast.api.contract.query.WorkspaceFileKindDomain
import io.github.amichne.kast.api.contract.result.WorkspaceFilesResult
import io.github.amichne.kast.api.contract.result.WorkspaceModule
import io.github.amichne.kast.api.protocol.InvalidWorkspaceFileCursorException
import io.github.amichne.kast.api.protocol.InvalidWorkspaceFileCursorScope
import io.github.amichne.kast.api.protocol.WorkspaceInventoryStaleException
import io.github.amichne.kast.api.validation.*

internal const val FAKE_MODULE_NAME: String = "fake-module"

private fun WorkspaceFileKindDomain.admits(filePath: String): Boolean = when (this) {
    WorkspaceFileKindDomain.SOURCE_ONLY -> filePath.endsWith(".kt")
    WorkspaceFileKindDomain.SCRIPT_ONLY -> filePath.endsWith(".kts")
    WorkspaceFileKindDomain.MIXED -> filePath.endsWith(".kt") || filePath.endsWith(".kts")
}

internal suspend fun FakeAnalysisBackend.workspaceFilesResult(query: ParsedWorkspaceFilesQuery): WorkspaceFilesResult {
    val suppliedSnapshotToken = query.snapshotToken
    val snapshot = if (suppliedSnapshotToken == null) {
        val inventory = workspaceInventory(query.kindDomain)
        val token = issueWorkspaceSnapshot(query.kindDomain, inventory)
        FakeWorkspaceSnapshot(token, inventory)
    } else {
        FakeWorkspaceSnapshot(
            token = suppliedSnapshotToken,
            inventory = leaseWorkspaceSnapshot(suppliedSnapshotToken, query.kindDomain),
        )
    }

    val requestedModule = query.moduleName?.value
    if (!query.includeFiles) {
        return workspaceFilesResult(
            snapshot = snapshot,
            modules = workspaceMetadataModules(snapshot.inventory, requestedModule),
        )
    }
    if (requestedModule != null && requestedModule != FAKE_MODULE_NAME && query.pageToken == null) {
        return workspaceFilesResult(snapshot, emptyList())
    }

    val pageSize = query.maxFilesPerModule?.value ?: snapshot.inventory.files.size.coerceAtLeast(1)
    val identity = FakeWorkspacePageIdentity(
        snapshotToken = snapshot.token,
        kindDomain = query.kindDomain,
        moduleName = requestedModule,
        pageSize = pageSize,
    )
    val pageToken = query.pageToken
    val page = if (pageToken == null) {
        firstWorkspacePage(snapshot.inventory, identity)
    } else {
        consumeWorkspacePage(pageToken, identity, snapshot.inventory)
    }
    return workspaceFilesResult(
        snapshot = snapshot,
        modules = listOf(workspaceModule(snapshot.inventory, page)),
    )
}

private fun FakeAnalysisBackend.workspaceInventory(kindDomain: WorkspaceFileKindDomain): FakeWorkspaceInventory =
    FakeWorkspaceInventory(
        files = availableFiles
            .asSequence()
            .filter { filePath -> kindDomain.admits(filePath) }
            .sorted()
            .toList(),
    )

private fun FakeAnalysisBackend.issueWorkspaceSnapshot(
    kindDomain: WorkspaceFileKindDomain,
    inventory: FakeWorkspaceInventory,
): WorkspaceFileSnapshotToken = when (val issued = workspaceSnapshots.issue(
    query = FakeWorkspaceSnapshotIdentity(kindDomain),
    state = FakeWorkspaceSnapshotState(inventory),
)) {
    is ContinuationIssueResult.Issued -> issued.token
    is ContinuationIssueResult.Rejected ->
        throw InvalidWorkspaceFileCursorException(InvalidWorkspaceFileCursorScope.SNAPSHOT_HANDLE)
}

private fun FakeAnalysisBackend.leaseWorkspaceSnapshot(
    token: WorkspaceFileSnapshotToken,
    kindDomain: WorkspaceFileKindDomain,
): FakeWorkspaceInventory = when (val leased = workspaceSnapshots.lease(
    token = token,
    query = FakeWorkspaceSnapshotIdentity(kindDomain),
    projection = ContinuationStateProjection { state ->
        val current = workspaceInventory(kindDomain)
        if (current != state.inventory) throw WorkspaceInventoryStaleException()
        state.inventory
    },
)) {
    is ContinuationLeaseResult.Granted -> leased.output
    is ContinuationLeaseResult.Rejected ->
        throw InvalidWorkspaceFileCursorException(InvalidWorkspaceFileCursorScope.SNAPSHOT_HANDLE)
}

private fun FakeAnalysisBackend.firstWorkspacePage(
    inventory: FakeWorkspaceInventory,
    identity: FakeWorkspacePageIdentity,
): FakeWorkspacePage {
    val page = FakeWorkspacePage.from(inventory, offset = 0, pageSize = identity.pageSize)
    val nextPageToken = if (page.hasMore) {
        issueWorkspacePage(
            identity = identity,
            state = FakeWorkspacePageState(inventory, page.nextOffset),
        ).value
    } else {
        null
    }
    return page.copy(nextPageToken = nextPageToken)
}

private fun FakeAnalysisBackend.issueWorkspacePage(
    identity: FakeWorkspacePageIdentity,
    state: FakeWorkspacePageState,
): WorkspaceFilePageToken = when (val issued = workspacePages.issue(identity, state)) {
    is ContinuationIssueResult.Issued -> issued.token
    is ContinuationIssueResult.Rejected ->
        throw InvalidWorkspaceFileCursorException(InvalidWorkspaceFileCursorScope.PAGE_HANDLE)
}

private fun FakeAnalysisBackend.consumeWorkspacePage(
    token: WorkspaceFilePageToken,
    identity: FakeWorkspacePageIdentity,
    inventory: FakeWorkspaceInventory,
): FakeWorkspacePage = when (val consumed = workspacePages.consume(
    token = token,
    query = identity,
    transition = ContinuationStateTransition { state ->
        if (state.inventory != inventory) throw WorkspaceInventoryStaleException()
        val page = FakeWorkspacePage.from(state.inventory, state.nextOffset, identity.pageSize)
        if (page.hasMore) {
            state.nextOffset = page.nextOffset
            ContinuationTransition.Reissue(page, identity)
        } else {
            ContinuationTransition.Complete(page)
        }
    },
)) {
    is ContinuationConsumeResult.Completed -> consumed.output
    is ContinuationConsumeResult.Reissued -> consumed.output.copy(nextPageToken = consumed.token.value)
    is ContinuationConsumeResult.Rejected ->
        throw InvalidWorkspaceFileCursorException(InvalidWorkspaceFileCursorScope.PAGE_HANDLE)
}

private fun FakeAnalysisBackend.workspaceMetadataModules(
    inventory: FakeWorkspaceInventory,
    requestedModule: String?,
): List<WorkspaceModule> = if (requestedModule == null || requestedModule == FAKE_MODULE_NAME) {
    listOf(workspaceModule(inventory, FakeWorkspacePage.empty()))
} else {
    emptyList()
}

private fun FakeAnalysisBackend.workspaceModule(
    inventory: FakeWorkspaceInventory,
    page: FakeWorkspacePage,
): WorkspaceModule = WorkspaceModule(
    name = FAKE_MODULE_NAME,
    sourceRoots = listOf(workspaceRoot.resolve("src").toString()),
    contentRoots = listOf(workspaceRoot.toString()),
    dependencyModuleNames = emptyList(),
    files = page.files,
    nextPageToken = page.nextPageToken,
    filesTruncated = page.hasMore,
    fileCount = inventory.files.size,
)

private fun FakeAnalysisBackend.workspaceFilesResult(
    snapshot: FakeWorkspaceSnapshot,
    modules: List<WorkspaceModule>,
): WorkspaceFilesResult = WorkspaceFilesResult(
    modules = modules,
    snapshotToken = snapshot.token.value,
)

internal data class FakeWorkspaceSnapshotIdentity(
    val kindDomain: WorkspaceFileKindDomain,
)

internal data class FakeWorkspaceSnapshotState(
    val inventory: FakeWorkspaceInventory,
) : ContinuationOwnedState()

private data class FakeWorkspaceSnapshot(
    val token: WorkspaceFileSnapshotToken,
    val inventory: FakeWorkspaceInventory,
)

internal data class FakeWorkspaceInventory(
    val files: List<String>,
) : ContinuationProjection() {
    init {
        require(files == files.distinct().sorted()) {
            "Fake workspace inventory must be sorted and deduplicated"
        }
    }
}

internal data class FakeWorkspacePageIdentity(
    val snapshotToken: WorkspaceFileSnapshotToken,
    val kindDomain: WorkspaceFileKindDomain,
    val moduleName: String?,
    val pageSize: Int,
)

internal data class FakeWorkspacePageState(
    val inventory: FakeWorkspaceInventory,
    var nextOffset: Int,
) : ContinuationOwnedState()

internal data class FakeWorkspacePage(
    val files: List<String>,
    val nextOffset: Int,
    val hasMore: Boolean,
    val nextPageToken: String? = null,
) : ContinuationProjection() {
    companion object {
        fun empty(): FakeWorkspacePage = FakeWorkspacePage(
            files = emptyList(),
            nextOffset = 0,
            hasMore = false,
        )

        fun from(
            inventory: FakeWorkspaceInventory,
            offset: Int,
            pageSize: Int,
        ): FakeWorkspacePage {
            val files = inventory.files.drop(offset).take(pageSize)
            val nextOffset = Math.addExact(offset, files.size)
            return FakeWorkspacePage(
                files = files,
                nextOffset = nextOffset,
                hasMore = nextOffset < inventory.files.size,
            )
        }
    }
}
