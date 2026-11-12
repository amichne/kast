package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class IdeLifecycleExchangeDeadlineTest {
    @Test
    fun `a late timer never closes a successfully completed frame`() {
        val attempt =
            LifecycleExchangeAttempt(io.github.amichne.kast.protocol.contract.IdeLifecycleCommand.Inspect) { 0 }
        val result = IdeLifecycleResult.Blocked(IdeLifecycleFailure.WRONG_HOST)
        assertEquals(result, attempt.complete(result))
        var closes = 0
        attempt.expire { closes++ }
        assertEquals(0, closes)
    }

    @Test
    fun `connection refusal and a truncated frame retain their distinct observed causes`() {
        for (cause in
            listOf(
                io.github.amichne.kast.protocol.contract.IdeLifecycleExchangeCause.CONNECTION_FAILED,
                io.github.amichne.kast.protocol.contract.IdeLifecycleExchangeCause.TRUNCATED_RESPONSE,
            )) {
            val attempt =
                LifecycleExchangeAttempt(io.github.amichne.kast.protocol.contract.IdeLifecycleCommand.Inspect) { 0 }
            val result = attempt.reject(cause)
            assertEquals(IdeLifecycleFailure.HOST_UNAVAILABLE, result.reason)
            assertEquals(cause, result.exchange?.cause)
        }
    }

    @Test
    fun `a fired frame timer remains a transport deadline instead of claiming the host is unavailable`(
        @TempDir root: Path
    ) {
        val realRoot = root.toRealPath()
        val selectedHome = Files.createDirectory(realRoot.resolve("idea"))
        val digest =
            MessageDigest.getInstance("SHA-256").digest(selectedHome.toString().toByteArray()).take(16).joinToString(
                ""
            ) {
                "%02x".format(it)
            }
        val socket =
            io.github.amichne.kast.distribution.contract.HostedEndpointPaths.family(
                    realRoot,
                    io.github.amichne.kast.distribution.contract.HostedEndpointPaths.Family.LIFECYCLE,
                )
                .resolve("$digest/host.sock")
        Files.createDirectories(socket.parent)
        Files.createFile(socket)
        var now = 0L
        var timerClosed = 0
        val timer = LifecycleExchangeTimer { expire ->
            now = 5_000_000_000
            expire()
            AutoCloseable { timerClosed++ }
        }
        val client = LifecycleSocketExchange(realRoot, timer, { now })
        val result = client.exchange(selectedHome, IdeLifecycleCommand.Inspect) as IdeLifecycleResult.Blocked
        assertEquals(IdeLifecycleFailure.EXCHANGE_DEADLINE_EXCEEDED, result.reason)
        assertEquals(5000, result.exchange?.elapsedMillis)
        assertEquals(5000, result.exchange?.limitMillis)
        assertEquals(
            io.github.amichne.kast.protocol.contract.IdeLifecycleExchangeCause.DEADLINE_EXCEEDED,
            result.exchange?.cause,
        )
        assertEquals(1, timerClosed)
    }

    @Test
    fun `elapsed time alone and a late timer cannot rewrite the observed IO failure`() {
        var now = 0L
        var closes = 0
        val attempt =
            LifecycleExchangeAttempt(io.github.amichne.kast.protocol.contract.IdeLifecycleCommand.Inspect) { now }
        now = 6_000_000_000
        val failed = attempt.reject(io.github.amichne.kast.protocol.contract.IdeLifecycleExchangeCause.IO_FAILURE)
        attempt.expire { closes++ }
        assertEquals(0, closes)
        assertEquals(IdeLifecycleFailure.HOST_UNAVAILABLE, failed.reason)
        assertEquals(
            io.github.amichne.kast.protocol.contract.IdeLifecycleExchangeCause.IO_FAILURE,
            failed.exchange?.cause,
        )
        assertEquals(6000, failed.exchange?.elapsedMillis)
    }
}
