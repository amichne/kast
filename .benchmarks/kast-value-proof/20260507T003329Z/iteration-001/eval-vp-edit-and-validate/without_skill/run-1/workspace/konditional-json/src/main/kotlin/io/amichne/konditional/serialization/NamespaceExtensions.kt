package io.amichne.konditional.serialization

import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.instance.Configuration
import io.amichne.konditional.core.result.ParseOutcome
import io.amichne.konditional.core.result.toParseResult
import io.amichne.konditional.serialization.snapshot.ConfigurationCodec

fun Namespace.toJson(): String = ConfigurationCodec.encode(this)

fun Namespace.fromJson(json: String): ParseOutcome<Configuration> =
    ConfigurationCodec.decode(json, this)
        .onSuccess { configuration -> load(configuration) }
        .toParseResult()
