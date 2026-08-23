package support.pr633

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Proof transition: `(Set<String>, Set<String>, List<ByteArray>, List<String>) ->
 * TopologyContractApiVerification` through [TopologyContractApiPolicyAdmission],
 * [CompiledTopologyContractApiProjection], and [CheckedTopologyContractAbiAdmission].
 *
 * [TopologyContractApiVerification.Verified] establishes that the logical public JVM API
 * projection exactly matches the checked manifest and that no zero-budget graph class or method
 * is present. Synthetic and Kotlin-inlined implementation classes, synthetic members, and the JVM
 * class initializer are excluded from that projection. Policy, projection, manifest, and
 * verification rejections are closed expected failures. The Gradle task boundary alone projects
 * them to build errors; raw names, class bytes, and manifest lines do not leave this task.
 */
@CacheableTask
abstract class VerifyTopologyContractApiTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifestFile: RegularFileProperty

    @get:Input
    abstract val forbiddenClassSimpleNames: SetProperty<String>

    @get:Input
    abstract val forbiddenPublicMethodNames: SetProperty<String>

    @TaskAction
    fun verify() {
        val policy = when (
            val admission = TopologyContractApiPolicy.fromBoundary(
                forbiddenClassSimpleNames.get(),
                forbiddenPublicMethodNames.get(),
            )
        ) {
            is TopologyContractApiPolicyAdmission.Admitted -> admission.policy
            is TopologyContractApiPolicyAdmission.Rejected -> throw IllegalStateException(
                "topology API zero-budget policy is incomplete: ${admission.failures.joinToString()}",
            )
        }
        val compiled = when (
            val projection = CompiledTopologyContractApi.project(
                policy,
                classDirectories.asFileTree
                    .matching { include("**/*.class") }
                    .files
                    .sortedBy { it.path }
                    .map { it.readBytes() },
            )
        ) {
            CompiledTopologyContractApiProjection.EmptyClassfiles -> throw IllegalStateException(
                "topology contract API projection received no classfiles",
            )
            is CompiledTopologyContractApiProjection.DuplicateClasses -> throw IllegalStateException(
                "topology contract API projection received duplicate classes: " +
                    projection.classes.joinToString { it.value },
            )
            is CompiledTopologyContractApiProjection.Projected -> projection.api
        }
        val checked = CheckedTopologyContractAbi.parse(manifestFile.get().asFile.readLines())
        when (val verification = verifyTopologyContractApi(compiled, checked)) {
            TopologyContractApiVerification.Verified -> Unit
            is TopologyContractApiVerification.Rejected -> throw IllegalStateException(
                buildString {
                    appendLine(":topology:contract public ABI differs from the checked manifest:")
                    verification.problems.forEach { appendLine("  ${it.display()}") }
                    appendLine("Observed manifest entries:")
                    verification.observed.entries.forEach { appendLine(it.value) }
                },
            )
        }
    }
}

internal class TopologyContractApiPolicy private constructor(
    val forbiddenClassSimpleNames: Set<JvmSimpleClassName>,
    val forbiddenMethodNames: Set<JvmMethodName>,
) {
    companion object {
        /**
         * Proof transition: `(Set<String>, Set<String>) -> TopologyContractApiPolicyAdmission`.
         *
         * An admitted policy proves both forbidden class and method inventories are non-empty and
         * wraps every name as a JVM identity. [TopologyContractApiPolicyAdmission.Rejected] is the
         * closed expected failure. Raw strings are extracted only at the Gradle task boundary.
         */
        fun fromBoundary(
            classes: Set<String>,
            methods: Set<String>,
        ): TopologyContractApiPolicyAdmission {
            val failures = mutableSetOf<TopologyContractApiPolicyFailure>()
            if (classes.isEmpty()) failures += TopologyContractApiPolicyFailure.EmptyForbiddenClasses
            if (methods.isEmpty()) failures += TopologyContractApiPolicyFailure.EmptyForbiddenMethods
            val refinedClasses = mutableSetOf<JvmSimpleClassName>()
            classes.forEach { value ->
                when (val refinement = JvmSimpleClassName.refinePolicy(value)) {
                    JvmPolicyNameRefinement.Blank ->
                        failures += TopologyContractApiPolicyFailure.BlankForbiddenClass
                    JvmPolicyNameRefinement.Invalid ->
                        failures += TopologyContractApiPolicyFailure.InvalidForbiddenClass
                    JvmPolicyNameRefinement.NonCanonical ->
                        failures += TopologyContractApiPolicyFailure.NonCanonicalForbiddenClass
                    is JvmPolicyNameRefinement.Refined -> refinedClasses += refinement.name
                }
            }
            val refinedMethods = mutableSetOf<JvmMethodName>()
            methods.forEach { value ->
                when (val refinement = JvmMethodName.refinePolicy(value)) {
                    JvmPolicyNameRefinement.Blank ->
                        failures += TopologyContractApiPolicyFailure.BlankForbiddenMethod
                    JvmPolicyNameRefinement.Invalid ->
                        failures += TopologyContractApiPolicyFailure.InvalidForbiddenMethod
                    JvmPolicyNameRefinement.NonCanonical ->
                        failures += TopologyContractApiPolicyFailure.NonCanonicalForbiddenMethod
                    is JvmPolicyNameRefinement.Refined -> refinedMethods += refinement.name
                }
            }
            return if (failures.isEmpty()) {
                TopologyContractApiPolicyAdmission.Admitted(TopologyContractApiPolicy(
                    forbiddenClassSimpleNames = refinedClasses,
                    forbiddenMethodNames = refinedMethods,
                ))
            } else {
                TopologyContractApiPolicyAdmission.Rejected(failures.first(), failures.drop(1))
            }
        }
    }
}

internal enum class TopologyContractApiPolicyFailure {
    EmptyForbiddenClasses,
    EmptyForbiddenMethods,
    BlankForbiddenClass,
    NonCanonicalForbiddenClass,
    InvalidForbiddenClass,
    BlankForbiddenMethod,
    NonCanonicalForbiddenMethod,
    InvalidForbiddenMethod,
}

internal sealed interface TopologyContractApiPolicyAdmission {
    data class Admitted(val policy: TopologyContractApiPolicy) : TopologyContractApiPolicyAdmission
    data class Rejected(
        val firstFailure: TopologyContractApiPolicyFailure,
        val additionalFailures: List<TopologyContractApiPolicyFailure>,
    ) : TopologyContractApiPolicyAdmission {
        val failures: List<TopologyContractApiPolicyFailure> = listOf(firstFailure) + additionalFailures
    }
}

@JvmInline
internal value class TopologyContractAbiEntry(val value: String)

@JvmInline
internal value class JvmClassName private constructor(val value: String) {
    val simpleNames: List<JvmSimpleClassName>
        get() = value.substringAfterLast('/')
            .split('$')
            .filter(String::isNotEmpty)
            .map(JvmSimpleClassName::fromClassfile)

    companion object {
        /**
         * Boundary transition: ASM classfile internal name `String -> JvmClassName`.
         *
         * ASM has already parsed the JVM classfile grammar. This wrapper preserves that identity;
         * raw extraction is permitted only in classfile visitors and descriptor projections.
         */
        fun fromClassfile(value: String): JvmClassName = JvmClassName(value)
    }
}

@JvmInline
internal value class JvmSimpleClassName private constructor(val value: String) {
    companion object {
        /**
         * Boundary transition: ASM classfile simple name `String -> JvmSimpleClassName`.
         *
         * ASM has already parsed the owning class identity. Raw extraction is permitted only while
         * deriving the simple-name projection from [JvmClassName].
         */
        fun fromClassfile(value: String): JvmSimpleClassName = JvmSimpleClassName(value)

        /**
         * Proof transition: `String -> JvmPolicyNameRefinement<JvmSimpleClassName>`.
         *
         * A refined name proves canonical trim equality and JVM simple-name grammar. Blank,
         * padded, and invalid values are closed failures. Raw strings enter from Gradle policy.
         */
        fun refinePolicy(value: String): JvmPolicyNameRefinement<JvmSimpleClassName> =
            refineJvmPolicyName(value) { JvmSimpleClassName(it) }
    }
}

@JvmInline
internal value class JvmMethodName private constructor(val value: String) {
    companion object {
        /**
         * Boundary transition: ASM classfile method name `String -> JvmMethodName`.
         *
         * ASM has already parsed the method table. Raw extraction is permitted only in the method
         * visitor; Gradle policy names must instead pass [refinePolicy].
         */
        fun fromClassfile(value: String): JvmMethodName = JvmMethodName(value)

        /**
         * Proof transition: `String -> JvmPolicyNameRefinement<JvmMethodName>`.
         *
         * A refined name proves canonical trim equality and JVM method-name grammar. Blank,
         * padded, and invalid values are closed failures. Raw strings enter from Gradle policy.
         */
        fun refinePolicy(value: String): JvmPolicyNameRefinement<JvmMethodName> =
            refineJvmPolicyName(value) { JvmMethodName(it) }
    }
}

internal sealed interface JvmPolicyNameRefinement<out T> {
    data class Refined<T>(val name: T) : JvmPolicyNameRefinement<T>
    data object Blank : JvmPolicyNameRefinement<Nothing>
    data object NonCanonical : JvmPolicyNameRefinement<Nothing>
    data object Invalid : JvmPolicyNameRefinement<Nothing>
}

/**
 * Proof transition: `String -> JvmPolicyNameRefinement<T>`.
 *
 * A refined result proves non-blank trim equality and the admitted JVM policy-name grammar.
 * [JvmPolicyNameRefinement] carries the closed blank, non-canonical, and invalid failures. Raw
 * strings enter only from Gradle policy properties.
 */
private fun <T> refineJvmPolicyName(
    value: String,
    construct: (String) -> T,
): JvmPolicyNameRefinement<T> = when {
    value.isBlank() -> JvmPolicyNameRefinement.Blank
    value != value.trim() -> JvmPolicyNameRefinement.NonCanonical
    !value.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*")) -> JvmPolicyNameRefinement.Invalid
    else -> JvmPolicyNameRefinement.Refined(construct(value))
}

internal data class JvmMethodIdentity(
    val owner: JvmClassName,
    val name: JvmMethodName,
    val descriptor: String,
) {
    fun display(): String = "${owner.value}#${name.value}$descriptor"
}

/**
 * Proof transition: `(CompiledTopologyContractApi, CheckedTopologyContractAbiAdmission) ->
 * TopologyContractApiVerification`.
 *
 * Establishes exact ABI equality and absence of forbidden graph API. Rejection carries the finite
 * [TopologyContractApiProblem] set. Raw manifest lines are admitted before this call.
 */
private fun verifyTopologyContractApi(
    compiled: CompiledTopologyContractApi,
    checked: CheckedTopologyContractAbiAdmission,
): TopologyContractApiVerification {
    val problems = buildList {
        if (compiled.forbiddenClasses.isNotEmpty()) {
            add(TopologyContractApiProblem.ForbiddenClasses(
                NonEmptyEvidence.from(
                    compiled.forbiddenClasses.first(),
                    compiled.forbiddenClasses.drop(1),
                ),
            ))
        }
        if (compiled.forbiddenMethods.isNotEmpty()) {
            add(TopologyContractApiProblem.ForbiddenMethods(
                NonEmptyEvidence.from(
                    compiled.forbiddenMethods.first(),
                    compiled.forbiddenMethods.drop(1),
                ),
            ))
        }
        when (checked) {
            CheckedTopologyContractAbiAdmission.EmptyManifest ->
                add(TopologyContractApiProblem.EmptyManifest)
            is CheckedTopologyContractAbiAdmission.Rejected ->
                add(TopologyContractApiProblem.DuplicateManifestEntries(checked.duplicates))
            is CheckedTopologyContractAbiAdmission.Admitted -> {
                val expected = checked.abi.entries.toSet()
                val observed = compiled.entries.toSet()
                val missing = (expected - observed).sortedBy(TopologyContractAbiEntry::value)
                val unexpected = (observed - expected).sortedBy(TopologyContractAbiEntry::value)
                if (missing.isNotEmpty()) {
                    add(TopologyContractApiProblem.MissingEntries(
                        NonEmptyEvidence.from(missing.first(), missing.drop(1)),
                    ))
                }
                if (unexpected.isNotEmpty()) {
                    add(TopologyContractApiProblem.UnexpectedEntries(
                        NonEmptyEvidence.from(unexpected.first(), unexpected.drop(1)),
                    ))
                }
            }
        }
    }
    return if (problems.isEmpty()) {
        TopologyContractApiVerification.Verified
    } else {
        TopologyContractApiVerification.Rejected(problems.first(), problems.drop(1), compiled)
    }
}
