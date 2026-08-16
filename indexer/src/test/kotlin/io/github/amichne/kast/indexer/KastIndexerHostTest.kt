package io.github.amichne.kast.indexer

import io.github.amichne.kast.runtime.composition.KastRuntimeDispatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class KastIndexerHostTest {
    @Test
    fun `host delegates one raw frame only to the composition capability`() {
        val observed = mutableListOf<String>()
        val host = KastIndexerHost { document ->
            observed += document
            KastRuntimeDispatch.Responded("response")
        }

        assertEquals(
            KastRuntimeDispatch.Responded("response"),
            runSuspend { host.dispatch("request") },
        )
        assertEquals(listOf("request"), observed)
    }

    private fun <Value> runSuspend(block: suspend () -> Value): Value {
        var outcome: Result<Value>? = null
        block.startCoroutine(
            object : Continuation<Value> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<Value>) {
                    outcome = result
                }
            },
        )
        return checkNotNull(outcome).getOrThrow()
    }
}
