package io.github.amichne.kast.workspace.intellij.read.hosted

import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Samples event shape only: neither file attachment nor callback timing proves causality. */
internal class HostedPsiDiagnosticListener(
    private val host: IdeReadHostLifetime,
    private val publish: (String) -> Unit,
) : PsiTreeChangeAdapter() {
    private val observed = mutableSetOf<Pair<HostedPsiPropertyKind, HostedPsiFileAttachment>>()

    override fun propertyChanged(event: PsiTreeChangeEvent) {
        observe(
            event.propertyName,
            if (event.file == null) HostedPsiFileAttachment.ABSENT else HostedPsiFileAttachment.ATTACHED,
        )
    }

    @Synchronized
    internal fun observe(propertyName: String?, file: HostedPsiFileAttachment) {
        val kind =
            if (propertyName == PsiTreeChangeEvent.PROP_UNLOADED_PSI) HostedPsiPropertyKind.UNLOADED_PSI
            else HostedPsiPropertyKind.OTHER
        if (observed.add(kind to file)) {
            publish(Json.encodeToString(HostedPsiPropertyDocument(host.value.toString(), kind, file)))
        }
    }
}

@Serializable
internal enum class HostedPsiPropertyKind {
    UNLOADED_PSI,
    OTHER,
}

@Serializable
internal enum class HostedPsiFileAttachment {
    ABSENT,
    ATTACHED,
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal data class HostedPsiPropertyDocument(
    val host: String,
    val kind: HostedPsiPropertyKind,
    val file: HostedPsiFileAttachment,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val event: String = "kast_hosted_psi_property",
)
