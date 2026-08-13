package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFileManager
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal const val EVENT_DRIVEN_FILE_COUNT: Int = 1_000

internal fun prepareThousandFileRepository(
    repository: Path,
    filter: Path,
    filterRelease: Path,
) {
    runGitCommand(repository, "init", "--initial-branch=state-a")
    runGitCommand(repository, "config", "user.name", "Kast Test")
    runGitCommand(repository, "config", "user.email", "kast@example.invalid")
    runGitCommand(repository, "config", "filter.kast-proof.required", "true")
    runGitCommand(repository, "config", "filter.kast-proof.clean", "cat")
    runGitCommand(
        repository,
        "config",
        "filter.kast-proof.smudge",
        "${shellQuote(filter)} ${shellQuote(filter.resolveSibling("checkout-filter-started"))} " +
        shellQuote(filterRelease),
    )
    Files.writeString(repository.resolve(".gitattributes"), "zzzz/hold.proof filter=kast-proof\n")
    writeCheckoutTree(repository, "a")
    runGitCommand(repository, "add", ".")
    runGitCommand(repository, "commit", "-m", "state a")

    runGitCommand(repository, "switch", "-c", "state-b")
    writeCheckoutTree(repository, "b")
    Files.deleteIfExists(repository.resolve("src/removed/Removed.kt"))
    Files.createDirectories(repository.resolve("src/added"))
    Files.writeString(repository.resolve("src/added/Added.kt"), "package added\nclass Added\n")
    runGitCommand(repository, "add", "-A")
    runGitCommand(repository, "commit", "-m", "state b")

    Files.createFile(filterRelease)
    runGitCommand(repository, "switch", "state-a")
}

private fun writeCheckoutTree(
    repository: Path,
    state: String,
) {
    val sources = repository.resolve("src/main/kotlin/demo").also(Files::createDirectories)
    val semanticState = if (state == "a") "a" else "changed-state-b"
    repeat(EVENT_DRIVEN_FILE_COUNT) { index ->
        Files.writeString(
            sources.resolve("File${index.toString().padStart(4, '0')}.kt"),
            "package demo\nclass File$index { val state = \"$semanticState\" }\n",
        )
    }
    Files.createDirectories(repository.resolve("src/removed"))
    Files.writeString(repository.resolve("src/removed/Removed.kt"), "package removed\nclass Removed$state\n")
    Files.writeString(repository.resolve("build.gradle.kts"), "version = \"$state\"\n")
    Files.writeString(repository.resolve("settings.gradle.kts"), "rootProject.name = \"checkout-$state\"\n")
    Files.writeString(repository.resolve(".kastignore"), "ignored-$state/**\n")
    Files.createDirectories(repository.resolve(".idea"))
    Files.writeString(repository.resolve(".idea/compiler.xml"), "<compiler state=\"$state\"/>\n")
    Files.createDirectories(repository.resolve("zzzz"))
    Files.writeString(repository.resolve("zzzz/hold.proof"), "hold-$state\n")
}

internal fun createBlockingCheckoutFilter(
    tempDir: Path,
    started: Path,
    release: Path,
): Path {
    val filter = tempDir.resolve("checkout-filter.sh")
    Files.writeString(
        filter,
        """#!/bin/sh
started="${'$'}1"
release="${'$'}2"
: > "${'$'}started"
while [ ! -e "${'$'}release" ]; do sleep 0.01; done
cat
""",
    )
    assertTrue(filter.toFile().setExecutable(true), "checkout filter must be executable")
    Files.deleteIfExists(started)
    Files.deleteIfExists(release)
    return filter
}

internal fun sourceIdentity(source: Path): WorkspaceStateIdentity =
    WorkspaceStateIdentity(
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))),
    )

internal fun treeIdentity(root: Path): WorkspaceStateIdentity {
    val digest = MessageDigest.getInstance("SHA-256")
    val gitDirectory = root.resolve(".git")
    Files.walk(root).use { paths ->
        paths.filter { path -> Files.isRegularFile(path) && !path.startsWith(gitDirectory) }
            .sorted()
            .forEach { path ->
                digest.update(root.relativize(path).toString().toByteArray())
                digest.update(0.toByte())
                digest.update(Files.readAllBytes(path))
                digest.update(0.toByte())
            }
    }
    return WorkspaceStateIdentity(HexFormat.of().formatHex(digest.digest()))
}

internal fun awaitSignal(
    delivered: LinkedBlockingQueue<WorkspaceSignal>,
    expected: WorkspaceSignal,
): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < deadline) {
        if (delivered.poll(100, TimeUnit.MILLISECONDS) == expected) return true
    }
    return false
}

internal fun awaitPath(
    path: Path,
    timeout: Duration,
): Boolean {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (System.nanoTime() < deadline) {
        if (Files.exists(path)) return true
        Thread.sleep(10)
    }
    return false
}

internal fun awaitReady(admission: IdeaIndexSemanticAdmission): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < deadline) {
        if (admission.status() is IdeaIndexSemanticAdmission.Status.Ready) return true
        Thread.sleep(10)
    }
    return false
}

internal fun syncRefresh() {
    ApplicationManager.getApplication().invokeAndWait {
        VirtualFileManager.getInstance().syncRefresh()
    }
}

private fun shellQuote(path: Path): String = "'${path.toAbsolutePath().toString().replace("'", "'\\''")}'"
