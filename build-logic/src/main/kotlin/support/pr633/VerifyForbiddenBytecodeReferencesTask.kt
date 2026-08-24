package support.pr633

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Proof transition: `(BytecodeAuthorityPolicy.Raw, List<ByteArray>) ->
 * BytecodeAuthorityVerification`.
 *
 * Establishes that declared callers exist and cannot directly or transitively reach forbidden
 * owners. [BytecodeAuthorityPolicy.Failure] and [BytecodeAuthorityViolation] form the closed
 * expected failures. Gradle properties and class files are the only raw extraction boundary;
 * this task alone projects rejection to a build exception.
 */
@CacheableTask
abstract class VerifyForbiddenBytecodeReferencesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ConfigurableFileCollection

    @get:Input
    abstract val callerInternalNamePrefixes: ListProperty<String>

    @get:Input
    abstract val forbiddenOwnerPrefixes: ListProperty<String>

    @get:Input
    abstract val ruleName: Property<String>

    @TaskAction
    fun verify() {
        val rawPolicy = BytecodeAuthorityPolicy.Raw(
            callers = callerInternalNamePrefixes.get(),
            forbiddenOwners = forbiddenOwnerPrefixes.get(),
            ruleName = ruleName.get(),
        )
        val policy = when (val refinement = BytecodeAuthorityPolicy.refine(rawPolicy)) {
            is BytecodeAuthorityPolicy.Refinement.Refined -> refinement.policy
            is BytecodeAuthorityPolicy.Refinement.Rejected -> throw GradleException(
                "invalid bytecode authority policy: ${refinement.failures.sortedBy(Enum<*>::name)}",
            )
        }
        val classes = classDirectories.asFileTree
            .matching { include("**/*.class") }
            .files
            .sortedBy { it.path }
            .map { it.readBytes() }

        when (val verification = verifyBytecodeAuthority(policy, classes)) {
            BytecodeAuthorityVerification.Accepted -> Unit
            is BytecodeAuthorityVerification.Rejected -> throw IllegalStateException(
                buildString {
                    appendLine("${policy.ruleName.value} rejected forbidden bytecode references:")
                    verification.violations.sortedBy(BytecodeAuthorityViolation::display).forEach {
                        appendLine("  ${it.display()}")
                    }
                },
            )
        }
    }
}

internal class BytecodeAuthorityPolicy private constructor(
    val callers: Set<JvmInternalNamePrefix>,
    val forbiddenOwners: Set<JvmInternalNamePrefix>,
    val ruleName: AuthorityRuleName,
) {
    data class Raw(
        val callers: List<String>,
        val forbiddenOwners: List<String>,
        val ruleName: String,
    )

    enum class Failure {
        NO_CALLERS,
        BLANK_CALLER,
        NON_CANONICAL_CALLER,
        INVALID_CALLER,
        NO_FORBIDDEN_OWNERS,
        BLANK_FORBIDDEN_OWNER,
        NON_CANONICAL_FORBIDDEN_OWNER,
        INVALID_FORBIDDEN_OWNER,
        BLANK_RULE_NAME,
        NON_CANONICAL_RULE_NAME,
    }

    sealed interface Refinement {
        data class Refined(val policy: BytecodeAuthorityPolicy) : Refinement
        data class Rejected(
            val firstFailure: Failure,
            val additionalFailures: Set<Failure>,
        ) : Refinement {
            val failures: Set<Failure> = setOf(firstFailure) + additionalFailures
        }
    }

    @JvmInline
    value class JvmInternalNamePrefix private constructor(val value: String) {
        sealed interface Refinement {
            data class Refined(val prefix: JvmInternalNamePrefix) : Refinement
            data object Blank : Refinement
            data object NonCanonical : Refinement
            data object Invalid : Refinement
        }

        companion object {
            /**
             * Proof transition: `String -> JvmInternalNamePrefix.Refinement`.
             *
             * [Refinement.Refined] proves a non-blank, trim-canonical supported JVM internal-name
             * or package prefix. Blank, non-canonical, and invalid grammar are the closed expected
             * failures. Raw strings enter only from [Raw].
             */
            fun refine(value: String): Refinement = when {
                value.isBlank() -> Refinement.Blank
                value != value.trim() -> Refinement.NonCanonical
                !value.matches(SUPPORTED_INTERNAL_NAME_PREFIX) -> Refinement.Invalid
                else -> Refinement.Refined(JvmInternalNamePrefix(value))
            }

            private val SUPPORTED_INTERNAL_NAME_PREFIX = Regex(
                "(?:[A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*/)*" +
                    "(?:[A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*)?",
            )
        }
    }

    @JvmInline
    value class AuthorityRuleName private constructor(val value: String) {
        sealed interface Refinement {
            data class Refined(val name: AuthorityRuleName) : Refinement
            data object Blank : Refinement
            data object NonCanonical : Refinement
        }

        companion object {
            /**
             * Proof transition: `String -> AuthorityRuleName.Refinement`.
             *
             * [Refinement.Refined] proves a non-blank, trim-canonical diagnostic rule name.
             * [Refinement.Blank] and [Refinement.NonCanonical] are the closed expected failures.
             * Raw strings enter only from [Raw].
             */
            fun refine(value: String): Refinement = when {
                value.isBlank() -> Refinement.Blank
                value != value.trim() -> Refinement.NonCanonical
                else -> Refinement.Refined(AuthorityRuleName(value))
            }
        }
    }

    companion object {
        /**
         * Proof transition: [Raw] `->` [Refinement].
         *
         * [Refinement.Refined] proves non-empty caller and forbidden-owner sets whose entries are
         * trim-canonical supported JVM internal-name prefixes, plus a non-blank trim-canonical
         * diagnostic rule name. [Failure] is the closed expected failure type. Raw strings are
         * extracted only at the Gradle task boundary.
         */
        fun refine(raw: Raw): Refinement {
            val failures = mutableSetOf<Failure>()
            val callers = mutableSetOf<JvmInternalNamePrefix>()
            val forbiddenOwners = mutableSetOf<JvmInternalNamePrefix>()
            if (raw.callers.isEmpty()) failures += Failure.NO_CALLERS
            raw.callers.forEach { value ->
                when (val refinement = JvmInternalNamePrefix.refine(value)) {
                    JvmInternalNamePrefix.Refinement.Blank -> failures += Failure.BLANK_CALLER
                    JvmInternalNamePrefix.Refinement.NonCanonical ->
                        failures += Failure.NON_CANONICAL_CALLER
                    JvmInternalNamePrefix.Refinement.Invalid -> failures += Failure.INVALID_CALLER
                    is JvmInternalNamePrefix.Refinement.Refined -> callers += refinement.prefix
                }
            }
            if (raw.forbiddenOwners.isEmpty()) failures += Failure.NO_FORBIDDEN_OWNERS
            raw.forbiddenOwners.forEach { value ->
                when (val refinement = JvmInternalNamePrefix.refine(value)) {
                    JvmInternalNamePrefix.Refinement.Blank ->
                        failures += Failure.BLANK_FORBIDDEN_OWNER
                    JvmInternalNamePrefix.Refinement.NonCanonical ->
                        failures += Failure.NON_CANONICAL_FORBIDDEN_OWNER
                    JvmInternalNamePrefix.Refinement.Invalid ->
                        failures += Failure.INVALID_FORBIDDEN_OWNER
                    is JvmInternalNamePrefix.Refinement.Refined ->
                        forbiddenOwners += refinement.prefix
                }
            }
            val refinedRuleName = when (val refinement = AuthorityRuleName.refine(raw.ruleName)) {
                AuthorityRuleName.Refinement.Blank -> {
                    failures += Failure.BLANK_RULE_NAME
                    AuthorityRuleName.Refinement.Blank
                }
                AuthorityRuleName.Refinement.NonCanonical -> {
                    failures += Failure.NON_CANONICAL_RULE_NAME
                    AuthorityRuleName.Refinement.NonCanonical
                }
                is AuthorityRuleName.Refinement.Refined -> refinement
            }
            return when (refinedRuleName) {
                AuthorityRuleName.Refinement.Blank -> Refinement.Rejected(
                    failures.first(),
                    failures.drop(1).toSet(),
                )
                AuthorityRuleName.Refinement.NonCanonical -> Refinement.Rejected(
                    failures.first(),
                    failures.drop(1).toSet(),
                )
                is AuthorityRuleName.Refinement.Refined -> if (failures.isEmpty()) {
                    Refinement.Refined(
                        BytecodeAuthorityPolicy(
                            callers = callers,
                            forbiddenOwners = forbiddenOwners,
                            ruleName = refinedRuleName.name,
                        ),
                    )
                } else {
                    Refinement.Rejected(failures.first(), failures.drop(1).toSet())
                }
            }
        }
    }
}
