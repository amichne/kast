package example.use

import example.contract.Decoder
import example.flow.Attempt
import example.flow.capture
import example.flow.runFlow

fun direct(decoder: Decoder, value: String): String = decoder.decode(value)

fun nested(decoder: Decoder, value: String): Attempt<String> = runFlow {
    capture {
        decoder.decode(value)
    }
}

fun deferred(decoder: Decoder, value: String): () -> String = {
    decoder.decode(value)
}
