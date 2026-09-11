package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.appserver.provider.BrokerExecutable
import io.github.amichne.kast.appserver.provider.BrokerProcessExecution
import io.github.amichne.kast.appserver.provider.BrokerProcessInput
import io.github.amichne.kast.appserver.provider.BrokerProcessRequest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.JsonObject

/** A genuine reference from A is submitted to the staged CLI in owned root B; no alternate host is admitted. */
internal class NativeForeignRootBoundary(
    private val product: Path,
    private val workspace: Path,
    private val trace: NativeProcessTrace,
) {
    suspend fun reject(arguments: JsonObject) {
        val foreign = workspace.parent.resolve("foreign-workspace")
        Files.createDirectory(
            foreign,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
        Files.writeString(foreign.resolve("settings.gradle.kts"), "rootProject.name = \"foreign-root-refusal\"\n")
        val request =
            BrokerProcessRequest.admit(
                    executable = BrokerExecutable.admit(product.resolve("bin/kast")).nativeValue(),
                    arguments = listOf("change", "plan"),
                    workingDirectory = checkNotNull(CanonicalBrokerDirectory.admit(foreign)),
                    maximumOutputBytes = 64 * 1024,
                    timeoutMillis = 30_000,
                    input = BrokerProcessInput.Document.admit(arguments.toString()).nativeValue(),
                )
                .nativeValue()
        val result =
            trace.execute(request) as? BrokerProcessExecution.Completed
                ?: throw NativeRejected(NativeFailure.PROVIDER_REJECTED)
        demand(
            nativeBoundaryRejection(result) == NativeBoundaryRejection.IDE_HOST_UNAVAILABLE,
            NativeFailure.EXPECTED_REJECTION_MISSING,
        )
        demand(Files.list(foreign).use { entries -> entries.count() } == 1L, NativeFailure.SOURCE_CHANGED)
    }
}
