package conventions.jsoncontracts

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Validated, exact allowances. No wildcard, suppression, or baseline-writing operation exists. */
internal class JsonContractBaseline
private constructor(val allowances: Map<JsonContractFingerprint, JsonContractAllowance>) {
    sealed interface Admission {
        data class Accepted(val baseline: JsonContractBaseline) : Admission

        data class Rejected(val reason: JsonContractBaselineFailure) : Admission
    }

    companion object {
        fun parse(text: String): Admission {
            val document =
                try {
                    Json.decodeFromString<JsonContractBaselineDocument>(text)
                } catch (_: SerializationException) {
                    return Admission.Rejected(JsonContractBaselineFailure.INVALID_DOCUMENT)
                } catch (_: IllegalArgumentException) {
                    return Admission.Rejected(JsonContractBaselineFailure.INVALID_DOCUMENT)
                }
            if (document.version != 1) return Admission.Rejected(JsonContractBaselineFailure.UNSUPPORTED_VERSION)
            val admitted = linkedMapOf<JsonContractFingerprint, JsonContractAllowance>()
            for (entry in document.allowances) {
                val fingerprint = entry.fingerprint
                when {
                    !validJsonContractPath(fingerprint.path) ->
                        return Admission.Rejected(JsonContractBaselineFailure.INVALID_PATH)
                    fingerprint.scope.isBlank() ||
                        fingerprint.scope.length > 4096 ||
                        fingerprint.scope.any(Char::isISOControl) ->
                        return Admission.Rejected(JsonContractBaselineFailure.INVALID_SCOPE)
                    !fingerprint.sha256.matches(Regex("[0-9a-f]{64}")) ->
                        return Admission.Rejected(JsonContractBaselineFailure.INVALID_HASH)
                    entry.count <= 0 -> return Admission.Rejected(JsonContractBaselineFailure.INVALID_COUNT)
                    entry.justification.isBlank() ||
                        entry.justification.length > 1024 ||
                        entry.justification.any(Char::isISOControl) ->
                        return Admission.Rejected(JsonContractBaselineFailure.INVALID_JUSTIFICATION)
                    fingerprint in admitted ->
                        return Admission.Rejected(JsonContractBaselineFailure.DUPLICATE_FINGERPRINT)
                }
                admitted[fingerprint] = entry
            }
            return Admission.Accepted(JsonContractBaseline(admitted.toMap()))
        }
    }
}

internal fun validJsonContractPath(path: String): Boolean =
    (path.endsWith(".kt") || path.endsWith(".kts")) &&
        path.none { it.isISOControl() || it == '\\' || it == ':' } &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }
