package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.DependencyClassDigest
import io.github.amichne.kast.relation.contract.KotlinLazyBinaryEvidence
import io.github.amichne.kast.relation.contract.KotlinLazyClassDigests
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter

/** Reads six bounded classes from the resolved factory's archive under one unchanged archive stamp. */
internal fun readKotlinLazyBinaryEvidence(
    context: IntellijCallbackFlowContext,
    factory: VirtualFile,
    factoryDigest: DependencyClassDigest,
): Refinement<KotlinLazyBinaryEvidence, CallbackInvocationFlowCause> {
    context.observation.count(IntellijReadCounter.CALLBACK_DEFERRED_MODEL_READS)
    val result = lazyBinaryEvidence(context, factory, factoryDigest)
    context.observation.count(
        when (result) {
            is Refinement.Refined -> IntellijReadCounter.CALLBACK_DEFERRED_MODELS_ADMITTED
            is Refinement.Rejected -> IntellijReadCounter.CALLBACK_DEFERRED_MODELS_REJECTED
        }
    )
    return result
}

private fun lazyBinaryEvidence(
    context: IntellijCallbackFlowContext,
    factory: VirtualFile,
    factoryDigest: DependencyClassDigest,
): Refinement<KotlinLazyBinaryEvidence, CallbackInvocationFlowCause> {
    if (!factory.url.endsWith("!/kotlin/LazyKt__LazyJVMKt.class") && !factory.url.endsWith("!/kotlin/LazyKt.class"))
        return Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_DEFERRED_MODEL_UNPROVEN)
    val archive =
        VfsUtilCore.getVirtualFileForJar(factory)
            ?: return Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_BINARY_UNAVAILABLE)
    val stamp = archive.modificationStamp
    val factoryStamp = factory.modificationStamp
    val classes =
        when (val read = LazyBinaryClasses(context, factory, factoryDigest).readClasses()) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    if (archive.modificationStamp != stamp || factory.modificationStamp != factoryStamp)
        return Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_BINARY_CHANGED)
    return KotlinLazyBinaryEvidence.admit(classes)
}

private class LazyBinaryClasses(
    private val context: IntellijCallbackFlowContext,
    private val factory: VirtualFile,
    private val factoryDigest: DependencyClassDigest,
) {
    fun read(name: String): Refinement<DependencyClassDigest, CallbackInvocationFlowCause> {
        when (val permit = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return permit
        }
        val file =
            factory.parent.findChild("$name.class")
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_BINARY_UNAVAILABLE)
        return if (file == factory) Refinement.Refined(factoryDigest) else file.contractClassDigest()
    }

    fun readClasses(): Refinement<KotlinLazyClassDigests, CallbackInvocationFlowCause> {

        val facade =
            when (val read = read("LazyKt")) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        val implementationFactory =
            when (val read = read("LazyKt__LazyJVMKt")) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        val synchronized =
            when (val read = read("SynchronizedLazyImpl")) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        val publication =
            when (val read = read("SafePublicationLazyImpl")) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        val unsafe =
            when (val read = read("UnsafeLazyImpl")) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        val modeDispatch =
            when (val read = read("LazyKt__LazyJVMKt\$WhenMappings")) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        return Refinement.Refined(
            KotlinLazyClassDigests(
                facade = facade,
                factory = implementationFactory,
                synchronized = synchronized,
                publication = publication,
                unsafe = unsafe,
                modeDispatch = modeDispatch,
            )
        )
    }
}
