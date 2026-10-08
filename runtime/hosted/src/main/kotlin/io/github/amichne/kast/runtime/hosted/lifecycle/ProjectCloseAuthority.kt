package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.ProjectCloseApprovalPayload

/** Exact local close intent. The connected controller owns user confirmation; this is not a credential. */
internal sealed interface ProjectCloseAuthority {
    data object ManagedCleanup : ProjectCloseAuthority

    class UserDirected private constructor(private val confirmation: ProjectCloseApprovalPayload) :
        ProjectCloseAuthority {
        fun matches(command: IdeLifecycleCommand.Close): Boolean =
            confirmation.target == command.target &&
                confirmation.requestId == command.requestId &&
                confirmation.client == command.client

        companion object {
            fun admit(command: IdeLifecycleCommand.AuthorizedClose): Refinement<UserDirected, IdeLifecycleFailure> {
                val confirmation = command.confirmation
                val authority = UserDirected(confirmation)
                val ids = listOf(confirmation.threadId, confirmation.turnId, confirmation.callId)
                return if (
                    confirmation.client == confirmation.threadId &&
                        ids.all(::boundedInvocationIdentifier) &&
                        authority.matches(IdeLifecycleCommand.Close(command.requestId, command.client, command.target))
                )
                    Refinement.Refined(authority)
                else Refinement.Rejected(IdeLifecycleFailure.USER_AUTHORIZATION_REQUIRED)
            }
        }
    }
}

private const val MAX_INVOCATION_LENGTH = 4096

private fun boundedInvocationIdentifier(value: String): Boolean =
    value.isNotBlank() && value.length <= MAX_INVOCATION_LENGTH && value.none(Char::isISOControl)
