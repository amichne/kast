package io.github.amichne.kast.distribution.cli

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContainerCommandTest {
    @Test
    fun `container list and lifecycle are native management commands`() {
        for (args in
            listOf(
                listOf("container"),
                listOf("container", "create"),
                listOf("container", "create", "--name", "backend", "--root", "/workspace"),
                listOf("container", "resume", "backend"),
                listOf("container", "stop", "backend"),
            )) {
            assertTrue(parseManagementCommand(args) is ManagementParsing.Selected, args.toString())
        }
    }

    @Test
    fun `unknown container commands and missing identities reject`() {
        for (args in listOf(listOf("container", "resume"), listOf("container", "stop"), listOf("container", "guess"))) {
            val result = parseManagementCommand(args)
            assertTrue(result is ManagementParsing.Print && result.error, args.toString())
        }
    }
}
