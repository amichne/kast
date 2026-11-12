package io.github.amichne.kast.distribution.cli

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContainerCommandTest {
    @Test
    fun `former container list and lifecycle commands reject before effects`() {
        for (args in
            listOf(
                listOf("container"),
                listOf("container", "create"),
                listOf("container", "create", "--name", "backend", "--root", "/workspace"),
                listOf("container", "resume", "backend"),
                listOf("container", "stop", "backend"),
            )) {
            val result = parseManagementCommand(args)
            assertTrue(result is ManagementParsing.Print && result.error, args.toString())
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
