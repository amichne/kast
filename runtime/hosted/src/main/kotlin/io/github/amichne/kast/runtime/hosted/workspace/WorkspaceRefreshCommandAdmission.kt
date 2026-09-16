package io.github.amichne.kast.runtime.hosted.workspace

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshCommand
import java.io.StringReader
import kotlinx.serialization.json.Json

/** Reject ambiguous field ownership before polymorphic JSON decoding can collapse repeated names. */
internal fun decodeWorkspaceRefreshCommand(raw: String): WorkspaceRefreshCommand {
    JsonReader(StringReader(raw)).use { reader ->
        uniqueRefreshObject(reader, nestedRuleAllowed = true)
        require(reader.peek() == JsonToken.END_DOCUMENT)
    }
    return Json.decodeFromString(WorkspaceRefreshCommand.serializer(), raw)
}

private fun uniqueRefreshObject(reader: JsonReader, nestedRuleAllowed: Boolean) {
    require(reader.peek() == JsonToken.BEGIN_OBJECT)
    reader.beginObject()
    val names = mutableSetOf<String>()
    while (reader.hasNext()) {
        require(names.add(reader.nextName()))
        when (reader.peek()) {
            JsonToken.STRING -> reader.nextString()
            JsonToken.BEGIN_OBJECT -> {
                require(nestedRuleAllowed)
                uniqueRefreshObject(reader, nestedRuleAllowed = false)
            }
            else -> throw IllegalArgumentException("Unsupported refresh command field")
        }
    }
    reader.endObject()
}
