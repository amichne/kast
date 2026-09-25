package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.IdeProjectDescription
import io.github.amichne.kast.protocol.contract.IdeProjectOwnership
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.util.UUID

/** Application bookkeeping only. Native work is always performed after leaving this monitor. */
internal class IdeLifecycleState(val host: UUID, private val capacity: Int = 256) {
    private data class ProjectEntry(
        val host: UUID,
        val project: UUID,
        val root: CanonicalWorkspaceRoot,
        var ownership: IdeProjectOwnership,
        val users: MutableSet<LifecycleClient>,
        var closing: Boolean = false,
    ) {
        val target: IdeProjectTarget
            get() = IdeProjectTarget(host.toString(), project.toString(), root.value)
    }

    private data class Entry(val command: IdeLifecycleCommand, var result: IdeLifecycleResult)

    private val projects = linkedMapOf<UUID, ProjectEntry>()
    private val operations = linkedMapOf<LifecycleRequest, Entry>()
    private var shutdown = false

    @Synchronized
    fun observe(project: UUID, root: CanonicalWorkspaceRoot, ownership: IdeProjectOwnership): IdeProjectTarget {
        val entry =
            projects.getOrPut(project) {
                ProjectEntry(
                    host,
                    project,
                    root,
                    ownership,
                    mutableSetOf(),
                )
            }
        if (ownership == IdeProjectOwnership.MANAGED && entry.ownership == IdeProjectOwnership.BORROWED)
            entry.ownership = ownership
        return entry.target
    }

    @Synchronized
    fun inspect(): List<IdeProjectDescription> =
        projects.values.map { IdeProjectDescription(it.target, it.ownership, it.users.size) }

    @Synchronized
    fun retire(project: UUID) {
        val retired = projects.remove(project) ?: return
        operations.values.forEach { entry ->
            if (
                entry.result is IdeLifecycleResult.Pending &&
                    target(entry.command) == retired.target &&
                    entry.command !is IdeLifecycleCommand.Close
            )
                entry.result = blocked(IdeLifecycleFailure.DISPOSED)
        }
    }

    @Synchronized
    fun begin(
        command: IdeLifecycleCommand,
        id: LifecycleRequest,
        client: LifecycleClient,
        authority: ProjectCloseAuthority = ProjectCloseAuthority.ManagedCleanup,
    ): LifecycleSubmission {
        if (shutdown) return existing(IdeLifecycleFailure.SHUTDOWN)
        operations[id]?.let {
            return LifecycleSubmission.Existing(
                if (it.command == command) it.result else blocked(IdeLifecycleFailure.REQUEST_CONFLICT)
            )
        }
        if (operations.size >= capacity) return existing(IdeLifecycleFailure.CAPACITY)
        target(command)?.let { target ->
            prepareTarget(command, target, id, client, authority)?.let {
                return it
            }
        }
        if (command is IdeLifecycleCommand.Open)
            joinOpen(command, id)?.let {
                return it
            }
        val pending = IdeLifecycleResult.Pending(id.value, initialStage(command), host.toString())
        operations[id] = Entry(command, pending)
        return LifecycleSubmission.Start(pending)
    }

    private fun prepareTarget(
        command: IdeLifecycleCommand,
        target: IdeProjectTarget,
        id: LifecycleRequest,
        client: LifecycleClient,
        authority: ProjectCloseAuthority,
    ): LifecycleSubmission.Existing? {
        val entry =
            when (val admitted = admitTarget(command, target, client, authority)) {
                is Refinement.Rejected -> return existing(admitted.failure)
                is Refinement.Refined -> admitted.value
            }
        if (command is IdeLifecycleCommand.Close) entry.closing = true
        if (command is IdeLifecycleCommand.Present) entry.ownership = IdeProjectOwnership.PRESENTED
        if (command is IdeLifecycleCommand.Release) {
            entry.users.remove(client)
            val result = IdeLifecycleResult.Released(target)
            operations[id] = Entry(command, result)
            return LifecycleSubmission.Existing(result)
        }
        if (command !is IdeLifecycleCommand.Close) entry.users.add(client)
        return null
    }

    private fun admitTarget(
        command: IdeLifecycleCommand,
        target: IdeProjectTarget,
        client: LifecycleClient,
        authority: ProjectCloseAuthority,
    ): Refinement<ProjectEntry, IdeLifecycleFailure> {
        if (target.host != host.toString()) return Refinement.Rejected(IdeLifecycleFailure.WRONG_HOST)
        val entry =
            projects.values.singleOrNull { it.target == target }
                ?: return Refinement.Rejected(IdeLifecycleFailure.STALE_PROJECT)
        if (entry.closing) return Refinement.Rejected(IdeLifecycleFailure.PROJECT_BUSY)
        if (requiresIdle(command) && hasPendingFor(target)) return Refinement.Rejected(IdeLifecycleFailure.PROJECT_BUSY)
        if (command is IdeLifecycleCommand.Close) {
            if (entry.users.any { it != client }) return Refinement.Rejected(IdeLifecycleFailure.OTHER_CLIENTS)
            val userDirected = authority is ProjectCloseAuthority.UserApproved && authority.matches(command)
            if (entry.ownership != IdeProjectOwnership.MANAGED && !userDirected)
                return Refinement.Rejected(IdeLifecycleFailure.USER_AUTHORIZATION_REQUIRED)
        }
        return Refinement.Refined(entry)
    }

    private fun hasPendingFor(target: IdeProjectTarget): Boolean =
        operations.values.any {
            it.result is IdeLifecycleResult.Pending &&
                ((it.command as? IdeLifecycleCommand.Open)?.root == target.root || target(it.command) == target)
        }

    private fun requiresIdle(command: IdeLifecycleCommand): Boolean =
        command is IdeLifecycleCommand.Sync ||
            command is IdeLifecycleCommand.ConfigureSync ||
            command is IdeLifecycleCommand.Close

    private fun joinOpen(command: IdeLifecycleCommand.Open, id: LifecycleRequest): LifecycleSubmission? {
        if (command.host != host.toString()) return existing(IdeLifecycleFailure.WRONG_HOST)
        if (projects.values.any { it.root.value == command.root && it.closing })
            return existing(IdeLifecycleFailure.PROJECT_BUSY)
        val joined =
            operations.values.firstOrNull {
                (it.command as? IdeLifecycleCommand.Open)?.root == command.root &&
                    it.result is IdeLifecycleResult.Pending
            } ?: return null
        val pending = (joined.result as IdeLifecycleResult.Pending).copy(requestId = id.value)
        operations[id] = Entry(command, pending)
        return LifecycleSubmission.Existing(pending)
    }

    private fun initialStage(command: IdeLifecycleCommand): IdeLifecycleStage =
        when (command) {
            is IdeLifecycleCommand.Open -> IdeLifecycleStage.OPENING
            is IdeLifecycleCommand.Present -> IdeLifecycleStage.PRESENTING
            is IdeLifecycleCommand.Close -> IdeLifecycleStage.CLOSING
            is IdeLifecycleCommand.ConfigureSync -> IdeLifecycleStage.ADMISSION
            else -> IdeLifecycleStage.IMPORTING
        }

    private fun existing(failure: IdeLifecycleFailure) = LifecycleSubmission.Existing(blocked(failure))

    @Synchronized
    fun complete(id: LifecycleRequest, result: IdeLifecycleResult) {
        val entry = operations[id] ?: return
        if (entry.result !is IdeLifecycleResult.Pending) return
        entry.result = result
        val command = entry.command
        if (command is IdeLifecycleCommand.Open) completeOpen(command, entry, result)
        if (command is IdeLifecycleCommand.Close) {
            val project = projects.values.singleOrNull { it.target == command.target }
            if (result is IdeLifecycleResult.Closed) projects.entries.removeIf { it.value == project }
            else project?.closing = false
        }
    }

    private fun completeOpen(command: IdeLifecycleCommand.Open, entry: Entry, result: IdeLifecycleResult) {

        operations.values
            .filter {
                (it === entry || it.result is IdeLifecycleResult.Pending) &&
                    (it.command as? IdeLifecycleCommand.Open)?.root == command.root
            }
            .forEach { joined ->
                if (joined.result is IdeLifecycleResult.Pending) joined.result = result
                if (result is IdeLifecycleResult.Opened) {
                    val open = joined.command as IdeLifecycleCommand.Open
                    projects.values
                        .singleOrNull { it.target == result.target }
                        ?.users
                        ?.add(LifecycleClient(open.client))
                }
            }
    }

    @Synchronized
    fun progress(id: LifecycleRequest, stage: IdeLifecycleStage) {
        val entry = operations[id] ?: return
        if (entry.result !is IdeLifecycleResult.Pending) return
        val open = entry.command as? IdeLifecycleCommand.Open
        operations.forEach { (request, operation) ->
            val joinedOpen = open != null && (operation.command as? IdeLifecycleCommand.Open)?.root == open.root
            if (operation.result is IdeLifecycleResult.Pending && (request == id || joinedOpen)) {
                operation.result = (operation.result as IdeLifecycleResult.Pending).copy(stage = stage)
            }
        }
    }

    @Synchronized
    fun status(id: LifecycleRequest): IdeLifecycleResult =
        operations[id]?.result ?: blocked(IdeLifecycleFailure.UNKNOWN_OPERATION)

    @Synchronized
    fun shutdown() {
        shutdown = true
        operations.values.forEach {
            if (it.result is IdeLifecycleResult.Pending) it.result = blocked(IdeLifecycleFailure.SHUTDOWN)
        }
    }

    private fun target(command: IdeLifecycleCommand): IdeProjectTarget? =
        when (command) {
            is IdeLifecycleCommand.Present -> command.target
            is IdeLifecycleCommand.Sync -> command.target
            is IdeLifecycleCommand.ConfigureSync -> command.target
            is IdeLifecycleCommand.Release -> command.target
            is IdeLifecycleCommand.Close -> command.target
            else -> null
        }

    private fun blocked(reason: IdeLifecycleFailure) = IdeLifecycleResult.Blocked(reason)
}

internal sealed interface LifecycleSubmission {
    data class Start(val pending: IdeLifecycleResult.Pending) : LifecycleSubmission

    data class Existing(val result: IdeLifecycleResult) : LifecycleSubmission
}

internal data class LifecycleRequest(val value: String)

internal data class LifecycleClient(val value: String)
