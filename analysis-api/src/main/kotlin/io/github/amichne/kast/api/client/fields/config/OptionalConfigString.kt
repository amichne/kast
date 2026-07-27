package io.github.amichne.kast.api.client.fields

data class OptionalConfigString(val orNull: String?) {
    companion object {
        val Unset: OptionalConfigString = OptionalConfigString(null)
    }
}
