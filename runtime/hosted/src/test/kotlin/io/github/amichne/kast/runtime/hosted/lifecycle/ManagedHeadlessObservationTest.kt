package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ManagedHeadlessObservationTest {
    @Test
    fun `startup and storage outcomes preserve the explicit discriminator and reason`() {
        val admitted =
            listOf(
                ManagedHeadlessObservation.StartupAdmitted to "startup_admitted",
                ManagedHeadlessObservation.StorageAdmitted to "storage_admitted",
                ManagedHeadlessObservation.JbrConfigured to "jbr_configured",
            )
        for ((value, type) in admitted) {
            val encoded =
                Json.parseToJsonElement(Json.encodeToString(ManagedHeadlessObservation.serializer(), value)).jsonObject
            assertEquals(setOf("type"), encoded.keys)
            assertEquals(type, encoded.getValue("type").jsonPrimitive.content)
        }
        val rejected =
            listOf(
                Triple(
                    ManagedHeadlessObservation.StartupRejected(HeadlessHostFailure.FOREIGN_JBR),
                    "startup_rejected",
                    "FOREIGN_JBR",
                ),
                Triple(
                    ManagedHeadlessObservation.StorageRejected(HeadlessProjectStoreFailure.WRONG_METADATA_PATH),
                    "storage_rejected",
                    "WRONG_METADATA_PATH",
                ),
                Triple(
                    ManagedHeadlessObservation.JbrRejected(HeadlessJbrFailure.INVALID_HOME),
                    "jbr_rejected",
                    "INVALID_HOME",
                ),
            )
        for ((value, type, reason) in rejected) {
            val encoded =
                Json.parseToJsonElement(Json.encodeToString(ManagedHeadlessObservation.serializer(), value)).jsonObject
            assertEquals(setOf("type", "reason"), encoded.keys)
            assertEquals(type, encoded.getValue("type").jsonPrimitive.content)
            assertEquals(reason, encoded.getValue("reason").jsonPrimitive.content)
        }
    }

    @Test
    fun `native storage admission preserves both identities and rejects repository metadata`() {
        val repository = Path.of("/workspace")
        val root = (CanonicalWorkspaceRoot.fromCanonicalPath(repository) as Refinement.Refined).value
        val metadata = Path.of("/owned/system/project/.idea")
        val admitted =
            HeadlessProjectStore.admit(root, metadata, repository, repository, metadata, true) as Refinement.Refined
        assertEquals(root, admitted.value.root)
        assertEquals(metadata, admitted.value.directory)
        assertEquals(
            Refinement.Rejected(HeadlessProjectStoreFailure.WRONG_METADATA_PATH),
            HeadlessProjectStore.admit(root, metadata, repository, repository, repository.resolve(".idea"), true),
        )
        assertEquals(
            Refinement.Rejected(HeadlessProjectStoreFailure.WRONG_PROJECT_IDENTITY),
            HeadlessProjectStore.admit(root, metadata, repository, Path.of("/another"), metadata, true),
        )
        assertEquals(
            Refinement.Rejected(HeadlessProjectStoreFailure.EXTERNAL_STORAGE_UNAVAILABLE),
            HeadlessProjectStore.admit(root, metadata, repository, repository, metadata, false),
        )
    }
}
