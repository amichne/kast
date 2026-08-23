package support.pr633

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class RepositoryPath private constructor(
    normalized: NormalizedRepositoryLocation,
) {
    val value: String = normalized.value
    val role: PathRole = when {
        value == "AGENTS.md" -> PathRole.Guide("")
        value.endsWith("/AGENTS.md") -> PathRole.Guide(value.removeSuffix("AGENTS.md"))
        else -> PathRole.NonGuide
    }

    override fun equals(other: Any?): Boolean = other is RepositoryPath && value == other.value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        /**
         * Proof transition: `String -> RepositoryPath`.
         *
         * Establishes one normalized repository-relative file path and its guide role. Expected
         * failure is the closed `RepositoryLocationResult.Rejected`; raw extraction is permitted
         * only by path admission and reporting boundaries.
         */
        fun parse(value: String): RepositoryLocationResult<RepositoryPath> {
            if (value.endsWith("/")) {
                return RepositoryLocationResult.Rejected(RepositoryLocationFailure.EXPECTED_FILE)
            }
            return when (val normalized = NormalizedRepositoryLocation.parse(value)) {
                is RepositoryLocationResult.Rejected -> normalized
                is RepositoryLocationResult.Parsed -> RepositoryLocationResult.Parsed(
                    RepositoryPath(normalized.value),
                )
            }
        }
    }
}

internal sealed interface PathRole {
    data object NonGuide : PathRole

    data class Guide(val directoryPrefix: String) : PathRole
}

private class RepositoryPrefix private constructor(
    normalized: NormalizedRepositoryLocation,
) {
    val value: String = "${normalized.value}/"

    override fun equals(other: Any?): Boolean = other is RepositoryPrefix && value == other.value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        /**
         * Proof transition: `String -> RepositoryPrefix`.
         *
         * Establishes one normalized repository-relative directory prefix. Expected failure is the
         * closed `RepositoryLocationResult.Rejected`; raw extraction remains inside path admission.
         */
        fun parse(value: String): RepositoryLocationResult<RepositoryPrefix> {
            if (!value.endsWith("/")) {
                return RepositoryLocationResult.Rejected(RepositoryLocationFailure.EXPECTED_PREFIX)
            }
            return when (val normalized = NormalizedRepositoryLocation.parse(value.dropLast(1))) {
                is RepositoryLocationResult.Rejected -> normalized
                is RepositoryLocationResult.Parsed -> RepositoryLocationResult.Parsed(
                    RepositoryPrefix(normalized.value),
                )
            }
        }
    }
}

internal sealed interface RepositoryLocationResult<out T> {
    data class Parsed<T>(val value: T) : RepositoryLocationResult<T>

    data class Rejected(val reason: RepositoryLocationFailure) : RepositoryLocationResult<Nothing>
}

private class NormalizedRepositoryLocation private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> NormalizedRepositoryLocation`.
         *
         * Establishes normalized repository-relative path segments and carries the admitted value
         * into the file or prefix constructor. Expected failure is the closed
         * `RepositoryLocationResult.Rejected`; raw extraction remains inside this parser.
         */
        fun parse(raw: String): RepositoryLocationResult<NormalizedRepositoryLocation> {
            if (raw.isBlank() || raw != raw.trim()) {
                return RepositoryLocationResult.Rejected(RepositoryLocationFailure.BLANK_OR_PADDED)
            }
            if (raw.startsWith("/") || '\\' in raw) {
                return RepositoryLocationResult.Rejected(
                    RepositoryLocationFailure.NOT_REPOSITORY_RELATIVE,
                )
            }
            val segments = raw.split('/')
            if (segments.any { it.isBlank() || it == "." || it == ".." }) {
                return RepositoryLocationResult.Rejected(RepositoryLocationFailure.NOT_NORMALIZED)
            }
            return RepositoryLocationResult.Parsed(NormalizedRepositoryLocation(raw))
        }
    }
}

private sealed interface GuideAuthority {
    data object ExplicitOnly : GuideAuthority

    data object ExplicitAndAncestors : GuideAuthority
}

private sealed interface DirectAdmission {
    data object Admitted : DirectAdmission

    data object Outside : DirectAdmission
}

internal data class RawPathAdmissionAuthorities(
    val program: String,
    val pathPolicy: String,
)

internal class ChangedPathAdmissionPolicy private constructor(
    private val exact: Set<RepositoryPath>,
    private val prefixes: Set<RepositoryPrefix>,
    private val forbiddenPrefixes: Set<RepositoryPrefix>,
    private val guideAuthority: GuideAuthority,
) {
    /**
     * Proof carried by the successful `ChangedPathAdmissionPolicy.admit` transition.
     *
     * Construction is private to the policy owner, so normalized paths cannot be presented as
     * admitted without passing both the deny-list and task-scope checks.
     */
    internal class AdmittedChangedPaths private constructor(
        private val paths: List<RepositoryPath>,
    ) {
        /**
         * Proof transition: `AdmittedChangedPaths -> List<String>` at the report boundary.
         *
         * Projects only already admitted paths in canonical order. Raw extraction is permitted
         * only while writing the deterministic stack report.
         */
        fun sortedValues(): List<String> = paths.map(RepositoryPath::value)

        internal companion object {
            /**
             * Proof transition: `ChangedPathAdmissionPolicy + List<String> -> AdmittedChangedPaths`.
             *
             * Establishes normalized, non-forbidden paths admitted by KTP633-010 through
             * KTP633-070, including only ancestor guides authorized by the program marker.
             * Expected failure is a closed `StackVerificationFailure`; construction of the proof
             * is confined to this transition owner.
             */
            fun refine(
                policy: ChangedPathAdmissionPolicy,
                rawPaths: List<String>,
            ): StackVerificationResult<AdmittedChangedPaths> {
                val paths = linkedSetOf<RepositoryPath>()
                rawPaths.forEach { raw ->
                    when (val parsed = RepositoryPath.parse(raw)) {
                        is RepositoryLocationResult.Parsed -> paths += parsed.value
                        is RepositoryLocationResult.Rejected -> return StackVerificationResult.Rejected(
                            StackVerificationFailure.InvalidChangedPath(raw, parsed.reason),
                        )
                    }
                }
                val forbidden = paths.filter { path ->
                    policy.forbiddenPrefixes.any { prefix -> path.value.startsWith(prefix.value) }
                }
                if (forbidden.isNotEmpty()) {
                    return StackVerificationResult.Rejected(
                        StackVerificationFailure.ForbiddenChangedPaths(
                            forbidden.first(),
                            forbidden.drop(1),
                        ),
                    )
                }
                val admittedNonGuides = paths.filter { path ->
                    path.role is PathRole.NonGuide &&
                        policy.directAdmission(path) is DirectAdmission.Admitted
                }
                val outside = paths.filter { path ->
                    when (policy.directAdmission(path)) {
                        DirectAdmission.Admitted -> false
                        DirectAdmission.Outside -> when (val role = path.role) {
                            PathRole.NonGuide -> true
                            is PathRole.Guide -> when (policy.guideAuthority) {
                                GuideAuthority.ExplicitOnly -> true
                                GuideAuthority.ExplicitAndAncestors -> admittedNonGuides.none { changed ->
                                    changed.value.startsWith(role.directoryPrefix)
                                }
                            }
                        }
                    }
                }
                return if (outside.isEmpty()) {
                    StackVerificationResult.Proven(
                        AdmittedChangedPaths(paths.sortedBy(RepositoryPath::value)),
                    )
                } else {
                    StackVerificationResult.Rejected(
                        StackVerificationFailure.OutsideTaskScopes(
                            outside.first(),
                            outside.drop(1),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Proof transition: `List<String> -> AdmittedChangedPaths`.
     *
     * Establishes normalized, non-forbidden paths admitted by KTP633-010 through KTP633-070,
     * including only ancestor guides authorized by the program marker. Expected failure is a
     * closed `StackVerificationFailure`; raw path extraction is permitted only in the Git input
     * and report boundaries.
     */
    fun admit(rawPaths: List<String>): StackVerificationResult<AdmittedChangedPaths> =
        AdmittedChangedPaths.refine(this, rawPaths)

    /**
     * Proof transition: `RepositoryPath -> DirectAdmission`.
     *
     * Establishes whether one path is covered directly by an exact or prefix task scope. The
     * closed `DirectAdmission.Outside` state preserves absence of direct authority so guide
     * ancestry can be considered separately; raw values stay inside this policy owner.
     */
    private fun directAdmission(path: RepositoryPath): DirectAdmission =
        if (path in exact || prefixes.any { prefix -> path.value.startsWith(prefix.value) }) {
            DirectAdmission.Admitted
        } else {
            DirectAdmission.Outside
        }

    companion object {
        /**
         * Proof transition: `RawPathAdmissionAuthorities -> ChangedPathAdmissionPolicy`.
         *
         * Establishes the exact/prefix union of KTP633-010 through KTP633-070, the program's
         * ancestor-guide capability, and the separately sourced forbidden-prefix deny-list.
         * Expected failure is a closed `StackVerificationFailure`; raw JSON is extracted only in
         * this configuration boundary. Construction of the refined policy remains private.
         */
        fun parse(
            raw: RawPathAdmissionAuthorities,
        ): StackVerificationResult<ChangedPathAdmissionPolicy> {
            val program = try {
                Json.parseToJsonElement(raw.program).jsonObject
            } catch (failure: RuntimeException) {
                return StackVerificationResult.Rejected(
                    StackVerificationFailure.MalformedProgram(failure.toString()),
                )
            }
            val policy = try {
                Json.parseToJsonElement(raw.pathPolicy).jsonObject
            } catch (failure: RuntimeException) {
                return StackVerificationResult.Rejected(
                    StackVerificationFailure.MalformedPathPolicy(failure.toString()),
                )
            }
            val programId: String
            val scopedTasks: List<Pair<String, List<String>>>
            try {
                programId = program.getValue("programId").jsonPrimitive.content
                scopedTasks = program.getValue("tasks").jsonArray.map { element ->
                    val task = element.jsonObject
                    task.getValue("id").jsonPrimitive.content to task
                }.filter { (id) -> id in REQUIRED_SCOPED_TASK_IDS }.map { (id, task) ->
                    id to
                        task.getValue("allowedWrites").jsonArray.map { it.jsonPrimitive.content }
                }
            } catch (failure: RuntimeException) {
                return StackVerificationResult.Rejected(
                    StackVerificationFailure.MalformedProgram(failure.toString()),
                )
            }
            val policyProgramId: String
            val forbiddenValues: List<String>
            try {
                policyProgramId = policy.getValue("programId").jsonPrimitive.content
                forbiddenValues = policy.getValue("forbiddenPrefixes").jsonArray.map {
                    it.jsonPrimitive.content
                }
            } catch (failure: RuntimeException) {
                return StackVerificationResult.Rejected(
                    StackVerificationFailure.MalformedPathPolicy(failure.toString()),
                )
            }
            if (programId != policyProgramId) {
                return StackVerificationResult.Rejected(
                    StackVerificationFailure.ProgramPolicyMismatch(programId, policyProgramId),
                )
            }
            val observedIds = scopedTasks.map(Pair<String, List<String>>::first)
            val duplicateIds = observedIds.groupingBy { id -> id }.eachCount()
                .filterValues { count -> count > 1 }.keys
            if (duplicateIds.isNotEmpty()) {
                return StackVerificationResult.Rejected(
                    StackVerificationFailure.DuplicateScopedTasks(
                        duplicateIds.first(),
                        duplicateIds.drop(1).toSet(),
                    ),
                )
            }
            val missingIds = REQUIRED_SCOPED_TASK_IDS - observedIds.toSet()
            if (missingIds.isNotEmpty()) {
                return StackVerificationResult.Rejected(
                    StackVerificationFailure.MissingScopedTasks(
                        missingIds.first(),
                        missingIds.drop(1).toSet(),
                    ),
                )
            }
            val exact = linkedSetOf<RepositoryPath>()
            val prefixes = linkedSetOf<RepositoryPrefix>()
            var guideAuthority: GuideAuthority = GuideAuthority.ExplicitOnly
            scopedTasks.forEach { (taskId, allowedWrites) ->
                allowedWrites.forEach { value ->
                    if (value == ANCESTOR_GUIDE_AUTHORITY) {
                        guideAuthority = GuideAuthority.ExplicitAndAncestors
                    } else if (value.endsWith("/")) {
                        when (val parsed = RepositoryPrefix.parse(value)) {
                            is RepositoryLocationResult.Parsed -> prefixes += parsed.value
                            is RepositoryLocationResult.Rejected -> return StackVerificationResult.Rejected(
                                StackVerificationFailure.InvalidAllowedWrite(taskId, value, parsed.reason),
                            )
                        }
                    } else {
                        when (val parsed = RepositoryPath.parse(value)) {
                            is RepositoryLocationResult.Parsed -> exact += parsed.value
                            is RepositoryLocationResult.Rejected -> return StackVerificationResult.Rejected(
                                StackVerificationFailure.InvalidAllowedWrite(taskId, value, parsed.reason),
                            )
                        }
                    }
                }
            }
            val forbidden = linkedSetOf<RepositoryPrefix>()
            forbiddenValues.forEach { value ->
                when (val parsed = RepositoryPrefix.parse(value)) {
                    is RepositoryLocationResult.Parsed -> forbidden += parsed.value
                    is RepositoryLocationResult.Rejected -> return StackVerificationResult.Rejected(
                        StackVerificationFailure.InvalidForbiddenPrefix(value, parsed.reason),
                    )
                }
            }
            return StackVerificationResult.Proven(
                ChangedPathAdmissionPolicy(exact, prefixes, forbidden, guideAuthority),
            )
        }
    }
}

private const val ANCESTOR_GUIDE_AUTHORITY =
    "ancestor AGENTS.md files required for every admitted changed path"
private val REQUIRED_SCOPED_TASK_IDS = (1..7).mapTo(linkedSetOf()) { index ->
    "KTP633-0${index}0"
}
