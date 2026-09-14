package io.github.amichne.kast.cli

import kotlinx.serialization.json.JsonObject

private const val GRADLE_CONTRACT = "io.github.amichne.kast.distribution.contract.gradle"
private const val BOOTSTRAP_PHASES = 7
private const val MAXIMUM_JAVA_FEATURE = 99

internal fun processDiagnosticSchema(): JsonObject =
    unionSchema(
        objectSchema(
            ServerSchemaProperty("status", constantSchema("rejected", "Boundary outcome.")),
            ServerSchemaProperty("boundary", textSchema("Rejected process boundary.")),
            ServerSchemaProperty("reason", textSchema("Closed boundary rejection reason.")),
        ),
        objectSchema(
            ServerSchemaProperty("status", constantSchema("rejected", "Boundary outcome.")),
            ServerSchemaProperty("boundary", textSchema("Rejected process boundary.")),
            ServerSchemaProperty("reason", textSchema("Closed boundary rejection reason.")),
            ServerSchemaProperty("diagnostic", textSchema("Usage diagnostic.")),
        ),
        objectSchema(
            ServerSchemaProperty("status", constantSchema("rejected", "Boundary outcome.")),
            ServerSchemaProperty("boundary", constantSchema("runtime", "Rejected process boundary.")),
            ServerSchemaProperty("reason", textSchema("Closed boundary rejection reason.")),
            ServerSchemaProperty("details", ideDescriptorFailureSchema()),
        ),
        objectSchema(
            ServerSchemaProperty("status", constantSchema("rejected", "Boundary outcome.")),
            ServerSchemaProperty("boundary", constantSchema("runtime", "Rejected process boundary.")),
            ServerSchemaProperty("reason", textSchema("Closed boundary rejection reason.")),
            ServerSchemaProperty("bootstrap", runtimeBootstrapDiagnosticSchema()),
        ),
    )

internal fun runtimeBootstrapDiagnosticSchema(): JsonObject =
    unionSchema(
        objectSchema(ServerSchemaProperty("state", constantSchema("unavailable", "Bootstrap state."))),
        objectSchema(ServerSchemaProperty("state", constantSchema("invalid", "Bootstrap state."))),
        objectSchema(
            ServerSchemaProperty("state", constantSchema("starting", "Bootstrap state.")),
            ServerSchemaProperty("attemptId", uuidSchema("Bootstrap attempt identity.")),
            ServerSchemaProperty("phase", textSchema("Current bootstrap phase.")),
            ServerSchemaProperty("completedPhases", integerSchema(0, BOOTSTRAP_PHASES, "Completed bootstrap phases.")),
            ServerSchemaProperty("totalPhases", integerSchema(1, BOOTSTRAP_PHASES, "Total bootstrap phases.")),
            ServerSchemaProperty("gradleJvm", gradleJvmSelectionObservationSchema()),
        ),
        objectSchema(
            ServerSchemaProperty("state", constantSchema("ready", "Bootstrap state.")),
            ServerSchemaProperty("attemptId", uuidSchema("Bootstrap attempt identity.")),
            ServerSchemaProperty("gradleJvm", gradleJvmSelectionObservationSchema()),
            ServerSchemaProperty("phase", constantSchema("ready", "Completed bootstrap phase.")),
            ServerSchemaProperty(
                "completedPhases",
                integerSchema(BOOTSTRAP_PHASES, BOOTSTRAP_PHASES, "Completed bootstrap phases."),
            ),
            ServerSchemaProperty(
                "totalPhases",
                integerSchema(BOOTSTRAP_PHASES, BOOTSTRAP_PHASES, "Total bootstrap phases."),
            ),
        ),
        objectSchema(
            ServerSchemaProperty("state", constantSchema("rejected", "Bootstrap state.")),
            ServerSchemaProperty("attemptId", uuidSchema("Bootstrap attempt identity.")),
            ServerSchemaProperty("phase", textSchema("Rejected bootstrap phase.")),
            ServerSchemaProperty("completedPhases", integerSchema(0, BOOTSTRAP_PHASES, "Completed bootstrap phases.")),
            ServerSchemaProperty("totalPhases", integerSchema(1, BOOTSTRAP_PHASES, "Total bootstrap phases.")),
            ServerSchemaProperty("cause", textSchema("Closed bootstrap rejection reason.")),
            ServerSchemaProperty("correctiveAction", textSchema("Bounded corrective action.")),
            ServerSchemaProperty("gradleJvm", gradleJvmSelectionObservationSchema()),
        ),
    )

internal fun gradleJvmSelectionObservationSchema(): JsonObject =
    unionSchema(
        objectSchema(
            ServerSchemaProperty(
                "type",
                constantSchema(
                    "$GRADLE_CONTRACT.GradleJvmSelectionObservation.Unobserved",
                    "Gradle JVM observation variant.",
                ),
            )
        ),
        objectSchema(
            ServerSchemaProperty(
                "type",
                constantSchema(
                    "$GRADLE_CONTRACT.GradleJvmSelectionObservation.Observed",
                    "Gradle JVM observation variant.",
                ),
            ),
            ServerSchemaProperty("report", gradleJvmSelectionReportSchema()),
        ),
    )

internal fun gradleJvmSelectionReportSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("distribution", gradleDistributionEvidenceSchema()),
        ServerSchemaProperty(
            "requiredJava",
            finiteArraySchema(integerSchema(1, MAXIMUM_JAVA_FEATURE, "Required Java feature.")),
        ),
        ServerSchemaProperty("candidates", arraySchema(gradleJvmCandidateSchema())),
        ServerSchemaProperty("outcome", gradleJvmSelectionOutcomeSchema()),
    )

internal fun gradleDistributionEvidenceSchema(): JsonObject =
    unionSchema(
        objectSchema(
            ServerSchemaProperty(
                "type",
                constantSchema(
                    "$GRADLE_CONTRACT.GradleDistributionEvidence.Unavailable",
                    "Gradle distribution evidence variant.",
                ),
            )
        ),
        objectSchema(
            ServerSchemaProperty(
                "type",
                constantSchema(
                    "$GRADLE_CONTRACT.GradleDistributionEvidence.Observed",
                    "Gradle distribution evidence variant.",
                ),
            ),
            ServerSchemaProperty("version", textSchema("Observed Gradle version.")),
        ),
    )

internal fun gradleJvmCandidateSchema(): JsonObject =
    objectSchema(
        ServerSchemaProperty("java", integerSchema(1, MAXIMUM_JAVA_FEATURE, "Java feature.")),
        ServerSchemaProperty("homeIdentity", sha256Schema("JDK home identity.")),
        ServerSchemaProperty(
            "authority",
            enumSchema(
                listOf(
                    "DAEMON_JVM_CRITERIA",
                    "REPOSITORY_GRADLE_PROPERTY",
                    "AMBIENT_JAVA_HOME",
                    "SIDECAR_COMPATIBLE",
                    "PLATFORM_RESOLVER",
                ),
                "JDK selection authority.",
            ),
        ),
        ServerSchemaProperty(
            "decision",
            enumSchema(
                listOf(
                    "SELECTED",
                    "INCOMPATIBLE_GRADLE",
                    "SHADOWED_BY_PROJECT_AUTHORITY",
                    "NOT_SELECTED",
                ),
                "JDK candidate decision.",
            ),
        ),
    )

internal fun gradleJvmSelectionOutcomeSchema(): JsonObject =
    unionSchema(
        objectSchema(
            ServerSchemaProperty(
                "type",
                constantSchema(
                    "$GRADLE_CONTRACT.GradleJvmSelectionOutcome.Selected",
                    "Gradle JVM outcome variant.",
                ),
            ),
            ServerSchemaProperty("candidate", gradleJvmCandidateSchema()),
        ),
        objectSchema(
            ServerSchemaProperty(
                "type",
                constantSchema(
                    "$GRADLE_CONTRACT.GradleJvmSelectionOutcome.Rejected",
                    "Gradle JVM outcome variant.",
                ),
            ),
            ServerSchemaProperty(
                "failure",
                enumSchema(
                    listOf(
                        "GRADLE_DISTRIBUTION_UNAVAILABLE",
                        "DAEMON_JVM_CRITERIA_UNSUPPORTED",
                        "REPOSITORY_JAVA_HOME_INVALID",
                        "LOCAL_JVM_DISCOVERY_FAILED",
                        "NO_COMPATIBLE_RUNTIME",
                        "SDK_REGISTRATION_FAILED",
                    ),
                    "Closed Gradle JVM selection failure.",
                ),
            ),
        ),
    )

internal fun ideDescriptorFailureSchema(): JsonObject =
    unionSchema(
        *listOf(
                "malformed-document",
                "non-canonical-document",
                "unsupported-schema",
                "unsupported-host-kind",
                "unsupported-framing",
            )
            .map(::typeOnlyFailureSchema)
            .toTypedArray(),
        typedFailureSchema("invalid-canonical-root", "failure"),
        typedFailureSchema("invalid-socket-path", "failure"),
        typedFailureSchema("invalid-process-id", "failure"),
        typedFailureSchema("invalid-runtime-epoch", "failure"),
        objectSchema(
            ServerSchemaProperty(
                "type",
                constantSchema("compatibility-rejected", "Descriptor failure variant."),
            ),
            ServerSchemaProperty("failure", compatibilityFailureSchema()),
        ),
        objectSchema(
            ServerSchemaProperty(
                "type",
                constantSchema("hosted-capabilities-rejected", "Descriptor failure variant."),
            ),
            ServerSchemaProperty("failure", hostedCapabilitiesFailureSchema()),
        ),
    )

internal fun compatibilityFailureSchema(): JsonObject =
    unionSchema(
        objectSchema(
            ServerSchemaProperty("type", constantSchema("malformed", "Compatibility failure.")),
            ServerSchemaProperty("field", textSchema("Rejected compatibility field.")),
            ServerSchemaProperty("syntax", textSchema("Closed syntax failure.")),
        ),
        objectSchema(
            ServerSchemaProperty("type", constantSchema("mismatch", "Compatibility failure.")),
            ServerSchemaProperty("field", textSchema("Mismatched compatibility field.")),
            ServerSchemaProperty("expected", textSchema("Expected identity.")),
            ServerSchemaProperty("observed", textSchema("Observed identity.")),
        ),
        objectSchema(
            ServerSchemaProperty(
                "type",
                constantSchema("capability-set-mismatch", "Compatibility failure."),
            ),
            ServerSchemaProperty("field", textSchema("Mismatched capability field.")),
            ServerSchemaProperty("expected", finiteArraySchema(textSchema("Expected operation."))),
            ServerSchemaProperty("observed", finiteArraySchema(textSchema("Observed operation."))),
        ),
        typedFailureSchema("unknown-capability", "operationId"),
        typedFailureSchema("unsupported-capability", "operationId"),
        typedFailureSchema("duplicate-capability", "operationId"),
    )

internal fun hostedCapabilitiesFailureSchema(): JsonObject =
    unionSchema(
        typedFailureSchema("malformed-operation-id", "failure"),
        typedFailureSchema("unknown-operation", "operationId"),
        typedFailureSchema("unsupported-intent", "operationId"),
        typedFailureSchema("duplicate-operation", "operationId"),
        objectSchema(
            ServerSchemaProperty(
                "type",
                constantSchema("duplicate-intent", "Hosted-capability failure."),
            ),
            ServerSchemaProperty("operationId", textSchema("Canonical operation identity.")),
            ServerSchemaProperty("intent", textSchema("Duplicate hosted intent.")),
        ),
        typeOnlyFailureSchema("canonical-projection-mismatch"),
    )
