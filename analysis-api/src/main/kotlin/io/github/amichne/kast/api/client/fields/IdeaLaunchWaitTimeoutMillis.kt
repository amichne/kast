package io.github.amichne.kast.api.client.fields

data class IdeaLaunchWaitTimeoutMillis(
    override val value: Long,
) : ConfigurationField<Long>() {
    init {
        require(value > 0L) { "runtime.ideaLaunch.waitTimeoutMillis must be greater than zero" }
    }

    override val section: String get() = "runtime.ideaLaunch"
    override val key: String get() = "waitTimeoutMillis"
    override val default: ConfigurationDefault<Long> get() = ConfigurationDefault(90_000L)
}
