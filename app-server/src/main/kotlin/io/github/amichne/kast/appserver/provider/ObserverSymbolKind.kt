package io.github.amichne.kast.appserver.provider

/** Closed symbol-kind labels admitted by the inspection observer. */
internal enum class ObserverSymbolKind(val label: String) {
    CLASS_LIKE("class-like"),
    CONSTRUCTOR("constructor"),
    FUNCTION("function"),
    PROPERTY("property"),
    TYPE_ALIAS("type-alias");

    companion object {
        fun from(value: String?): ObserverSymbolKind? =
            when (value) {
                "classlike" -> CLASS_LIKE
                "constructor" -> CONSTRUCTOR
                "function" -> FUNCTION
                "property" -> PROPERTY
                "type-alias" -> TYPE_ALIAS
                else -> null
            }
    }
}
