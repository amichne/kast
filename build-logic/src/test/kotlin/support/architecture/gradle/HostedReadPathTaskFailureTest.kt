package support.architecture.gradle

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import support.architecture.ArchitecturePolicyValidation
import support.architecture.HostedReadPathDerivation
import support.architecture.HostedReadPathDeriver
import support.architecture.Kvp018PredecessorReceiptFailure
import support.architecture.Kvp018PredecessorReceiptId
import support.architecture.ModuleId
import support.architecture.ValidatedArchitecturePolicy

class HostedReadPathTaskFailureTest {
    @Test
    fun `canonical architecture helper returns the validated policy`() {
        val validation = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            canonicalArchitecturePolicy(),
        )

        assertTrue(ModuleId.WORKSPACE_INTELLIJ_READ in validation.architecture.modules)
    }

    @Test
    fun `malformed and unreadable predecessor receipts remain typed`(
        @TempDir directory: Path,
    ) {
        val malformedPath = directory.resolve("malformed.receipt.json")
        Files.writeString(malformedPath, "not-json")
        val malformedObservation = assertInstanceOf<HostedReadPredecessorReceiptObservation.Rejected>(
            observeKvp018PredecessorReceipt(
                Kvp018PredecessorReceiptId.KVP_016_COMPLETE,
                malformedPath,
            ),
        )
        val malformedFailure = assertInstanceOf<HostedReadPathTaskFailure.ReceiptRejected>(
            malformedObservation.failure,
        )
        val decodeFailure = assertInstanceOf<Kvp018PredecessorReceiptFailure.MalformedReceipt>(
            malformedFailure.failure,
        )

        assertEquals(Kvp018PredecessorReceiptId.KVP_016_COMPLETE, decodeFailure.id)
        assertEquals(
            "predecessor receipt rejected: $decodeFailure",
            malformedFailure.renderAtGradleBoundary(),
        )

        val missingPath = directory.resolve("missing.receipt.json")
        val unreadableObservation = assertInstanceOf<HostedReadPredecessorReceiptObservation.Rejected>(
            observeKvp018PredecessorReceipt(
                Kvp018PredecessorReceiptId.KVP_017_COMPLETE,
                missingPath,
            ),
        )
        val unreadableFailure = HostedReadPathTaskFailure.ReceiptUnreadable(
            Kvp018PredecessorReceiptId.KVP_017_COMPLETE,
            missingPath,
        )

        assertEquals(unreadableFailure, unreadableObservation.failure)
        assertEquals(
            "predecessor receipt unreadable: KVP_017_COMPLETE at $missingPath",
            unreadableFailure.renderAtGradleBoundary(),
        )
    }

    @Test
    fun `hosted path derivation rejects an architecture without its module`() {
        val canonical = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            canonicalArchitecturePolicy(),
        ).architecture
        val missingModuleArchitecture = ValidatedArchitecturePolicy(
            canonical.modules - ModuleId.WORKSPACE_INTELLIJ_READ,
            canonical.moduleOrder - ModuleId.WORKSPACE_INTELLIJ_READ,
        )

        val rejection = assertInstanceOf<HostedReadPathDerivation.ModuleUnavailable>(
            HostedReadPathDeriver.derive(
                missingModuleArchitecture,
                classes = emptyList(),
                requiredClassNames = emptySet(),
                runtimeProjectJars = emptyList(),
                runtimeExternalJars = emptyList(),
            ),
        )

        assertEquals(ModuleId.WORKSPACE_INTELLIJ_READ, rejection.module)
        assertEquals(
            "hosted path derivation rejected: $rejection",
            HostedReadPathTaskFailure.DerivationRejected(rejection).renderAtGradleBoundary(),
        )
    }
}
