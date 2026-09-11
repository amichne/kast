package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.CanonicalRoot
import io.github.amichne.kast.cli.CanonicalRootDiscoverer
import io.github.amichne.kast.cli.CanonicalRootDiscovery
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExistingIdeChangeCliTest {
    @Test
    fun `supported plan reaches only the hosted capability`() {
        val root = CanonicalRoot(Path.of("/workspace"))
        var calls = 0
        val result =
            executeExistingIdeCli(
                argv = listOf("change", "plan"),
                start = root.path,
                roots = CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(root) },
                client =
                    ExistingIdeClient { _, _ ->
                        calls++
                        ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
                    },
                requestInput =
                    CliRequestDocumentInput.Provided(
                        """{"intent":{"kind":"add-declaration","exactTarget":"exact:opaque\u005Fref",""" +
                            """"declaration":"fun added() = Unit"}}"""
                    ),
            )
        assertEquals(1, calls)
        assertTrue(result.document.value.contains("ide-host-unavailable"))
    }

    @Test
    fun `approval preparation reaches hosted ingress with exact plan identity`() {
        val root = CanonicalRoot(Path.of("/workspace"))
        var calls = 0
        executeExistingIdeCli(
            argv = listOf("change", "apply", "--stdin", "--hosted-approval-prepare"),
            start = root.path,
            roots = CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(root) },
            client =
                ExistingIdeClient { _, _ ->
                    calls++
                    ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
                },
            requestInput = CliRequestDocumentInput.Provided("""{"planIdentity":"plan:${"a".repeat(64)}"}"""),
        )
        assertEquals(1, calls)
    }

    @Test
    fun `ordinary apply requires approval before calling the hosted capability`() {
        val root = CanonicalRoot(Path.of("/workspace"))
        var calls = 0
        val result =
            executeExistingIdeCli(
                argv = listOf("change", "apply"),
                start = root.path,
                roots = CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(root) },
                client =
                    ExistingIdeClient { _, _ ->
                        calls++
                        ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
                    },
                requestInput = CliRequestDocumentInput.Provided("""{"planIdentity":"plan:${"a".repeat(64)}"}"""),
            )
        assertEquals(0, calls)
        assertTrue(result.document.value.contains("approval-required"))
    }

    @Test
    fun `every change command enters hosted ingress before installed bootstrap`() {
        for (operation in listOf("plan", "apply", "recover", "unsupported")) {
            for (prefix in listOf(emptyList(), listOf("--"))) {
                assertEquals(CliRuntimePath.EXISTING_IDE, selectCliRuntimePath(prefix + listOf("change", operation)))
            }
        }
    }
}
