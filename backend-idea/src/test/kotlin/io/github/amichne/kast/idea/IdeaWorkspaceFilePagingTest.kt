package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.WorkspaceId
import io.github.amichne.kast.api.continuation.ContinuationClock
import io.github.amichne.kast.api.continuation.ContinuationTokenIssuer
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.query.WorkspaceFileKindDomain
import io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery
import io.github.amichne.kast.api.protocol.InvalidWorkspaceFileCursorException
import io.github.amichne.kast.api.protocol.InvalidWorkspaceFileCursorScope
import io.github.amichne.kast.api.protocol.WorkspaceInventoryStaleException
import io.github.amichne.kast.api.validation.WorkspaceFilePageToken
import io.github.amichne.kast.api.validation.WorkspaceFileSnapshotToken
import io.github.amichne.kast.api.validation.parsed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class IdeaWorkspaceFilePagingTest {
    @Test
    fun `metadata and exact-module pages share one generation-bound snapshot`() {
        val inventory = MutableInventory(snapshot("Alpha.kt", "Beta.kt", "Gamma.kt"))
        val paging = paging(inventory)
        try {
            val metadata = paging.query(metadataQuery().parsed())
            val first = paging.query(pageQuery(metadata.snapshotToken).parsed())
            val second = paging.query(
                pageQuery(metadata.snapshotToken, first.modules.single().nextPageToken).parsed(),
            )

            assertEquals(metadata.snapshotToken, first.snapshotToken)
            assertEquals(metadata.snapshotToken, second.snapshotToken)
            assertEquals(listOf("/workspace/Alpha.kt", "/workspace/Beta.kt"), first.modules.single().files)
            assertNotNull(first.modules.single().nextPageToken)
            assertEquals(listOf("/workspace/Gamma.kt"), second.modules.single().files)
            assertNull(second.modules.single().nextPageToken)
            assertEquals(3, second.modules.single().fileCount)
        } finally {
            paging.close()
        }
    }

    @Test
    fun `cross-module mismatch consumes the page handle before it can be replayed`() {
        val paging = paging(MutableInventory(snapshot("Alpha.kt", "Beta.kt", "Gamma.kt")))
        try {
            val metadata = paging.query(metadataQuery().parsed())
            val first = paging.query(pageQuery(metadata.snapshotToken).parsed())
            val token = requireNotNull(first.modules.single().nextPageToken)

            val crossModule = assertThrows(InvalidWorkspaceFileCursorException::class.java) {
                paging.query(pageQuery(metadata.snapshotToken, token, moduleName = "other").parsed())
            }
            val consumed = assertThrows(InvalidWorkspaceFileCursorException::class.java) {
                paging.query(pageQuery(metadata.snapshotToken, token).parsed())
            }

            assertEquals(InvalidWorkspaceFileCursorScope.PAGE_HANDLE, crossModule.scope)
            assertEquals(InvalidWorkspaceFileCursorScope.PAGE_HANDLE, consumed.scope)
        } finally {
            paging.close()
        }
    }

    @Test
    fun `equal-cardinality replacement stales reusable snapshot before paging or validation`() {
        val inventory = MutableInventory(snapshot("Alpha.kt", "Beta.kt"))
        val paging = paging(inventory)
        try {
            val pageMetadata = paging.query(metadataQuery().parsed())
            val validationMetadata = paging.query(metadataQuery().parsed())
            inventory.current = snapshot("Alpha.kt", "Replacement.kt")

            assertThrows(WorkspaceInventoryStaleException::class.java) {
                paging.query(pageQuery(pageMetadata.snapshotToken).parsed())
            }
            assertThrows(WorkspaceInventoryStaleException::class.java) {
                paging.query(validationQuery(validationMetadata.snapshotToken).parsed())
            }
        } finally {
            paging.close()
        }
    }

    @Test
    fun `snapshot handles are scoped by kind and expire or evict through typed snapshot failure`() {
        val clock = MutableClock()
        val inventory = MutableInventory(snapshot("Alpha.kt", scripts = listOf("build.gradle.kts")))
        val paging = paging(inventory, clock = clock, capacity = 1)
        try {
            val source = paging.query(metadataQuery().parsed())
            val script = paging.query(
                WorkspaceFilesQuery(kindDomain = WorkspaceFileKindDomain.SCRIPT_ONLY).parsed(),
            )

            val evicted = assertThrows(InvalidWorkspaceFileCursorException::class.java) {
                paging.query(validationQuery(source.snapshotToken).parsed())
            }
            val kindMismatch = assertThrows(InvalidWorkspaceFileCursorException::class.java) {
                paging.query(
                    WorkspaceFilesQuery(
                        kindDomain = WorkspaceFileKindDomain.SOURCE_ONLY,
                        snapshotToken = script.snapshotToken,
                    ).parsed(),
                )
            }
            assertEquals(InvalidWorkspaceFileCursorScope.SNAPSHOT_HANDLE, evicted.scope)
            assertEquals(InvalidWorkspaceFileCursorScope.SNAPSHOT_HANDLE, kindMismatch.scope)

            val expiring = paging.query(metadataQuery().parsed())
            clock.advance(Duration.ofMinutes(2))
            val expired = assertThrows(InvalidWorkspaceFileCursorException::class.java) {
                paging.query(validationQuery(expiring.snapshotToken).parsed())
            }
            assertEquals(InvalidWorkspaceFileCursorScope.SNAPSHOT_HANDLE, expired.scope)
        } finally {
            paging.close()
        }
    }

    @Test
    fun `forged handles and store close are typed rather than leaking continuation internals`() {
        val paging = paging(MutableInventory(snapshot("Alpha.kt")))
        val forged = WorkspaceFileSnapshotToken.parse("00000000-0000-0000-0000-000000000099")
        val unknown = assertThrows(InvalidWorkspaceFileCursorException::class.java) {
            paging.query(validationQuery(forged.value).parsed())
        }
        assertEquals(InvalidWorkspaceFileCursorScope.SNAPSHOT_HANDLE, unknown.scope)

        paging.close()
        val closed = assertThrows(InvalidWorkspaceFileCursorException::class.java) {
            paging.query(metadataQuery().parsed())
        }
        assertEquals(InvalidWorkspaceFileCursorScope.SNAPSHOT_HANDLE, closed.scope)
    }

    private fun paging(
        inventory: MutableInventory,
        clock: ContinuationClock = ContinuationClock { 0L },
        capacity: Int = 8,
    ): IdeaWorkspaceFilePaging {
        var snapshotToken = 0
        var pageToken = 0
        return IdeaWorkspaceFilePaging(
            workspaceId = WorkspaceId("workspace"),
            inventory = inventory,
            limits = ServerLimits(
                maxResults = 500,
                requestTimeoutMillis = 30_000,
                maxConcurrentRequests = 4,
                continuationTtlMillis = 60_000,
                continuationCapacity = capacity,
            ),
            clock = clock,
            snapshotTokenIssuer = ContinuationTokenIssuer {
                snapshotToken += 1
                WorkspaceFileSnapshotToken.parse(uuid(snapshotToken))
            },
            pageTokenIssuer = ContinuationTokenIssuer {
                pageToken += 1
                WorkspaceFilePageToken.parse(uuid(pageToken + 100))
            },
        )
    }

    private fun metadataQuery(): WorkspaceFilesQuery = WorkspaceFilesQuery(
        kindDomain = WorkspaceFileKindDomain.SOURCE_ONLY,
    )

    private fun validationQuery(snapshotToken: String): WorkspaceFilesQuery = WorkspaceFilesQuery(
        kindDomain = WorkspaceFileKindDomain.SOURCE_ONLY,
        snapshotToken = snapshotToken,
    )

    private fun pageQuery(
        snapshotToken: String,
        pageToken: String? = null,
        moduleName: String = "main",
    ): WorkspaceFilesQuery = WorkspaceFilesQuery(
        kindDomain = WorkspaceFileKindDomain.SOURCE_ONLY,
        moduleName = moduleName,
        includeFiles = true,
        maxFilesPerModule = 2,
        snapshotToken = snapshotToken,
        pageToken = pageToken,
    )

    private fun snapshot(
        vararg sources: String,
        scripts: List<String> = emptyList(),
    ): IdeaWorkspaceFileInventorySnapshot = IdeaWorkspaceFileInventorySnapshot.create(
        kindDomain = WorkspaceFileKindDomain.MIXED,
        modules = listOf(
            IdeaWorkspaceModuleSnapshot.create(
                identity = IdeaWorkspaceModuleIdentity.of("main"),
                sourceRoots = listOf("/workspace"),
                contentRoots = listOf("/workspace"),
                dependencyModuleNames = emptyList(),
                sourceFilePaths = sources.map { name -> "/workspace/$name" },
                scriptFilePaths = scripts.map { name -> "/workspace/$name" },
            ),
        ),
    )

    private fun uuid(suffix: Int): String = "00000000-0000-0000-0000-${suffix.toString().padStart(12, '0')}"

    private class MutableInventory(
        var current: IdeaWorkspaceFileInventorySnapshot,
    ) : IdeaWorkspaceFileInventory {
        override fun snapshot(kindDomain: WorkspaceFileKindDomain): IdeaWorkspaceFileInventorySnapshot =
            IdeaWorkspaceFileInventorySnapshot.create(kindDomain, current.modules)
    }

    private class MutableClock : ContinuationClock {
        private var nowNanos: Long = 0L

        override fun nowNanos(): Long = nowNanos

        fun advance(duration: Duration) {
            nowNanos = Math.addExact(nowNanos, duration.toNanos())
        }
    }
}
