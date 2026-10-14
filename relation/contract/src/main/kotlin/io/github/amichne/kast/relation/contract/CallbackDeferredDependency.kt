package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

/** Reviewed binary content for the facade, factory, initializer implementations, and mode dispatch. */
class KotlinLazyBinaryEvidence private constructor(val classes: KotlinLazyClassDigests) {
    companion object {
        fun admit(classes: KotlinLazyClassDigests): Refinement<KotlinLazyBinaryEvidence, CallbackInvocationFlowCause> =
            if (
                listOf(
                    classes.facade.value,
                    classes.factory.value,
                    classes.synchronized.value,
                    classes.publication.value,
                    classes.unsafe.value,
                    classes.modeDispatch.value,
                ) in REVIEWED_LAZY_BINARIES
            )
                Refinement.Refined(KotlinLazyBinaryEvidence(classes))
            else Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_DEFERRED_MODEL_UNPROVEN)
    }
}

/** Detached class-content observations remain unproven until the reviewed model admits all six. */
data class KotlinLazyClassDigests(
    val facade: DependencyClassDigest,
    val factory: DependencyClassDigest,
    val synchronized: DependencyClassDigest,
    val publication: DependencyClassDigest,
    val unsafe: DependencyClassDigest,
    val modeDispatch: DependencyClassDigest,
)

/** A possible deferred initializer supply. This proof never asserts an invocation or an invocation count. */
class CallbackDeferredDependency
private constructor(
    val owner: CallbackDeferredSupplier,
    val target: CompilerGroundedSymbolEvidence,
    val position: ValueArgumentPosition,
    val binary: KotlinLazyBinaryEvidence,
) {
    val basis = owner.basis
    val occurrence = owner.occurrence

    companion object {
        fun fromCompiler(
            owner: CallbackDeferredSupplier,
            target: CompilerGroundedSymbolEvidence,
            position: ValueArgumentPosition,
            binary: KotlinLazyBinaryEvidence,
        ): Refinement<CallbackDeferredDependency, CallbackInvocationFlowFailure> {
            val signature =
                target.signature as? CanonicalCompilerSignature.Function
                    ?: return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_DEPENDENCY_CONTRACT)
            val file =
                target.file as? SymbolDiscoveryFileIdentity.External
                    ?: return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_DEPENDENCY_CONTRACT)
            if (
                signature.qualifiedIdentity.value != "kotlin.lazy" ||
                    signature.receiver != CanonicalCompilerReceiver.Absent
            )
                return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_DEPENDENCY_CONTRACT)
            if (signature.contextReceivers.isNotEmpty() || signature.typeParameterCount.value != 1)
                return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_DEPENDENCY_CONTRACT)
            if (signature.valueParameters.size !in 1..2 || position.value != signature.valueParameters.lastIndex)
                return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_DEPENDENCY_CONTRACT)
            if (!file.url.value.startsWith("jar://"))
                return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_DEPENDENCY_CONTRACT)
            if (
                !file.url.value.endsWith("!/kotlin/LazyKt.class") &&
                    !file.url.value.endsWith("!/kotlin/LazyKt__LazyJVMKt.class")
            )
                return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_DEPENDENCY_CONTRACT)
            return Refinement.Refined(CallbackDeferredDependency(owner, target, position, binary))
        }
    }
}

private val REVIEWED_LAZY_BINARIES =
    setOf(
        // Kotlin stdlib 1.9.24.
        listOf(
            "2825350109fdfd5e58a067c8e124b446594d9c6a9be4db7086e9d65b1f5597b3",
            "30ba3a2ca6c5427d3f3fc6d11ef533eef74b72d46a56db1b76f1b0eed0ac9bc9",
            "52058246e1c92b481a42f256082afba920a929a1b07b7e8b6a46e5df061ae5bf",
            "5b4f3afe7a8c566200c000413663d02a1543884ca7744e522a25dba7c1d41dab",
            "7b8260c477923f95b618f3f99af270534b8631a4f11860c6683869c388d15577",
            "4c3d539021e4af0d1c1ac3e7682c1a6d546a5e52b4ebb706db8e4eca3c801265",
        ),
        // Kotlin stdlib 2.0.21.
        listOf(
            "2825350109fdfd5e58a067c8e124b446594d9c6a9be4db7086e9d65b1f5597b3",
            "30ba3a2ca6c5427d3f3fc6d11ef533eef74b72d46a56db1b76f1b0eed0ac9bc9",
            "bc3c43305a1c40ba2e457fb1f89084e377931f4dd73bf1f23a31bbd31ee5e99a",
            "f44fa94e1b7a01c0981cce30cea8882d756806e67156cb6c5e42946c08631165",
            "7b8260c477923f95b618f3f99af270534b8631a4f11860c6683869c388d15577",
            "4c3d539021e4af0d1c1ac3e7682c1a6d546a5e52b4ebb706db8e4eca3c801265",
        ),
        // Kotlin stdlib 2.1.20.
        listOf(
            "7426767f0ecb53010760569d57ebd71818b3cf8a6e011b58e2707e889f806693",
            "a62fc5e94947642fb6d4b9661b28f3fc7ac86f3f4f4f4e8228ac252aa248749f",
            "9d79ac2df77e1b8d3b34584356bf9b133ba81c766cf4d454549a4ce55904bd9d",
            "b9dfaafa6d789d7c450a419827ab2b0a118ff960969d12f07dbb595a85a3b7a9",
            "9ae02973a34b7316d1c08cd6bf73795e6c6376ef5348dc658aef1958d456aa6e",
            "2acf0fcb38e8731530af9fbcd5c9fe9252c60b252ce53d205c05373a0524f1d8",
        ),
        // Kotlin stdlib 2.2.20.
        listOf(
            "8bccd4d50f8a0097744b645330b346a745d9778cfcc5f3bed047f2ad1bb02bd9",
            "098898e0cb4e4b39371f7a5d569a450602ac18baae9b5634f2672d5a75cf2c5d",
            "ed84525304a4e0fc606a73e087df0b05ed44d48af71af722b4a3ee4512996348",
            "15246ab7759a02b8ba06f6725e95b6f3add17ec086f214c1598b6c7e09d0a1ef",
            "ee502a24de59f5bcd62c36499819a24c516bd8a400f64642a1956d1c6f330124",
            "fa549923484646d4904ce264ac7b855a5834335c52fc002a2308712225a7bd93",
        ),
        // Kotlin stdlib 2.3.20.
        listOf(
            "0f432817ab2bf3d06e5095a83b872c1e1e2632a2aea3bce3b1e0fbd50cbd32c4",
            "0b114cc831af3a8f239210550a7c827b220dcfabf7affbbfb39916a4a50c846a",
            "e1e31f2d66b92e44fd5232af6a0f40e0fd1a42ed93b3eb44e26fe111e0e10294",
            "f4bcf09dcc22d212208914c804a924fb94e9ae5336a2d4bfaf0b92072c48135a",
            "1b745b04099715a6eeb9d4df8d593c769aa58b8f116cc88063e8d17d35d759c3",
            "842bd12641b951e267c40fd2a57cb4fa4356167ccbf713722b541d8307429d7d",
        ),
        // Kotlin stdlib 2.4.20.
        listOf(
            "40a68df0fe452df9d7a7360220e498ccf105c369ab3c117edd300003654f3e8f",
            "47e8208e0dddc260709ec5573405b0a89b7506ffd17da9a4d02d01186aed1a0c",
            "df1d1ff13a024c4f98158598b98021c1c90ffb8516fb00c41f7315b29de9c33f",
            "85de91a2748af85549ac08421e7141f0c0434df8f196c66a6fa2c88962a53ae8",
            "e33934f3107bea95658842c372477374e1e72da60a342d812e33cebc02bc36e2",
            "17a79669895c0e2c06958fc2b144a3f608f3efaeed7d43014d640acbad485d5b",
        ),
    )
