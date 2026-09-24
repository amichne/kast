package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExistingIdeModelRefreshTest {
    @Test
    fun `schema admitted model blocker is a typed presemantic recovery`() {
        val root = CanonicalRoot(Path.of("/workspace"))
        val name = (ExistingIdeClassName.parse("Subject") as Refinement.Refined).value
        val response =
            ExistingIdeDocuments.response(
                Json { encodeDefaults = true }.encodeToString(ModelRefreshRejection()).toByteArray(),
                root,
                ExistingIdeOperation.Classes(name),
                ExistingIdeDescriptor(1, UUID.fromString("00000000-0000-0000-0000-000000000001")),
            )
        assertEquals(HostedPresemanticRecovery.ModelReload, (response as ExistingIdeExchange.HostRejected).recovery)
    }

    @Serializable
    private data class ModelRefreshRejection(
        val type: String = "HOST_REJECTED",
        val failure: String = "MODEL_REFRESH_REQUIRED",
    )
}
