package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
internal data class AuthorityVerificationDocument(
    val schemaVersion: Int,
    val gateId: String,
    val baseRevision: String,
    val exactHead: String,
    val programFingerprint: String,
    val requirementFingerprint: String,
    val sourceDigests: Map<String, String>,
)

@Serializable
internal enum class AuthorityNegativeCase {
    STALE_EXACT_HEAD,
    CHANGED_REQUIREMENT_FINGERPRINT,
    OMITTED_CONTRADICTION,
    OBSOLETE_TWO_PROCESS_ASSUMPTION,
}

@Serializable
internal data class AuthorityNegativeProofDocument(
    val schemaVersion: Int,
    val gateId: String,
    val rejectedCases: List<AuthorityNegativeCase>,
)

@Serializable
private data class ProgramAuthorityJsonDocument(
    val schemaVersion: Int,
    val baseRevision: String,
    val exactHead: String,
    val programFingerprint: String,
    val requirementFingerprint: String,
    val sourceArtifacts: List<AuthoritySourceJsonDocument>,
    val contradictions: List<String>,
    val obsoleteAssumptions: List<String>,
    val unprovenClaims: List<String>,
)

@Serializable
private data class AuthoritySourceJsonDocument(
    val id: String,
    val path: String,
    val sha256: String,
)

internal val authorityEvidenceJson = Json {
    ignoreUnknownKeys = false
    prettyPrint = true
}

/**
 * Proof transition: authority JSON bytes -> parsed `ProgramAuthorityDocument` ->
 * `AdmittedProgramAuthority`.
 *
 * Generated serializers establish the closed JSON shape. Enum names and duplicate entries refine
 * before domain admission. Expected failure returns [ProgramAuthorityAdmission.Rejected]. Raw JSON
 * is extracted only at this Gradle boundary.
 */
internal fun admitProgramAuthority(
    rawDocument: String,
    expectation: ProgramAuthorityExpectation,
    observeSource: (AuthoritySourcePath) -> AuthoritySourceObservation,
): ProgramAuthorityAdmission {
    val raw = try {
        authorityEvidenceJson.decodeFromString(ProgramAuthorityJsonDocument.serializer(), rawDocument)
    } catch (_: SerializationException) {
        return malformedAuthority()
    }
    val enumSets = when (val refined = refineAuthorityEnumSets(raw)) {
        is AuthorityEnumSetsRefinement.Complete -> refined
        AuthorityEnumSetsRefinement.Rejected -> return malformedAuthority()
    }
    val document = ProgramAuthorityDocument(
        raw.schemaVersion,
        raw.baseRevision,
        raw.exactHead,
        raw.programFingerprint,
        raw.requirementFingerprint,
        raw.sourceArtifacts.map { AuthoritySourceDocument(it.id, it.path, it.sha256) },
        enumSets.contradictions,
        enumSets.obsolete,
        enumSets.unproven,
    )
    return admitProgramAuthority(document, expectation, observeSource)
}

internal fun encodeProgramAuthorityDocument(document: ProgramAuthorityDocument): String {
    val raw = ProgramAuthorityJsonDocument(
        document.schemaVersion,
        document.baseRevision,
        document.exactHead,
        document.programFingerprint,
        document.requirementFingerprint,
        document.sourceArtifacts.map { AuthoritySourceJsonDocument(it.id, it.path, it.sha256) },
        document.contradictions.map { it.name }.sorted(),
        document.obsoleteAssumptions.map { it.name }.sorted(),
        document.unprovenClaims.map { it.name }.sorted(),
    )
    return authorityEvidenceJson.encodeToString(ProgramAuthorityJsonDocument.serializer(), raw) + "\n"
}

private fun malformedAuthority() =
    ProgramAuthorityAdmission.Rejected(AuthorityAdmissionFailure.MalformedDocument)

private sealed interface EnumSetRefinement<out E> {
    data class Complete<E>(val values: Set<E>) : EnumSetRefinement<E>
    data object Rejected : EnumSetRefinement<Nothing>
}

private sealed interface AuthorityEnumSetsRefinement {
    data class Complete(
        val contradictions: Set<AuthorityContradiction>,
        val obsolete: Set<ObsoleteAuthorityAssumption>,
        val unproven: Set<UnprovenAuthorityClaim>,
    ) : AuthorityEnumSetsRefinement

    data object Rejected : AuthorityEnumSetsRefinement
}

/**
 * Proof transition: raw authority enum lists -> closed enum sets.
 *
 * Unknown or duplicate names return `Rejected`. Raw names stay at the JSON boundary.
 */
private fun refineAuthorityEnumSets(
    raw: ProgramAuthorityJsonDocument,
): AuthorityEnumSetsRefinement {
    val contradictions = when (
        val value = enumSetOrRejected<AuthorityContradiction>(raw.contradictions)
    ) {
        is EnumSetRefinement.Complete -> value.values
        EnumSetRefinement.Rejected -> return AuthorityEnumSetsRefinement.Rejected
    }
    val obsolete = when (
        val value = enumSetOrRejected<ObsoleteAuthorityAssumption>(raw.obsoleteAssumptions)
    ) {
        is EnumSetRefinement.Complete -> value.values
        EnumSetRefinement.Rejected -> return AuthorityEnumSetsRefinement.Rejected
    }
    val unproven = when (
        val value = enumSetOrRejected<UnprovenAuthorityClaim>(raw.unprovenClaims)
    ) {
        is EnumSetRefinement.Complete -> value.values
        EnumSetRefinement.Rejected -> return AuthorityEnumSetsRefinement.Rejected
    }
    return AuthorityEnumSetsRefinement.Complete(contradictions, obsolete, unproven)
}

/**
 * Proof transition: raw enum-name list -> unique `Set<E>`.
 *
 * Unknown or duplicate names return `Rejected`. Raw names stay at the JSON boundary.
 */
private inline fun <reified E : Enum<E>> enumSetOrRejected(
    values: List<String>,
): EnumSetRefinement<E> {
    if (values.toSet().size != values.size) return EnumSetRefinement.Rejected
    val entriesByName = enumValues<E>().associateBy { it.name }
    val parsed = LinkedHashSet<E>()
    values.forEach { value -> parsed += entriesByName[value] ?: return EnumSetRefinement.Rejected }
    return EnumSetRefinement.Complete(parsed)
}

internal enum class AuthorityGateProofFailure {
    MALFORMED_DOCUMENT,
    GATE_ID_MISMATCH,
    REJECTED_CASE_SET_MISMATCH,
    BASE_REVISION_MISMATCH,
    EXACT_HEAD_MISMATCH,
    PROGRAM_FINGERPRINT_MISMATCH,
    REQUIREMENT_FINGERPRINT_MISMATCH,
    SOURCE_DIGESTS_MISMATCH,
}

internal sealed interface AuthorityGateProofObservation {
    data class NegativeComplete(
        val rejectedCases: Set<AuthorityNegativeCase>,
    ) : AuthorityGateProofObservation

    data class VerificationComplete(
        val admittedSourceIds: Set<AuthoritySourceId>,
    ) : AuthorityGateProofObservation

    data class Rejected(val failure: AuthorityGateProofFailure) : AuthorityGateProofObservation
}

/**
 * Proof transition: KVP-001 RED report JSON -> `AuthorityGateProofObservation.NegativeComplete`.
 *
 * Establishes the exact gate identity and exhaustive negative-case set. Expected malformed or
 * mismatched evidence returns [AuthorityGateProofObservation.Rejected]. Raw JSON is retained only
 * at this Gradle evidence boundary.
 */
internal fun observeAuthorityNegativeProof(
    rawDocument: String,
): AuthorityGateProofObservation {
    val document = try {
        authorityEvidenceJson.decodeFromString(
            AuthorityNegativeProofDocument.serializer(),
            rawDocument,
        )
    } catch (_: SerializationException) {
        return AuthorityGateProofObservation.Rejected(
            AuthorityGateProofFailure.MALFORMED_DOCUMENT,
        )
    }
    if (document.schemaVersion != 1 || document.gateId != "KVP-001-RED") {
        return AuthorityGateProofObservation.Rejected(
            AuthorityGateProofFailure.GATE_ID_MISMATCH,
        )
    }
    if (
        document.rejectedCases.size != AuthorityNegativeCase.entries.size ||
        document.rejectedCases.toSet() != AuthorityNegativeCase.entries.toSet()
    ) {
        return AuthorityGateProofObservation.Rejected(
            AuthorityGateProofFailure.REJECTED_CASE_SET_MISMATCH,
        )
    }
    return AuthorityGateProofObservation.NegativeComplete(
        document.rejectedCases.toSet(),
    )
}

/**
 * Proof transition: KVP-001 GREEN report JSON plus admitted authority identities ->
 * `AuthorityGateProofObservation.VerificationComplete`.
 *
 * Establishes the exact gate, revisions, fingerprints, and source-digest set. Expected malformed
 * or mismatched evidence returns [AuthorityGateProofObservation.Rejected]. Raw JSON is retained
 * only at this Gradle evidence boundary.
 */
internal fun observeAuthorityVerificationProof(
    rawDocument: String,
    baseRevision: String,
    exactHead: String,
    programFingerprint: String,
    requirementFingerprint: String,
    sourceDigests: Map<String, String>,
): AuthorityGateProofObservation {
    val document = try {
        authorityEvidenceJson.decodeFromString(
            AuthorityVerificationDocument.serializer(),
            rawDocument,
        )
    } catch (_: SerializationException) {
        return AuthorityGateProofObservation.Rejected(
            AuthorityGateProofFailure.MALFORMED_DOCUMENT,
        )
    }
    val failure = when {
        document.schemaVersion != 1 || document.gateId != "KVP-001-GREEN" -> {
            AuthorityGateProofFailure.GATE_ID_MISMATCH
        }
        document.baseRevision != baseRevision -> AuthorityGateProofFailure.BASE_REVISION_MISMATCH
        document.exactHead != exactHead -> AuthorityGateProofFailure.EXACT_HEAD_MISMATCH
        document.programFingerprint != programFingerprint -> {
            AuthorityGateProofFailure.PROGRAM_FINGERPRINT_MISMATCH
        }
        document.requirementFingerprint != requirementFingerprint -> {
            AuthorityGateProofFailure.REQUIREMENT_FINGERPRINT_MISMATCH
        }
        document.sourceDigests != sourceDigests -> {
            AuthorityGateProofFailure.SOURCE_DIGESTS_MISMATCH
        }
        else -> null
    }
    return if (failure == null) {
        AuthorityGateProofObservation.VerificationComplete(
            document.sourceDigests.keys.mapTo(mutableSetOf(), ::AuthoritySourceId),
        )
    } else {
        AuthorityGateProofObservation.Rejected(failure)
    }
}
