package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.WorkspaceId
import io.github.amichne.kast.api.continuation.ContinuationClock
import io.github.amichne.kast.api.continuation.ContinuationConsumeResult
import io.github.amichne.kast.api.continuation.ContinuationIssueResult
import io.github.amichne.kast.api.continuation.ContinuationLeaseResult
import io.github.amichne.kast.api.continuation.ContinuationStateDisposer
import io.github.amichne.kast.api.continuation.ContinuationStateProjection
import io.github.amichne.kast.api.continuation.ContinuationStateTransition
import io.github.amichne.kast.api.continuation.ContinuationTokenIssuer
import io.github.amichne.kast.api.continuation.ContinuationTransition
import io.github.amichne.kast.api.continuation.ServerHeldContinuationStore
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.result.WorkspaceFilesResult
import io.github.amichne.kast.api.contract.result.WorkspaceModule
import io.github.amichne.kast.api.protocol.InvalidWorkspaceFileCursorException
import io.github.amichne.kast.api.protocol.InvalidWorkspaceFileCursorScope
import io.github.amichne.kast.api.protocol.WorkspaceInventoryStaleException
import io.github.amichne.kast.api.validation.ParsedWorkspaceFilesQuery
import io.github.amichne.kast.api.validation.WorkspaceFilePageToken
import io.github.amichne.kast.api.validation.WorkspaceFileSnapshotToken

internal class IdeaWorkspaceFilePaging(
    private val workspaceId: WorkspaceId,
    private val inventory: IdeaWorkspaceFileInventory,
    limits: ServerLimits,
    clock: ContinuationClock = ContinuationClock.System,
    snapshotTokenIssuer: ContinuationTokenIssuer<WorkspaceFileSnapshotToken> =
        ContinuationTokenIssuer(WorkspaceFileSnapshotToken::random),
    pageTokenIssuer: ContinuationTokenIssuer<WorkspaceFilePageToken> =
        ContinuationTokenIssuer(WorkspaceFilePageToken::random),
) : AutoCloseable {
    private val defaultPageSize = PositiveInt(limits.maxResults)
    private val snapshots = ServerHeldContinuationStore<
        WorkspaceFileSnapshotToken,
        IdeaWorkspaceFileSnapshotIdentity,
        IdeaWorkspaceFileSnapshotState,
        IdeaWorkspaceFileSnapshotProjection,
    >(
        capacity = limits.typedContinuationCapacity,
        timeToLive = limits.typedContinuationTtl,
        tokenIssuer = snapshotTokenIssuer,
        stateDisposer = ContinuationStateDisposer { },
        clock = clock,
    )
    private val pages = ServerHeldContinuationStore<
        WorkspaceFilePageToken,
        IdeaWorkspaceFilePageIdentity,
        IdeaWorkspaceFilePageState,
        IdeaWorkspaceFilePage,
    >(
        capacity = limits.typedContinuationCapacity,
        timeToLive = limits.typedContinuationTtl,
        tokenIssuer = pageTokenIssuer,
        stateDisposer = ContinuationStateDisposer { },
        clock = clock,
    )

    fun query(query: ParsedWorkspaceFilesQuery): WorkspaceFilesResult {
        val suppliedSnapshotToken = query.snapshotToken
        val snapshot = if (suppliedSnapshotToken == null) {
            val current = inventory.snapshot(query.kindDomain)
            IdeaWorkspaceFileSnapshot(
                token = issueSnapshot(current),
                inventory = current,
            )
        } else {
            IdeaWorkspaceFileSnapshot(
                token = suppliedSnapshotToken,
                inventory = leaseSnapshot(suppliedSnapshotToken, query),
            )
        }
        val requestedModule = query.moduleName?.value
        if (!query.includeFiles || requestedModule == null) {
            return result(snapshot, metadataModules(snapshot.inventory, requestedModule))
        }
        val moduleIdentity = IdeaWorkspaceModuleIdentity.of(requestedModule)
        val pageSize = query.maxFilesPerModule ?: defaultPageSize
        val identity = IdeaWorkspaceFilePageIdentity(
            workspaceId = workspaceId,
            snapshotToken = snapshot.token,
            kindDomain = query.kindDomain,
            moduleIdentity = moduleIdentity,
            pageSize = pageSize,
        )
        val module = snapshot.inventory.modules.singleOrNull { candidate -> candidate.identity == moduleIdentity }
        if (module == null) {
            query.pageToken?.let { token -> consumePageForMissingModule(token, identity) }
            return result(snapshot, emptyList())
        }
        val moduleFiles = module.filePaths(query.kindDomain)
        val page = query.pageToken?.let { token ->
            consumePage(token, identity, snapshot.inventory, module, moduleFiles)
        } ?: firstPage(identity, snapshot.inventory, module, moduleFiles)
        return result(snapshot, listOf(workspaceModule(module, moduleFiles.size, page)))
    }

    private fun consumePageForMissingModule(
        token: WorkspaceFilePageToken,
        identity: IdeaWorkspaceFilePageIdentity,
    ): Nothing {
        pages.consume(
            token = token,
            query = identity,
            transition = ContinuationStateTransition {
                throw InvalidWorkspaceFileCursorException(InvalidWorkspaceFileCursorScope.PAGE_HANDLE)
            },
        )
        throw InvalidWorkspaceFileCursorException(InvalidWorkspaceFileCursorScope.PAGE_HANDLE)
    }

    private fun issueSnapshot(
        snapshot: IdeaWorkspaceFileInventorySnapshot,
    ): WorkspaceFileSnapshotToken = when (val result = snapshots.issue(
        query = IdeaWorkspaceFileSnapshotIdentity(workspaceId, snapshot.kindDomain),
        state = IdeaWorkspaceFileSnapshotState(snapshot),
    )) {
        is ContinuationIssueResult.Issued -> result.token
        is ContinuationIssueResult.Rejected ->
            throw InvalidWorkspaceFileCursorException(InvalidWorkspaceFileCursorScope.SNAPSHOT_HANDLE)
    }

    private fun leaseSnapshot(
        token: WorkspaceFileSnapshotToken,
        query: ParsedWorkspaceFilesQuery,
    ): IdeaWorkspaceFileInventorySnapshot {
        val current = inventory.snapshot(query.kindDomain)
        return when (val result = snapshots.lease(
            token = token,
            query = IdeaWorkspaceFileSnapshotIdentity(workspaceId, query.kindDomain),
            projection = ContinuationStateProjection { state ->
                if (state.inventory.generation != current.generation) {
                    throw WorkspaceInventoryStaleException()
                }
                IdeaWorkspaceFileSnapshotProjection(state.inventory)
            },
        )) {
            is ContinuationLeaseResult.Granted -> result.output.inventory
            is ContinuationLeaseResult.Rejected ->
                throw InvalidWorkspaceFileCursorException(InvalidWorkspaceFileCursorScope.SNAPSHOT_HANDLE)
        }
    }

    private fun firstPage(
        identity: IdeaWorkspaceFilePageIdentity,
        snapshot: IdeaWorkspaceFileInventorySnapshot,
        module: IdeaWorkspaceModuleSnapshot,
        moduleFiles: List<String>,
    ): IdeaWorkspaceFilePage {
        val page = IdeaWorkspaceFilePage.from(module, moduleFiles, offset = 0, pageSize = identity.pageSize)
        val nextToken = if (page.hasMore) {
            issuePage(
                identity = identity,
                state = IdeaWorkspaceFilePageState(
                    generation = snapshot.generation,
                    moduleIdentity = module.identity,
                    nextOffset = page.nextOffset,
                ),
            ).value
        } else {
            null
        }
        return page.copy(nextPageToken = nextToken)
    }

    private fun issuePage(
        identity: IdeaWorkspaceFilePageIdentity,
        state: IdeaWorkspaceFilePageState,
    ): WorkspaceFilePageToken = when (val result = pages.issue(identity, state)) {
        is ContinuationIssueResult.Issued -> result.token
        is ContinuationIssueResult.Rejected ->
            throw InvalidWorkspaceFileCursorException(InvalidWorkspaceFileCursorScope.PAGE_HANDLE)
    }

    private fun consumePage(
        token: WorkspaceFilePageToken,
        identity: IdeaWorkspaceFilePageIdentity,
        snapshot: IdeaWorkspaceFileInventorySnapshot,
        module: IdeaWorkspaceModuleSnapshot,
        moduleFiles: List<String>,
    ): IdeaWorkspaceFilePage = when (val result = pages.consume(
        token = token,
        query = identity,
        transition = ContinuationStateTransition { state ->
            if (state.generation != snapshot.generation) throw WorkspaceInventoryStaleException()
            if (state.moduleIdentity != module.identity) {
                throw InvalidWorkspaceFileCursorException(InvalidWorkspaceFileCursorScope.PAGE_HANDLE)
            }
            val page = IdeaWorkspaceFilePage.from(
                module = module,
                files = moduleFiles,
                offset = state.nextOffset,
                pageSize = identity.pageSize,
            )
            if (page.hasMore) {
                state.advanceTo(page.nextOffset)
                ContinuationTransition.Reissue(page, identity)
            } else {
                ContinuationTransition.Complete(page)
            }
        },
    )) {
        is ContinuationConsumeResult.Completed -> result.output
        is ContinuationConsumeResult.Reissued -> result.output.copy(nextPageToken = result.token.value)
        is ContinuationConsumeResult.Rejected ->
            throw InvalidWorkspaceFileCursorException(InvalidWorkspaceFileCursorScope.PAGE_HANDLE)
    }

    private fun metadataModules(
        snapshot: IdeaWorkspaceFileInventorySnapshot,
        requestedModule: String?,
    ): List<WorkspaceModule> = snapshot.modules
        .filter { module -> requestedModule == null || module.identity.value == requestedModule }
        .map { module ->
            workspaceModule(
                module = module,
                fileCount = module.filePaths(snapshot.kindDomain).size,
                page = IdeaWorkspaceFilePage(emptyList(), 0, false),
            )
        }

    private fun workspaceModule(
        module: IdeaWorkspaceModuleSnapshot,
        fileCount: Int,
        page: IdeaWorkspaceFilePage,
    ): WorkspaceModule = WorkspaceModule(
        name = module.identity.value,
        sourceRoots = module.sourceRoots,
        contentRoots = module.contentRoots,
        dependencyModuleNames = module.dependencyModuleNames,
        files = page.files,
        returnedFileCount = page.files.size,
        nextPageToken = page.nextPageToken,
        filesTruncated = page.hasMore,
        fileCount = fileCount,
    )

    private fun result(
        snapshot: IdeaWorkspaceFileSnapshot,
        modules: List<WorkspaceModule>,
    ): WorkspaceFilesResult = WorkspaceFilesResult(
        modules = modules,
        snapshotToken = snapshot.token.value,
    )

    override fun close() {
        val failures = listOf(snapshots, pages)
            .mapNotNull { store -> runCatching(store::close).exceptionOrNull() }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }
}
