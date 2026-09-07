package io.github.amichne.kast.cli.broker

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class CodexHostInvocationTest {
    @Test
    fun `one app server role retains global and role arguments`() {
        val invocation = assertInstanceOf(
            CodexHostInvocation.AppServer::class.java,
            refined(
                CodexHostInvocation.admit(
                    listOf(
                        "-c",
                        "features.code_mode_host=true",
                        "app-server",
                        "--analytics-default-enabled",
                        "-c",
                        "mcp_servers.codex_app.enabled=true",
                    ),
                ),
            ),
        )

        assertEquals(CodexHostMode.APP_SERVER_STDIO, invocation.mode)
        assertEquals(
            listOf("-c", "features.code_mode_host=true"),
            invocation.arguments.globalValues,
        )
        assertEquals(
            listOf(
                "--analytics-default-enabled",
                "-c",
                "mcp_servers.codex_app.enabled=true",
            ),
            invocation.arguments.roleValues,
        )
    }

    @Test
    fun `ordinary invocation remains the remote client host`() {
        val invocation = assertInstanceOf(
            CodexHostInvocation.Cli::class.java,
            refined(CodexHostInvocation.admit(listOf("--model", "gpt-5"))),
        )

        assertEquals(CodexHostMode.CLI_REMOTE_CLIENT, invocation.mode)
        assertEquals(listOf("--model", "gpt-5"), invocation.arguments.values)
    }

    @Test
    fun `app server text used as an option value remains a CLI invocation`() {
        val invocation = assertInstanceOf(
            CodexHostInvocation.Cli::class.java,
            refined(CodexHostInvocation.admit(listOf("--model", "app-server"))),
        )

        assertEquals(listOf("--model", "app-server"), invocation.arguments.values)
    }

    @Test
    fun `transport and subcommand text used as role option values are not reinterpreted`() {
        val invocation = assertInstanceOf(
            CodexHostInvocation.AppServer::class.java,
            refined(
                CodexHostInvocation.admit(
                    listOf("app-server", "-c", "--listen", "-c", "proxy"),
                ),
            ),
        )

        assertEquals(
            listOf("-c", "--listen", "-c", "proxy"),
            invocation.arguments.roleValues,
        )
    }

    @Test
    fun `host owned transports and ambiguous roles fail closed`() {
        listOf(
            listOf("app-server", "--stdio"),
            listOf("app-server", "--listen", "stdio://"),
            listOf("app-server", "--listen=stdio://"),
            listOf("app-server", "--code-mode-host", "https://example.invalid"),
            listOf("app-server", "proxy"),
            listOf("app-server", "generate-json-schema"),
            listOf("app-server", "app-server"),
        ).forEach { arguments ->
            assertInstanceOf(
                Refinement.Rejected::class.java,
                CodexHostInvocation.admit(arguments),
                arguments.toString(),
            )
        }
    }

    private fun <T, E> refined(refinement: Refinement<T, E>): T =
        (refinement as Refinement.Refined).value
}
