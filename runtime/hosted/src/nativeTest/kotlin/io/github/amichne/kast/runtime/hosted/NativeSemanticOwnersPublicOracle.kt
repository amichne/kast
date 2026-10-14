package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.PathManager
import io.github.amichne.kast.protocol.contract.CompilerReceiverDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import io.github.amichne.kast.protocol.contract.QueryDeferredDependencyModelDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourceSetsDocument
import io.github.amichne.kast.protocol.contract.QueryRelationCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue

/** Absolute source identity precedes the injective fixture-root projection. */
internal class NativeSemanticOwnersPublicOracle(private val root: Path) {
    fun actual(result: QueryRunResult): NativeSemanticOwnersSemanticResult {
        val callback = callback(result)
        val flow = callback.flow as QueryCallbackFlowDocument.Observed
        val binding = flow.binding as QueryCallbackBindingDocument.DeferredDependency
        assertFlow(flow, binding)
        val execution = NativeSemanticOwnersExecutionOracle(root).from(flow, binding)
        val basis = flow.basis as ImpactSemanticBasisDocument.Published
        assertEquals(root.toString(), basis.root.value)
        return NativeSemanticOwnersSemanticResult(
            completion = NativeSemanticOwnersCompletion.COMPLETE,
            scan = scan(flow.scan),
            targets = setOf(target(callback)),
            namedCallers =
                result.items.values
                    .map { semanticOwnersIdentity(checkNotNull((it as QueryResultItemDocument.ExactSymbol).signature)) }
                    .toSet(),
            supplierIdentity = semanticOwnersIdentity(binding.owner.compilerTarget.compilerEvidence.signature),
            supplierDeclaration = binding.owner.declaration.range.semanticOwnersSpan(),
            supplierSignature = supplierSignature(binding.owner.compilerTarget.compilerEvidence),
            executionOwner = execution.owner,
            executionKind = execution.kind,
            supply = binding.occurrence.range.semanticOwnersSpan(),
            bodies = setOf(callback.callbackBody.range.semanticOwnersSpan()),
            directOuterCalls = execution.route.map { it.call }.toSet(),
            executionRoute = execution.route,
            effect = NativeSemanticOwnersEffect.DEFERRED_NO_CARDINALITY,
            obligations =
                (flow.obligations.values + flow.ownerBindings.values.flatMap { it.obligations.values })
                    .map { it.name }
                    .toSet(),
            failures = result.failures.values.map { it.toString() }.toSet(),
            omissions = result.omissions.values.map { it.toString() }.toSet(),
            generation = basis.generation.value,
            sourceFile = normalizeSourceFile(callback.occurrence.file.value),
            sourceSets =
                ((callback.effectiveDomain.sourceSets as QueryDiscoverySourceSetsDocument.Exact).names.values)
                    .map { it.value }
                    .toSet(),
            directory = checkNotNull(callback.effectiveDomain.directory).path.value,
            relation = callback.relation.name,
        )
    }

    private fun scan(scan: QueryCallbackInvocationScanDocument): NativeSemanticOwnersScan =
        when (scan) {
            QueryCallbackInvocationScanDocument.EXHAUSTIVE -> NativeSemanticOwnersScan.EXHAUSTIVE
            QueryCallbackInvocationScanDocument.INCOMPLETE -> NativeSemanticOwnersScan.INCOMPLETE
            QueryCallbackInvocationScanDocument.NOT_APPLICABLE -> NativeSemanticOwnersScan.NOT_APPLICABLE
        }

    private fun supplierSignature(evidence: CompilerSymbolEvidenceDocument): NativeSemanticOwnersSupplierSignature =
        when (val signature = evidence.signature) {
            is CompilerSignatureDocument.LocalFunction -> localSignature(signature)
            is CompilerSignatureDocument.Function ->
                NativeSemanticOwnersSupplierSignature.Function(
                    identity = signature.qualifiedIdentity.value,
                    receiver = receiver(signature.receiver),
                    contextReceiverTypes = signature.contextReceivers.values.map { it.value },
                    valueParameterTypes = signature.valueParameters.values.map { it.value },
                    typeParameterCount = signature.typeParameterCount.value,
                    compilerIdentity = evidence.identity.value,
                )
            is CompilerSignatureDocument.Property ->
                NativeSemanticOwnersSupplierSignature.Property(
                    identity = signature.qualifiedIdentity.value,
                    receiver = receiver(signature.receiver),
                    contextReceiverTypes = signature.contextReceivers.values.map { it.value },
                    returnType = signature.returnType.value,
                    compilerIdentity = evidence.identity.value,
                )
            is CompilerSignatureDocument.ClassLike ->
                NativeSemanticOwnersSupplierSignature.ClassLike(
                    signature.qualifiedIdentity.value,
                    evidence.identity.value,
                )
            is CompilerSignatureDocument.TypeAlias,
            is CompilerSignatureDocument.LocalProperty,
            is CompilerSignatureDocument.AnonymousObject ->
                error("A required owner has an unexpected compiler signature")
        }

    private fun localSignature(signature: CompilerSignatureDocument.LocalFunction) =
        NativeSemanticOwnersSupplierSignature.LocalFunction(
            file = normalizeSourceFile(signature.address.file.value.value),
            kind = signature.address.kind,
            declaration = signature.address.range.semanticOwnersSpan(),
            ownerIdentity = signature.address.ownerIdentity.value,
            ownerDeclaration = signature.address.ownerRange.semanticOwnersSpan(),
            lexicalOwners = signature.address.lexicalOwners.values.map { it.semanticOwnersSpan() },
            receiver = receiver(signature.receiver),
            contextReceiverTypes = signature.contextReceivers.values.map { it.value },
            valueParameterTypes = signature.valueParameters.values.map { it.value },
            typeParameterCount = signature.typeParameterCount.value,
            returnType = signature.returnType.value,
        )

    private fun receiver(receiver: CompilerReceiverDocument): NativeSemanticOwnersReceiver =
        when (receiver) {
            CompilerReceiverDocument.Absent -> NativeSemanticOwnersReceiver.Absent
            is CompilerReceiverDocument.Present -> NativeSemanticOwnersReceiver.Present(receiver.compilerType.value)
        }

    private fun target(
        callback: io.github.amichne.kast.protocol.contract.QueryCallbackObservationDocument
    ): NativeSemanticOwnersTarget {
        val signature = callback.target.compilerTarget.compilerEvidence.signature
        val shape =
            when (signature) {
                is CompilerSignatureDocument.Function -> NativeSemanticOwnersTargetShape.FUNCTION
                is CompilerSignatureDocument.ClassLike -> NativeSemanticOwnersTargetShape.CONSTRUCTOR
                else -> error("Unexpected target shape in an owner fixture")
            }
        return NativeSemanticOwnersTarget(
            identity = semanticOwnersIdentity(signature),
            shape = shape,
            occurrence = callback.occurrence.range.semanticOwnersSpan(),
            declaration = callback.target.declaration.range.semanticOwnersSpan(),
            file = normalizeSourceFile(callback.target.declaration.file.value),
            compilerIdentity = callback.target.compilerTarget.compilerEvidence.identity.value,
        )
    }

    fun assertScopes(result: QueryRunResult) {
        result.relationObservations.values.forEach { relation ->
            assertEquals(QueryRelationCoverageDocument.Exhausted, relation.coverage)
            assertEquals(QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY, relation.effectiveDomain.sourcePolicy)
            assertEquals(QueryDiscoveryInclusionPolicyDocument.EXCLUDE, relation.effectiveDomain.libraries)
            assertEquals(QueryDiscoveryInclusionPolicyDocument.EXCLUDE, relation.effectiveDomain.generatedSources)
            assertEquals(NativeSemanticOwnersRequests.directory, relation.effectiveDomain.directory)
        }
    }

    fun assertBinary(result: QueryRunResult) {
        val callback = result.relationObservations.values.flatMap { it.callbackObservations.values }.single()
        val binding =
            (callback.flow as QueryCallbackFlowDocument.Observed).binding
                as QueryCallbackBindingDocument.DeferredDependency
        assertEquals(QueryDeferredDependencyModelDocument.KOTLIN_LAZY_JVM_V1, binding.binary.model)
        assertEquals(0, binding.position.value)
        val signature = binding.target.compilerEvidence.signature as CompilerSignatureDocument.Function
        assertEquals("kotlin.lazy", signature.qualifiedIdentity.value)
        assertEquals(1, signature.valueParameters.values.size)
        assertEquals(1, signature.typeParameterCount.value)
        val observed =
            with(binding.binary) {
                listOf(facade, factory, synchronized, publication, unsafe, modeDispatch).map { it.value }
            }
        assertEquals(binaryDigests(), observed)
    }

    private fun binaryDigests(): List<String> {
        val jar = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
        val names =
            listOf(
                "LazyKt",
                "LazyKt__LazyJVMKt",
                "SynchronizedLazyImpl",
                "SafePublicationLazyImpl",
                "UnsafeLazyImpl",
                "LazyKt__LazyJVMKt\$WhenMappings",
            )
        return ZipFile(jar.toFile()).use { zip ->
            names.map { name ->
                val bytes = zip.getInputStream(checkNotNull(zip.getEntry("kotlin/$name.class"))).use { it.readBytes() }
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
            }
        }
    }

    private fun callback(
        result: QueryRunResult
    ): io.github.amichne.kast.protocol.contract.QueryCallbackObservationDocument {
        val callbacks = result.relationObservations.values.flatMap { it.callbackObservations.values }
        assertEquals("Exact body-target occurrence population", 1, callbacks.size)
        return callbacks.single()
    }

    private fun assertFlow(
        flow: QueryCallbackFlowDocument.Observed,
        binding: QueryCallbackBindingDocument.DeferredDependency,
    ) {
        assertEquals(QueryCallbackInvocationScanDocument.EXHAUSTIVE, flow.scan)
        assertTrue(flow.invocations.values.isEmpty())
        assertEquals(flow.basis, binding.basis)
        normalizeSourceFile(binding.owner.declaration.file.value)
        normalizeSourceFile(binding.occurrence.file.value)
    }

    private fun normalizeSourceFile(raw: String): String {
        val file = Path.of(raw)
        val expected = root.resolve("src/backend-service-clients/Owners.kt")
        assertEquals("The exact owned source must precede root normalization", expected, file)
        return root.relativize(file).toString()
    }
}

internal fun SourceRangeDocument.semanticOwnersSpan() =
    NativeSemanticOwnersRange(startInclusive.value, endExclusive.value)

internal fun semanticOwnersIdentity(signature: CompilerSignatureDocument): String =
    when (signature) {
        is CompilerSignatureDocument.Function -> signature.qualifiedIdentity.value
        is CompilerSignatureDocument.Property -> signature.qualifiedIdentity.value
        is CompilerSignatureDocument.ClassLike -> signature.qualifiedIdentity.value
        is CompilerSignatureDocument.TypeAlias -> signature.qualifiedIdentity.value
        is CompilerSignatureDocument.LocalFunction -> "local-function"
        is CompilerSignatureDocument.LocalProperty -> "local-property"
        is CompilerSignatureDocument.AnonymousObject -> "anonymous-object"
    }
