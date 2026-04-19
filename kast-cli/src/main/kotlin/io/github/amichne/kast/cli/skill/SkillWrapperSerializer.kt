package io.github.amichne.kast.cli.skill

import io.github.amichne.kast.api.KastCallersResponse
import io.github.amichne.kast.api.KastDiagnosticsResponse
import io.github.amichne.kast.api.KastReferencesResponse
import io.github.amichne.kast.api.KastRenameResponse
import io.github.amichne.kast.api.KastResolveResponse
import io.github.amichne.kast.api.KastScaffoldResponse
import io.github.amichne.kast.api.KastWorkspaceFilesResponse
import io.github.amichne.kast.api.KastWriteAndValidateResponse
import kotlinx.serialization.json.Json

/**
 * Serializes wrapper responses via their sealed base interface serializer,
 * which ensures the `type` discriminator field is included in output.
 */
internal object SkillWrapperSerializer {

    /**
     * Encodes a wrapper response to JSON, using the sealed base serializer
     * so the `type` discriminator is emitted (e.g., `"type": "RESOLVE_SUCCESS"`).
     */
    fun encode(json: Json, name: SkillWrapperName, response: Any): String = when (name) {
        SkillWrapperName.RESOLVE ->
            json.encodeToString(KastResolveResponse.serializer(), response as KastResolveResponse)
        SkillWrapperName.REFERENCES ->
            json.encodeToString(KastReferencesResponse.serializer(), response as KastReferencesResponse)
        SkillWrapperName.CALLERS ->
            json.encodeToString(KastCallersResponse.serializer(), response as KastCallersResponse)
        SkillWrapperName.DIAGNOSTICS ->
            json.encodeToString(KastDiagnosticsResponse.serializer(), response as KastDiagnosticsResponse)
        SkillWrapperName.RENAME ->
            json.encodeToString(KastRenameResponse.serializer(), response as KastRenameResponse)
        SkillWrapperName.SCAFFOLD ->
            json.encodeToString(KastScaffoldResponse.serializer(), response as KastScaffoldResponse)
        SkillWrapperName.WORKSPACE_FILES ->
            json.encodeToString(KastWorkspaceFilesResponse.serializer(), response as KastWorkspaceFilesResponse)
        SkillWrapperName.WRITE_AND_VALIDATE ->
            json.encodeToString(KastWriteAndValidateResponse.serializer(), response as KastWriteAndValidateResponse)
    }
}
