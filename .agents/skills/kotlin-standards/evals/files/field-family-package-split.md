# Field family package split

## Situation

An agent is adding a typed configuration API. It creates one file:

```kotlin
package io.github.example.config

sealed class ConfigurationField<T>
data class ServerMaxResults(val value: Int) : ConfigurationField<Int>()
data class ServerRequestTimeoutMillis(val value: Long) : ConfigurationField<Long>()
data class IndexingPhase2Enabled(val value: Boolean) : ConfigurationField<Boolean>()
data class CacheEnabled(val value: Boolean) : ConfigurationField<Boolean>()
data class TelemetryEnabled(val value: Boolean) : ConfigurationField<Boolean>()
data class PathsInstallRoot(val value: String) : ConfigurationField<String>()
data class CliBinaryPath(val value: String) : ConfigurationField<String>()
```

The file keeps growing as more configuration fields are added. Each field is a public type that users may search for,
import, or inspect independently.

## Desired review

Recommend moving the field vocabulary into `config.fields`, placing
`ConfigurationField` and each concrete field class in its own file. Helper functions that compute defaults may share a
focused `ConfigurationDefaults.kt`
file, but the primary public classes should not stay bundled in one root-level declaration file.
