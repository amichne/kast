package io.github.amichne.kast.cli.eval.adapter

import io.github.amichne.kast.cli.eval.EvalCheck
import io.github.amichne.kast.cli.eval.EvalMetric
import io.github.amichne.kast.cli.eval.EvalSeverity
import io.github.amichne.kast.cli.eval.EvalStatus
import io.github.amichne.kast.cli.eval.RawBudget
import io.github.amichne.kast.cli.eval.SkillDescriptor
import io.github.amichne.kast.cli.eval.SkillTarget
import io.github.amichne.kast.cli.skill.SkillWrapperName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.math.ceil

/**
 * Scans a skill directory and produces a [SkillDescriptor] containing
 * budget estimates, structural checks, and completeness metrics.
 */
internal class SkillAdapter(private val skillDir: Path) {
    private val evalsDir = skillDir.resolve("evals")
    private val historyDir = skillDir.resolve("history")
    private val catalogPath = evalsDir.resolve("catalog.json")
    private val painPointsPath = evalsDir.resolve("pain_points.jsonl")
    private val evalFilesDir = evalsDir.resolve("files")
    private val progressionPath = historyDir.resolve("progression.json")
    private val wrapperOpenApiPath = skillDir.resolve("references/wrapper-openapi.yaml")
    private val routingScriptPath = skillDir.resolve("scripts/build-routing-corpus.py")
    private val routingReferencePath = skillDir.resolve("references/routing-improvement.md")
    private val json = Json { ignoreUnknownKeys = true }

    fun scan(): SkillDescriptor {
        val checks = mutableListOf<EvalCheck>()
        val metrics = mutableListOf<EvalMetric>()
        val behaviorCorpus = validateBehaviorCorpus()
        val routingCorpus = validateRoutingCorpus()
        val routingMeasurements = analyzeRoutingMeasurements()

        checks += checkSkillMdExists()
        checks += checkLegacyWrappersRemoved()
        checks += checkSkillMdHasTriggerPhrases()
        checks += checkWrapperOpenApiExists()
        checks += checkRoutingImprovementAssets()
        checks += checkBehaviorEvalCorpus(behaviorCorpus)
        checks += checkRoutingEvalCorpus(routingCorpus)
        checks += checkFailureModeCoverage(behaviorCorpus, routingCorpus)
        checks += checkCostMeasurementCoverage(routingMeasurements)
        checks += checkWrapperCompleteness()

        val budget = estimateBudget()
        metrics += budgetMetrics(budget)
        metrics += corpusMetrics(behaviorCorpus, routingCorpus, routingMeasurements)

        return SkillDescriptor(
            target = SkillTarget(kind = "skill", name = skillDir.name, path = skillDir.toString()),
            checks = checks,
            metrics = metrics,
            budget = budget,
        )
    }

    // --- Budget estimation ---

    internal fun estimateBudget(): RawBudget {
        val skillMd = skillDir.resolve("SKILL.md")
        val triggerTokens = estimateTokens(skillMd)

        val agentsDir = skillDir.resolve("agents")
        val invokeTokens = sumTokensInDir(agentsDir)

        val refsDir = skillDir.resolve("references")
        val deferredTokens = sumTokensInDir(refsDir)

        return RawBudget(
            triggerTokens = triggerTokens,
            invokeTokens = invokeTokens,
            deferredTokens = deferredTokens,
        )
    }

    // --- Structural checks ---

    private fun checkSkillMdExists(): EvalCheck {
        val exists = skillDir.resolve("SKILL.md").exists()
        return EvalCheck(
            id = "structural-skill-md-exists",
            category = "structural",
            severity = EvalSeverity.ERROR,
            status = if (exists) EvalStatus.PASS else EvalStatus.FAIL,
            message = if (exists) "SKILL.md found" else "SKILL.md missing",
            remediation = if (!exists) "Create SKILL.md at skill root" else null,
        )
    }

    private fun checkLegacyWrappersRemoved(): EvalCheck {
        val legacyPaths = buildList {
            listOf("kast.md", "explore.md", "plan.md", "edit.md").forEach {
                add(skillDir.resolve("agents/$it"))
            }
        }
        val present = legacyPaths
            .filter(Path::exists)
            .map { skillDir.relativize(it).toString() }
            .sorted()
        return EvalCheck(
            id = "structural-legacy-artifacts-removed",
            category = "structural",
            severity = EvalSeverity.WARNING,
            status = if (present.isEmpty()) EvalStatus.PASS else EvalStatus.WARN,
            message = if (present.isEmpty()) {
                "Legacy shell-wrapper and sub-agent artifacts are absent"
            } else {
                "Legacy artifacts still present: ${present.joinToString()}"
            },
            remediation = if (present.isNotEmpty()) {
                "Remove the legacy artifacts and rely on native `kast_*` tools, keeping `kast skill` only as an explicit fallback path"
            } else {
                null
            },
        )
    }

    private fun checkRoutingImprovementAssets(): EvalCheck {
        val catalogExists = catalogPath.exists()
        val painPointsExists = painPointsPath.exists()
        val evalFilesExist = evalFilesDir.exists()
        val progressionExists = progressionPath.exists()
        val routingScriptExists = routingScriptPath.exists()
        val routingReferenceExists = routingReferencePath.exists()
        val allPresent = catalogExists && painPointsExists && evalFilesExist &&
            progressionExists && routingScriptExists && routingReferenceExists
        return EvalCheck(
            id = "structural-routing-improvement-assets",
            category = "structural",
            severity = EvalSeverity.WARNING,
            status = if (allPresent) EvalStatus.PASS else EvalStatus.WARN,
            message = listOf(
                "catalog=$catalogExists",
                "pain-points=$painPointsExists",
                "eval-files=$evalFilesExist",
                "progression=$progressionExists",
                "routing-script=$routingScriptExists",
                "routing-reference=$routingReferenceExists",
            ).joinToString(),
            remediation = if (!allPresent) {
                "Add evals/catalog.json, evals/pain_points.jsonl, evals/files/, " +
                    "history/progression.json, scripts/build-routing-corpus.py, and " +
                    "references/routing-improvement.md"
            } else {
                null
            },
        )
    }

    private fun checkBehaviorEvalCorpus(corpus: CorpusValidation): EvalCheck = EvalCheck(
        id = "corpus-behavior-evals-valid",
        category = "corpus",
        severity = EvalSeverity.ERROR,
        status = if (corpus.isValid) EvalStatus.PASS else EvalStatus.FAIL,
        message = corpus.message("behavior"),
        remediation = if (corpus.isValid) {
            null
        } else {
            "Keep evals/catalog.json non-empty with suite=`behavior`, and give each behavior case prompt, expected_output, expectations, and failure_mode."
        },
    )

    private fun checkRoutingEvalCorpus(corpus: CorpusValidation): EvalCheck = EvalCheck(
        id = "corpus-routing-evals-valid",
        category = "corpus",
        severity = EvalSeverity.ERROR,
        status = if (corpus.isValid) EvalStatus.PASS else EvalStatus.FAIL,
        message = corpus.message("routing"),
        remediation = if (corpus.isValid) {
            null
        } else {
            "Keep evals/catalog.json non-empty with suite=`routing`, and give each routing case prompt, expected_skill, expected_route, allowed_ops, forbidden_ops, and failure_mode."
        },
    )

    private fun checkFailureModeCoverage(
        behaviorCorpus: CorpusValidation,
        routingCorpus: CorpusValidation,
    ): EvalCheck {
        val failureModes = behaviorCorpus.failureModes + routingCorpus.failureModes
        if (!behaviorCorpus.isParseable || !routingCorpus.isParseable) {
            return EvalCheck(
                id = "corpus-failure-mode-coverage",
                category = "corpus",
                severity = EvalSeverity.WARNING,
                status = EvalStatus.WARN,
                message = "Failure-mode coverage unavailable until both eval corpora parse cleanly",
                remediation = "Fix eval corpus parse errors before relying on failure-mode coverage",
            )
        }
        val missing = REQUIRED_FAILURE_MODES - failureModes
        return EvalCheck(
            id = "corpus-failure-mode-coverage",
            category = "corpus",
            severity = EvalSeverity.WARNING,
            status = if (missing.isEmpty()) EvalStatus.PASS else EvalStatus.WARN,
            message = if (missing.isEmpty()) {
                "Failure-mode coverage spans ${failureModes.size}/${REQUIRED_FAILURE_MODES.size} required categories"
            } else {
                "Failure-mode coverage missing ${missing.joinToString()} (${failureModes.size}/${REQUIRED_FAILURE_MODES.size} covered)"
            },
            remediation = if (missing.isEmpty()) {
                null
            } else {
                "Promote durable transcript failures into evals/catalog.json and tag them with suite plus failure_mode."
            },
        )
    }

    private fun checkCostMeasurementCoverage(measurements: RoutingMeasurements): EvalCheck {
        if (!measurements.isParseable) {
            return EvalCheck(
                id = "corpus-cost-measurement-coverage",
                category = "corpus",
                severity = EvalSeverity.WARNING,
                status = EvalStatus.WARN,
                message = "Cost-of-insight coverage unavailable until routing evals parse cleanly",
                remediation = "Fix routing eval parse errors before relying on discoverability or haystack-reduction measurements",
            )
        }
        val missing = REQUIRED_MEASUREMENT_DIMENSIONS - measurements.measurementDimensions
        return EvalCheck(
            id = "corpus-cost-measurement-coverage",
            category = "corpus",
            severity = EvalSeverity.WARNING,
            status = if (missing.isEmpty()) EvalStatus.PASS else EvalStatus.WARN,
            message = buildString {
                if (missing.isEmpty()) {
                    append("Cost-of-insight coverage spans ")
                    append("${measurements.measurementDimensions.size}/${REQUIRED_MEASUREMENT_DIMENSIONS.size} criteria")
                } else {
                    append("Cost-of-insight coverage missing ${missing.joinToString()} ")
                    append("(${measurements.measurementDimensions.size}/${REQUIRED_MEASUREMENT_DIMENSIONS.size} covered)")
                }
                append("; native-route=${measurements.nativeRouteCount}/${measurements.entryCount}")
                append(", native-ops=${measurements.nativeAllowedOpCount}/${measurements.entryCount}")
                append(", guardrails=${measurements.genericGuardrailCount}/${measurements.entryCount}")
            },
            remediation = if (missing.isEmpty()) {
                null
            } else {
                "Tag routing evals with measurement_dimensions covering discoverability, iteration_reduction, haystack_reduction, and generic_tool_avoidance."
            },
        )
    }

    private fun checkSkillMdHasTriggerPhrases(): EvalCheck {
        val skillMd = skillDir.resolve("SKILL.md")
        if (!skillMd.exists()) {
            return EvalCheck(
                id = "structural-trigger-phrases",
                category = "structural",
                severity = EvalSeverity.ERROR,
                status = EvalStatus.FAIL,
                message = "Cannot check trigger phrases: SKILL.md missing",
            )
        }
        val content = skillMd.readText()
        // Look for a section about triggers or common trigger-phrase patterns
        val hasTriggers = content.contains("trigger", ignoreCase = true) ||
            content.contains("Trigger phrases", ignoreCase = true) ||
            content.contains("description:", ignoreCase = true)
        return EvalCheck(
            id = "structural-trigger-phrases",
            category = "structural",
            severity = EvalSeverity.WARNING,
            status = if (hasTriggers) EvalStatus.PASS else EvalStatus.WARN,
            message = if (hasTriggers) "SKILL.md contains trigger/description metadata" else "SKILL.md missing trigger phrases",
            remediation = if (!hasTriggers) "Add trigger phrases or a description section to SKILL.md" else null,
        )
    }

    private fun checkWrapperOpenApiExists(): EvalCheck {
        val exists = wrapperOpenApiPath.exists()
        return EvalCheck(
            id = "structural-openapi-exists",
            category = "structural",
            severity = EvalSeverity.WARNING,
            status = if (exists) EvalStatus.PASS else EvalStatus.WARN,
            message = if (exists) "wrapper-openapi.yaml found" else "wrapper-openapi.yaml missing",
            remediation = if (!exists) {
                "Generate references/wrapper-openapi.yaml from wrapper contracts"
            } else {
                null
            },
        )
    }

    private fun checkWrapperCompleteness(): List<EvalCheck> {
        val skillMdText = skillDir.resolve("SKILL.md").takeIf(Path::exists)?.readText().orEmpty()
        val openApiText = wrapperOpenApiPath.takeIf(Path::exists)?.readText().orEmpty()
        return SkillWrapperName.entries.map { wrapper ->
            val command = "kast skill ${wrapper.cliName}"
            val nativeTool = wrapper.nativeToolName
            val documentedInSkillMd = skillMdText.contains(command) || skillMdText.contains(nativeTool)
            val documentedInOpenApi = openApiText.contains(command)
            EvalCheck(
                id = "completeness-wrapper-${wrapper.cliName}",
                category = "completeness",
                severity = EvalSeverity.INFO,
                status = if (documentedInSkillMd && documentedInOpenApi) EvalStatus.PASS else EvalStatus.WARN,
                message = "Wrapper ${wrapper.cliName}: skillMd=$documentedInSkillMd, openApi=$documentedInOpenApi",
                remediation = if (documentedInSkillMd && documentedInOpenApi) {
                    null
                } else {
                    "Document `$nativeTool` (or `$command`) in SKILL.md and `$command` in references/wrapper-openapi.yaml"
                },
            )
        }
    }

    // --- Token estimation ---

    internal fun estimateTokens(file: Path): Int {
        if (!file.exists() || !file.isRegularFile()) return 0
        return ceil(file.readText().length / 4.0).toInt()
    }

    private fun sumTokensInDir(dir: Path): Int {
        if (!dir.exists()) return 0
        return Files.list(dir).use { stream ->
            stream.filter(Path::isRegularFile).toList()
        }.sumOf { estimateTokens(it) }
    }

    private fun budgetMetrics(budget: RawBudget): List<EvalMetric> = listOf(
        EvalMetric(id = "budget-trigger-tokens", category = "budget", value = budget.triggerTokens.toDouble(), unit = "tokens"),
        EvalMetric(id = "budget-invoke-tokens", category = "budget", value = budget.invokeTokens.toDouble(), unit = "tokens"),
        EvalMetric(id = "budget-deferred-tokens", category = "budget", value = budget.deferredTokens.toDouble(), unit = "tokens"),
    )

    private fun corpusMetrics(
        behaviorCorpus: CorpusValidation,
        routingCorpus: CorpusValidation,
        routingMeasurements: RoutingMeasurements,
    ): List<EvalMetric> {
        val failureModeCount = (behaviorCorpus.failureModes + routingCorpus.failureModes).size
        return listOf(
            EvalMetric(
                id = "corpus-behavior-eval-count",
                category = "corpus",
                value = behaviorCorpus.entryCount.toDouble(),
                unit = "cases",
            ),
            EvalMetric(
                id = "corpus-routing-eval-count",
                category = "corpus",
                value = routingCorpus.entryCount.toDouble(),
                unit = "cases",
            ),
            EvalMetric(
                id = "corpus-failure-mode-count",
                category = "corpus",
                value = failureModeCount.toDouble(),
                unit = "categories",
            ),
            EvalMetric(
                id = "corpus-cost-measurement-dimension-count",
                category = "corpus",
                value = routingMeasurements.measurementDimensions.size.toDouble(),
                unit = "criteria",
            ),
            EvalMetric(
                id = "corpus-routing-native-route-count",
                category = "corpus",
                value = routingMeasurements.nativeRouteCount.toDouble(),
                unit = "cases",
            ),
            EvalMetric(
                id = "corpus-routing-native-op-count",
                category = "corpus",
                value = routingMeasurements.nativeAllowedOpCount.toDouble(),
                unit = "cases",
            ),
            EvalMetric(
                id = "corpus-routing-generic-guardrail-count",
                category = "corpus",
                value = routingMeasurements.genericGuardrailCount.toDouble(),
                unit = "cases",
            ),
        )
    }

    private fun validateBehaviorCorpus(): CorpusValidation =
        validateCatalogSuite(
            suite = "behavior",
            validator = { entry, index ->
                buildList {
                    if (entry.stringField("prompt") == null) add("entry ${index + 1} missing prompt")
                    if (entry.stringField("expected_output") == null) add("entry ${index + 1} missing expected_output")
                    if (entry.stringArrayField("expectations").isEmpty()) add("entry ${index + 1} missing expectations")
                    val failureMode = entry.stringField("failure_mode")
                    if (failureMode == null) {
                        add("entry ${index + 1} missing failure_mode")
                    } else if (failureMode !in REQUIRED_FAILURE_MODES) {
                        add("entry ${index + 1} has unknown failure_mode `$failureMode`")
                    }
                }
            },
        )

    private fun validateRoutingCorpus(): CorpusValidation =
        validateCatalogSuite(
            suite = "routing",
            validator = { entry, index ->
                buildList {
                    if (entry.stringField("prompt") == null) add("entry ${index + 1} missing prompt")
                    val expectedSkill = entry.stringField("expected_skill")
                    val expectedRoute = entry.stringField("expected_route")
                    val allowedOps = entry.stringArrayField("allowed_ops").toSet()
                    val forbiddenOps = entry.stringArrayField("forbidden_ops").toSet()
                    val failureMode = entry.stringField("failure_mode")
                    if (expectedSkill == null) add("entry ${index + 1} missing expected_skill")
                    if (expectedRoute == null) add("entry ${index + 1} missing expected_route")
                    if (allowedOps.isEmpty()) add("entry ${index + 1} missing allowed_ops")
                    if (forbiddenOps.isEmpty()) add("entry ${index + 1} missing forbidden_ops")
                    if (expectedSkill != null && expectedSkill != "kast") {
                        add("entry ${index + 1} expected_skill must be `kast`")
                    }
                    if (expectedRoute != null && expectedRoute != REQUIRED_EXPECTED_ROUTE) {
                        add("entry ${index + 1} expected_route must be `$REQUIRED_EXPECTED_ROUTE`")
                    }
                    val legacyAllowedOps = allowedOps.filter { it.startsWith("kast skill ") }
                    if (legacyAllowedOps.isNotEmpty()) {
                        add("entry ${index + 1} uses legacy allowed_ops: ${legacyAllowedOps.joinToString()}")
                    }
                    val overlap = allowedOps intersect forbiddenOps
                    if (overlap.isNotEmpty()) {
                        add("entry ${index + 1} overlaps allowed_ops and forbidden_ops: ${overlap.joinToString()}")
                    }
                    val measurementDimensions = entry.stringArrayField("measurement_dimensions").toSet()
                    val unknownMeasurementDimensions = measurementDimensions - REQUIRED_MEASUREMENT_DIMENSIONS
                    if (unknownMeasurementDimensions.isNotEmpty()) {
                        add(
                            "entry ${index + 1} has unknown measurement_dimensions ${unknownMeasurementDimensions.joinToString()}",
                        )
                    }
                    if (failureMode == null) {
                        add("entry ${index + 1} missing failure_mode")
                    } else if (failureMode !in REQUIRED_FAILURE_MODES) {
                        add("entry ${index + 1} has unknown failure_mode `$failureMode`")
                    }
                }
            },
        )

    private fun analyzeRoutingMeasurements(): RoutingMeasurements {
        val catalogLoad = loadCatalogCases()
        if (catalogLoad.parseError != null) {
            return RoutingMeasurements(parseError = catalogLoad.parseError)
        }
        val entries = catalogLoad.cases.filter { it.stringField("suite") == "routing" }
        if (entries.isEmpty()) {
            return RoutingMeasurements(parseError = "${skillDir.relativize(catalogPath)} contains no routing cases")
        }
        val measurementDimensions = mutableSetOf<String>()
        var nativeRouteCount = 0
        var nativeAllowedOpCount = 0
        var genericGuardrailCount = 0
        entries.forEach { entry ->
            val allowedOps = entry.stringArrayField("allowed_ops")
            val forbiddenOps = entry.stringArrayField("forbidden_ops")
            measurementDimensions += entry.stringArrayField("measurement_dimensions")
            if (entry.stringField("expected_route") == REQUIRED_EXPECTED_ROUTE) {
                nativeRouteCount += 1
            }
            if (allowedOps.any(::isNativeToolOp)) {
                nativeAllowedOpCount += 1
            }
            if (forbiddenOps.any(::isGenericKotlinGuardrail)) {
                genericGuardrailCount += 1
            }
        }
        return RoutingMeasurements(
            entryCount = entries.size,
            measurementDimensions = measurementDimensions,
            nativeRouteCount = nativeRouteCount,
            nativeAllowedOpCount = nativeAllowedOpCount,
            genericGuardrailCount = genericGuardrailCount,
        )
    }

    private fun validateCatalogSuite(
        suite: String,
        validator: (JsonObject, Int) -> List<String>,
    ): CorpusValidation {
        val catalogLoad = loadCatalogCases()
        if (catalogLoad.parseError != null) {
            return CorpusValidation(parseError = catalogLoad.parseError)
        }
        val entries = catalogLoad.cases.filter { it.stringField("suite") == suite }
        if (entries.isEmpty()) {
            return CorpusValidation(
                entryCount = 0,
                issues = listOf("${skillDir.relativize(catalogPath)} contains no $suite cases"),
            )
        }
        val issues = mutableListOf<String>()
        val failureModes = mutableSetOf<String>()
        entries.forEachIndexed { index, entry ->
            issues += validator(entry, index)
            entry.stringField("failure_mode")?.let(failureModes::add)
        }
        return CorpusValidation(
            entryCount = entries.size,
            failureModes = failureModes,
            issues = issues.distinct(),
        )
    }

    private fun loadCatalogCases(): CatalogLoad {
        if (!catalogPath.exists()) {
            return CatalogLoad(parseError = "${skillDir.relativize(catalogPath)} missing")
        }
        val root = try {
            json.parseToJsonElement(catalogPath.readText()).jsonObject
        } catch (exception: Exception) {
            return CatalogLoad(parseError = "${skillDir.relativize(catalogPath)} invalid JSON: ${exception.message}")
        }
        val rawCases = try {
            root["cases"]?.jsonArray ?: JsonArray(emptyList())
        } catch (_: Exception) {
            return CatalogLoad(parseError = "${skillDir.relativize(catalogPath)} has non-array `cases`")
        }
        val cases = mutableListOf<JsonObject>()
        rawCases.forEachIndexed { index, entry ->
            val obj = entry as? JsonObject
            if (obj == null) {
                return CatalogLoad(parseError = "${skillDir.relativize(catalogPath)} entry ${index + 1} is not an object")
            }
            cases += obj
        }
        return CatalogLoad(cases = cases)
    }

    private fun JsonObject.stringField(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.stringArrayField(name: String): List<String> =
        (this[name] as? JsonArray)?.mapNotNull { element ->
            runCatching { element.jsonPrimitive.contentOrNull?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        }.orEmpty()

    private data class CorpusValidation(
        val entryCount: Int = 0,
        val failureModes: Set<String> = emptySet(),
        val issues: List<String> = emptyList(),
        val parseError: String? = null,
    ) {
        val isParseable: Boolean get() = parseError == null
        val isValid: Boolean get() = parseError == null && issues.isEmpty() && entryCount > 0

        fun message(label: String): String = when {
            parseError != null -> parseError
            issues.isNotEmpty() -> "$label eval corpus invalid: ${issues.joinToString(limit = 4, separator = "; ")}"
            else -> "$label eval corpus valid ($entryCount cases)"
        }
    }

    private data class CatalogLoad(
        val cases: List<JsonObject> = emptyList(),
        val parseError: String? = null,
    )

    private data class RoutingMeasurements(
        val entryCount: Int = 0,
        val measurementDimensions: Set<String> = emptySet(),
        val nativeRouteCount: Int = 0,
        val nativeAllowedOpCount: Int = 0,
        val genericGuardrailCount: Int = 0,
        val parseError: String? = null,
    ) {
        val isParseable: Boolean get() = parseError == null
    }

    private fun isNativeToolOp(operation: String): Boolean = operation in REQUIRED_NATIVE_TOOL_NAMES

    private fun isGenericKotlinGuardrail(operation: String): Boolean = operation in GENERIC_KOTLIN_TOOL_OPS

    private companion object {
        private const val REQUIRED_EXPECTED_ROUTE = "native-kast-tools"
        private val REQUIRED_FAILURE_MODES = setOf(
            "trigger_miss",
            "routing_bypass",
            "initialization_friction",
            "maintenance_thrash",
            "schema_request",
            "relative_path",
            "ambiguous_symbol",
            "schema_response",
            "mutation_abandonment",
            "failure_response_ignored",
        )
        private val REQUIRED_MEASUREMENT_DIMENSIONS = setOf(
            "discoverability",
            "iteration_reduction",
            "haystack_reduction",
            "generic_tool_avoidance",
        )
        private val REQUIRED_NATIVE_TOOL_NAMES = SkillWrapperName.entries.map { it.nativeToolName }.toSet()
        private val GENERIC_KOTLIN_TOOL_OPS = setOf("grep", "rg", "view", "edit", "create", "apply_patch", "sed")
    }
}
