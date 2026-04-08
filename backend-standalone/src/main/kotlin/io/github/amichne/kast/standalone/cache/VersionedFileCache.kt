package io.github.amichne.kast.standalone.cache

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Default JSON configuration shared by versioned caches.
 * Matches the settings used by the individual caches before this base class was introduced.
 */
internal val defaultCacheJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * Base class for caches that persist versioned JSON payloads to disk.
 *
 * Subclasses provide the payload serializer and a way to extract the schema
 * version from the deserialized payload (which keeps the on-disk format
 * unchanged — no outer wrapper layer).
 *
 * Enabled/disabled checks and atomic writes are handled here so that
 * individual caches only contain their own domain logic.
 */
internal abstract class VersionedFileCache<P : Any>(
    protected val schemaVersion: Int,
    protected val serializer: KSerializer<P>,
    protected val enabled: Boolean,
    protected val json: Json = defaultCacheJson,
) {
    /** Extract the schema-version field from a deserialized payload. */
    protected abstract fun payloadSchemaVersion(payload: P): Int

    /**
     * Read and deserialize the payload at [path], returning `null` when
     * the cache is disabled, the file is missing, or the schema version
     * does not match.
     */
    protected fun readPayload(path: Path): P? {
        if (!enabled || !Files.isRegularFile(path)) return null
        val payload = json.decodeFromString(serializer, Files.readString(path))
        if (payloadSchemaVersion(payload) != schemaVersion) return null
        return payload
    }

    /**
     * Atomically write the given [payload] to [path] when the cache is
     * enabled. Creates parent directories as needed.
     */
    protected fun writePayload(path: Path, payload: P) {
        if (!enabled) return
        writeCacheFileAtomically(
            path = path,
            payload = json.encodeToString(serializer, payload),
        )
    }
}
