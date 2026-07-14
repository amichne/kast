package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.query.WorkspaceFileKindDomain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KastWorkspaceFilesContractTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    @Test
    fun `workspace request preserves paging identity`() {
        val request = KastWorkspaceFilesRequest(
            workspaceRoot = "/workspace",
            moduleName = ":app",
            includeFiles = true,
            maxFilesPerModule = 25,
            kindDomain = WorkspaceFileKindDomain.SOURCE_ONLY,
            snapshotToken = "65ce31a2-b82c-4f8a-a425-03430ef548f9",
            pageToken = "b7c24708-715a-4897-922c-5f34d7daf848",
        )

        val encoded = json.encodeToString(KastWorkspaceFilesRequest.serializer(), request)
        val decoded = json.decodeFromString(KastWorkspaceFilesRequest.serializer(), encoded)

        assertEquals(request, decoded)
    }

    @Test
    fun `workspace query defaults to the mixed file domain`() {
        val decoded = json.decodeFromString(
            KastWorkspaceFilesQuery.serializer(),
            """{"workspaceRoot":"/workspace"}""",
        )

        assertEquals(WorkspaceFileKindDomain.MIXED, decoded.kindDomain)
        assertEquals(null, decoded.snapshotToken)
        assertEquals(null, decoded.pageToken)
    }

    @Test
    fun `workspace success response echoes the snapshot token`() {
        val response: KastWorkspaceFilesResponse = KastWorkspaceFilesSuccessResponse(
            query = KastWorkspaceFilesQuery(workspaceRoot = "/workspace"),
            modules = emptyList(),
            snapshotToken = "65ce31a2-b82c-4f8a-a425-03430ef548f9",
            schemaVersion = 8,
            logFile = "/tmp/kast.log",
        )

        val encoded = json.encodeToString(KastWorkspaceFilesResponse.serializer(), response)
        val fields = json.parseToJsonElement(encoded).jsonObject

        assertEquals("WORKSPACE_FILES_SUCCESS", fields.getValue("type").jsonPrimitive.content)
        assertEquals(
            "65ce31a2-b82c-4f8a-a425-03430ef548f9",
            fields.getValue("snapshotToken").jsonPrimitive.content,
        )
    }
}
