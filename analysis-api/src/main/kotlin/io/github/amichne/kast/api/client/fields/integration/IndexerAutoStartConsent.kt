package io.github.amichne.kast.api.client.fields

enum class IndexerAutoStartConsent {
    Unconfigured,
    Enabled,
    Disabled,
    ;

    companion object {
        fun fromBoolean(value: Boolean): IndexerAutoStartConsent =
            if (value) Enabled else Disabled
    }
}
