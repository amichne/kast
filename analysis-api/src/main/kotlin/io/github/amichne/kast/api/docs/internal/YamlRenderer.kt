package io.github.amichne.kast.api.docs.internal

/** Renders a YAML document fragment from a nested Map/List/scalar structure. */
internal fun renderYaml(
    value: Any?,
    indent: Int = 0,
): String = when (value) {
    null -> "${" ".repeat(indent)}null\n"
    is Map<*, *> -> {
        if (value.isEmpty()) {
            "${" ".repeat(indent)}{}\n"
        } else {
            buildString {
                value.forEach { (rawKey, rawEntryValue) ->
                    val key = renderKey(rawKey.toString())
                    when (rawEntryValue) {
                        is Map<*, *>, is List<*> -> {
                            append(" ".repeat(indent))
                            append(key)
                            append(":\n")
                            append(renderYaml(rawEntryValue, indent + 2))
                        }
                        else -> {
                            append(" ".repeat(indent))
                            append(key)
                            append(": ")
                            append(renderScalar(rawEntryValue))
                            append('\n')
                        }
                    }
                }
            }
        }
    }
    is List<*> -> {
        if (value.isEmpty()) {
            "${" ".repeat(indent)}[]\n"
        } else {
            buildString {
                value.forEach { entry ->
                    when (entry) {
                        is Map<*, *> -> {
                            val entries = entry.entries.toList()
                            if (entries.isNotEmpty()) {
                                val (firstKey, firstValue) = entries.first()
                                val key = renderKey(firstKey.toString())
                                append(" ".repeat(indent))
                                append("- ")
                                when (firstValue) {
                                    is Map<*, *>, is List<*> -> {
                                        append(key)
                                        append(":\n")
                                        append(renderYaml(firstValue, indent + 4))
                                    }
                                    else -> {
                                        append(key)
                                        append(": ")
                                        append(renderScalar(firstValue))
                                        append('\n')
                                    }
                                }
                                entries.drop(1).forEach { (restKey, restValue) ->
                                    val rk = renderKey(restKey.toString())
                                    append(" ".repeat(indent + 2))
                                    when (restValue) {
                                        is Map<*, *>, is List<*> -> {
                                            append(rk)
                                            append(":\n")
                                            append(renderYaml(restValue, indent + 4))
                                        }
                                        else -> {
                                            append(rk)
                                            append(": ")
                                            append(renderScalar(restValue))
                                            append('\n')
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            append(" ".repeat(indent))
                            append("- ")
                            append(renderScalar(entry))
                            append('\n')
                        }
                    }
                }
            }
        }
    }
    else -> "${" ".repeat(indent)}${renderScalar(value)}\n"
}

private fun renderScalar(value: Any?): String = when (value) {
    null -> "null"
    is Boolean -> value.toString()
    is Number -> value.toString()
    is String -> when {
        value.isEmpty() -> "\"\""
        value.startsWith("#") || value.contains(": ") || value.contains("\"") ||
        value == "true" || value == "false" || value == "null" ||
        value.contains("{") || value.contains("}") ->
            "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        else -> value
    }
    else -> value.toString()
}

private fun renderKey(key: String): String =
    if (key.startsWith("\$") || key.contains("/") || key.contains(" ")) "\"$key\"" else key
