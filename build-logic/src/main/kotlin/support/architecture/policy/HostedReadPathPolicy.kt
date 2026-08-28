package support.architecture

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

internal sealed interface HostedReadInjectionFailure {
    data class FamilyContractMismatch(
        val missing: Set<Kvp018RequiredForbiddenFamily>,
        val duplicated: Set<Kvp018RequiredForbiddenFamily>,
    ) : HostedReadInjectionFailure
    data class FixtureScanRejected(val failures: List<BytecodeScanFailure>) :
        HostedReadInjectionFailure
    data class MissingClassification(
        val family: Kvp018RequiredForbiddenFamily,
        val target: JvmMember,
        val requiredEffect: ForbiddenEffect,
        val observedEffects: Set<ForbiddenEffect>,
    ) : HostedReadInjectionFailure
}

internal class HostedReadInjectionProof private constructor(
    observations: Map<Kvp018RequiredForbiddenFamily, Set<ForbiddenEffect>>,
) {
    private val snapshot = observations.mapValues { (_, effects) -> effects.toSet() }.toMap()

    internal fun observations(): Map<Kvp018RequiredForbiddenFamily, Set<ForbiddenEffect>> =
        snapshot.mapValues { (_, effects) -> effects.toMutableSet() }.toMutableMap()

    internal companion object {
        /**
         * Proof transition: `ValidatedModulePolicy -> HostedReadInjectionVerification`.
         *
         * Establishes one independently scanned JVM observation for every typed KVP-018 family
         * and its exact required effect. Family, fixture-scan, or classification gaps remain
         * closed [HostedReadInjectionFailure] data. Raw ASM generation is confined here.
         */
        fun verify(module: ValidatedModulePolicy): HostedReadInjectionVerification {
            val observedFamilies = HostedReadForbiddenAuthority.entries.map { it.family }
            val requiredFamilies = Kvp018RequiredForbiddenFamily.entries.toSet()
            val missingFamilies = requiredFamilies - observedFamilies.toSet()
            val duplicatedFamilies = observedFamilies.groupingBy { it }.eachCount()
                .filterValues { it > 1 }.keys
            if (missingFamilies.isNotEmpty() || duplicatedFamilies.isNotEmpty()) {
                return HostedReadInjectionVerification.Rejected(
                    HostedReadInjectionFailure.FamilyContractMismatch(
                        missingFamilies,
                        duplicatedFamilies,
                    ),
                    emptyList(),
                )
            }
            val calls = HostedReadForbiddenAuthority.entries
            val effects = when (val scan = JvmEffectScanner.scanBytes(
                module,
                listOf(HostedReadClassBytes.capture(
                    "Kvp018InjectedFixture.class",
                    injectedHostedReadClass(calls),
                )),
            )) {
                is BytecodeScanOutcome.Scanned -> scan.effects()
                is BytecodeScanOutcome.Failed -> return HostedReadInjectionVerification.Rejected(
                    HostedReadInjectionFailure.FixtureScanRejected(scan.failures()),
                    emptyList(),
                )
            }
            val observations = calls.associate { authority ->
                authority.family to effects.filter { it.target == authority.target }
                    .mapTo(linkedSetOf(), EffectObservation::effect)
            }
            val failures = calls.mapNotNull { authority ->
                val observed = observations.getValue(authority.family)
                HostedReadInjectionFailure.MissingClassification(
                    authority.family,
                    authority.target,
                    authority.requiredEffect,
                    observed,
                ).takeIf { authority.requiredEffect !in observed }
            }
            return if (failures.isEmpty()) {
                HostedReadInjectionVerification.Complete(HostedReadInjectionProof(observations))
            } else {
                HostedReadInjectionVerification.Rejected(failures.first(), failures.drop(1))
            }
        }
    }
}

internal sealed interface HostedReadInjectionVerification {
    data class Complete(val proof: HostedReadInjectionProof) : HostedReadInjectionVerification
    data class Rejected(
        val first: HostedReadInjectionFailure,
        val additional: List<HostedReadInjectionFailure>,
    ) : HostedReadInjectionVerification
}

internal object HostedReadPathPolicy {
    /**
     * Proof transition: `ValidatedModulePolicy -> HostedReadInjectionVerification`.
     *
     * Establishes that every finite KVP-018 injected JVM authority is classified as its required
     * hosted-read forbidden effect. Expected missing classifications remain closed
     * [HostedReadInjectionFailure] values. Raw JVM members are owned by
     * [HostedReadForbiddenAuthority] and are not exposed beyond build-policy proof.
     */
    fun verifyInjectedAuthorities(
        module: ValidatedModulePolicy,
    ): HostedReadInjectionVerification = HostedReadInjectionProof.verify(module)
}

private fun injectedHostedReadClass(calls: List<HostedReadForbiddenAuthority>): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
        "io/github/amichne/kast/workspace/intellij/read/Kvp018InjectedFixture",
        null,
        "java/lang/Object",
        null,
    )
    calls.forEachIndexed { index, call ->
        writer.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "injected$index",
            "()V",
            null,
            null,
        ).apply {
            visitCode()
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                call.target.owner.internalName,
                call.target.name.value,
                call.target.descriptor.value,
                false,
            )
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }
    writer.visitEnd()
    return writer.toByteArray()
}

internal enum class HostedReadReportMutation {
    AUTHORITY_COUNT,
    EFFECT_COUNT,
    INVENTORY_DIGEST,
    PROJECT_PATH,
    PROJECT_ARTIFACT_DIGEST,
    EXTERNAL_ARTIFACT_DIGEST,
    PREDECESSOR_DIGEST,
    NON_CANONICAL_BYTES,
}

internal sealed interface HostedReadReportMutationVerification {
    sealed interface Complete : HostedReadReportMutationVerification {
        val count: Int
        fun canonicalDocumentAtReportBoundary(): String
    }
    data class Rejected(
        val mutation: HostedReadReportMutation,
        val expected: HostedReadPathReportFailure,
        val observed: HostedReadPathReportAdmission,
    ) : HostedReadReportMutationVerification
}

private class CompleteHostedReadReportMutationVerification(
    private val report: AdmittedKvp018NoWalkReport,
    override val count: Int,
) : HostedReadReportMutationVerification.Complete {
    override fun canonicalDocumentAtReportBoundary(): String =
        report.canonicalDocumentAtReportBoundary()
}

internal object HostedReadPathReportPolicy {
    /**
     * Proof transition: `(AdmittedKvp018NoWalkReport, VfsPassiveHostedModelCapture,
     * Kvp018PredecessorReceipts) -> HostedReadReportMutationVerification`.
     *
     * Establishes rejection of every independently bound canonical KVP-018 report field against
     * the real production proof. An admitted mutation or wrong closed failure remains a typed
     * rejection. Raw report mutation is confined to this GREEN proof boundary.
     */
    fun verifyMutations(
        report: AdmittedKvp018NoWalkReport,
        proof: VfsPassiveHostedModelCapture,
        predecessors: Kvp018PredecessorReceipts,
    ): HostedReadReportMutationVerification {
        val canonicalDocument = report.canonicalDocumentAtReportBoundary()
        val cases = listOf(
            HostedReadReportMutation.AUTHORITY_COUNT to mutation(
                canonicalDocument.replace(
                    "\"injectedForbiddenAuthorityCount\": ${HostedReadForbiddenAuthority.entries.size}",
                    "\"injectedForbiddenAuthorityCount\": 0",
                ),
                HostedReadPathReportFailure.INJECTED_AUTHORITY_COUNT_MISMATCH,
            ),
            HostedReadReportMutation.EFFECT_COUNT to mutation(
                canonicalDocument.replaceFirst("\"count\": 0", "\"count\": 1"),
                HostedReadPathReportFailure.EFFECT_COUNTS_MISMATCH,
            ),
            HostedReadReportMutation.INVENTORY_DIGEST to mutation(
                canonicalDocument.replaceFirst(proof.inventory.digest.value, "d".repeat(64)),
                HostedReadPathReportFailure.INVENTORY_REJECTED,
            ),
            HostedReadReportMutation.PROJECT_PATH to mutation(
                canonicalDocument.replaceFirst(
                    proof.projectClasspath.projectPaths().first().projectPath,
                    ":unexpected",
                ),
                HostedReadPathReportFailure.RUNTIME_PROJECT_PATHS_MISMATCH,
            ),
            HostedReadReportMutation.PROJECT_ARTIFACT_DIGEST to mutation(
                canonicalDocument.replaceFirst(
                    proof.projectClasspath.artifactSetSha256,
                    "e".repeat(64),
                ),
                HostedReadPathReportFailure.PROJECT_CLASSPATH_MISMATCH,
            ),
            HostedReadReportMutation.EXTERNAL_ARTIFACT_DIGEST to mutation(
                canonicalDocument.replaceFirst(
                    proof.externalClasspath.artifactSetSha256,
                    "f".repeat(64),
                ),
                HostedReadPathReportFailure.EXTERNAL_CLASSPATH_MISMATCH,
            ),
            HostedReadReportMutation.PREDECESSOR_DIGEST to mutation(
                canonicalDocument.replaceFirst(
                    predecessors.artifacts().first().sha256,
                    "0".repeat(64),
                ),
                HostedReadPathReportFailure.PREDECESSOR_RECEIPTS_MISMATCH,
            ),
            HostedReadReportMutation.NON_CANONICAL_BYTES to mutation(
                "\n$canonicalDocument",
                HostedReadPathReportFailure.NON_CANONICAL_DOCUMENT,
            ),
        )
        cases.forEach { (id, case) ->
            val observed = admitHostedReadPathReport(case.report, proof, predecessors)
            if (observed !is HostedReadPathReportAdmission.Rejected ||
                observed.failure != case.expected
            ) return HostedReadReportMutationVerification.Rejected(id, case.expected, observed)
        }
        return CompleteHostedReadReportMutationVerification(report, cases.size)
    }

    private fun mutation(report: String, expected: HostedReadPathReportFailure) =
        HostedReadReportMutationCase(report, expected)
}

private data class HostedReadReportMutationCase(
    val report: String,
    val expected: HostedReadPathReportFailure,
)
