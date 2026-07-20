package io.github.amichne.kast.api.contract.mutation

import io.github.amichne.kast.api.contract.skill.KastAddDeclarationRequest
import io.github.amichne.kast.api.contract.skill.KastAddFileRequest
import io.github.amichne.kast.api.contract.skill.KastAddImplementationRequest
import io.github.amichne.kast.api.contract.skill.KastAddStatementRequest
import io.github.amichne.kast.api.contract.skill.KastRenameRequest
import io.github.amichne.kast.api.contract.skill.KastReplaceDeclarationRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface KastSemanticMutation {
    val workspaceTaskId: KastWorkspaceTaskId
    val idempotencyKey: KastMutationIdempotencyKey
    val kind: KastSemanticMutationKind
    val symbolMethod: String

    @Serializable
    @SerialName("RENAME")
    data class Rename(
        override val workspaceTaskId: KastWorkspaceTaskId,
        override val idempotencyKey: KastMutationIdempotencyKey,
        val request: KastRenameRequest,
    ) : KastSemanticMutation {
        override val kind: KastSemanticMutationKind
            get() = KastSemanticMutationKind.RENAME
        override val symbolMethod: String
            get() = "symbol/rename"
    }

    @Serializable
    @SerialName("ADD_FILE")
    data class AddFile(
        override val workspaceTaskId: KastWorkspaceTaskId,
        override val idempotencyKey: KastMutationIdempotencyKey,
        val request: KastAddFileRequest,
    ) : KastSemanticMutation {
        override val kind: KastSemanticMutationKind
            get() = KastSemanticMutationKind.ADD_FILE
        override val symbolMethod: String
            get() = "symbol/add-file"
    }

    @Serializable
    @SerialName("ADD_DECLARATION")
    data class AddDeclaration(
        override val workspaceTaskId: KastWorkspaceTaskId,
        override val idempotencyKey: KastMutationIdempotencyKey,
        val request: KastAddDeclarationRequest,
    ) : KastSemanticMutation {
        override val kind: KastSemanticMutationKind
            get() = KastSemanticMutationKind.ADD_DECLARATION
        override val symbolMethod: String
            get() = "symbol/add-declaration"
    }

    @Serializable
    @SerialName("ADD_IMPLEMENTATION")
    data class AddImplementation(
        override val workspaceTaskId: KastWorkspaceTaskId,
        override val idempotencyKey: KastMutationIdempotencyKey,
        val request: KastAddImplementationRequest,
    ) : KastSemanticMutation {
        override val kind: KastSemanticMutationKind
            get() = KastSemanticMutationKind.ADD_IMPLEMENTATION
        override val symbolMethod: String
            get() = "symbol/add-implementation"
    }

    @Serializable
    @SerialName("ADD_STATEMENT")
    data class AddStatement(
        override val workspaceTaskId: KastWorkspaceTaskId,
        override val idempotencyKey: KastMutationIdempotencyKey,
        val request: KastAddStatementRequest,
    ) : KastSemanticMutation {
        override val kind: KastSemanticMutationKind
            get() = KastSemanticMutationKind.ADD_STATEMENT
        override val symbolMethod: String
            get() = "symbol/add-statement"
    }

    @Serializable
    @SerialName("REPLACE_DECLARATION")
    data class ReplaceDeclaration(
        override val workspaceTaskId: KastWorkspaceTaskId,
        override val idempotencyKey: KastMutationIdempotencyKey,
        val request: KastReplaceDeclarationRequest,
    ) : KastSemanticMutation {
        override val kind: KastSemanticMutationKind
            get() = KastSemanticMutationKind.REPLACE_DECLARATION
        override val symbolMethod: String
            get() = "symbol/replace-declaration"
    }
}
