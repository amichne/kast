package io.github.amichne.kast.distribution.contract.configuration

/** Fixed installation policies. Standalone script projections are checked against the generated catalogue. */
object InstallationOperationalLimits {
    const val retirementChildTimeoutMillis: Long = 60_000
    const val activationLockTimeoutMillis: Long = 30_000
    const val activationLockPollMillis: Long = 50
    const val downloadRetries: Long = 5
    const val downloadRetryDelayMillis: Long = 2_000
    const val stateMaximumEntries: Long = 100_000

    val declarations: List<ConfigurationOperationalLimit> =
        listOf(
            limit(
                "installation.retirement.child_timeout",
                retirementChildTimeoutMillis,
                ConfigurationUnit.MILLISECONDS,
                "retirementChildTimeoutMillis",
                projected = true,
            ),
            limit(
                "installation.activation.lock_timeout",
                activationLockTimeoutMillis,
                ConfigurationUnit.MILLISECONDS,
                "activationLockTimeoutMillis",
            ),
            limit(
                "installation.activation.lock_poll",
                activationLockPollMillis,
                ConfigurationUnit.MILLISECONDS,
                "activationLockPollMillis",
            ),
            limit(
                "installation.download.retries",
                downloadRetries,
                ConfigurationUnit.COUNT,
                "downloadRetries",
                projected = true,
            ),
            limit(
                "installation.download.retry_delay",
                downloadRetryDelayMillis,
                ConfigurationUnit.MILLISECONDS,
                "downloadRetryDelayMillis",
                projected = true,
            ),
            limit(
                "installation.state.maximum_entries",
                stateMaximumEntries,
                ConfigurationUnit.COUNT,
                "stateMaximumEntries",
                projected = true,
            ),
        )

    private fun limit(key: String, value: Long, unit: ConfigurationUnit, member: String, projected: Boolean = false) =
        ConfigurationOperationalLimit(
            key,
            ":distribution:contract",
            value,
            unit,
            ConfigurationScope.INSTALLATION,
            "InstallationOperationalLimits.$member" +
                if (projected) "; standalone projections verified by configuration_ingress.py" else "",
        )
}
