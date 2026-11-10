package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal class ColimaContainerRuntime(
    private val executable: Path,
    private val home: Path,
    private val architecture: ContainerArchitecture,
    private val process: ContainerProcess,
) {
    val runner: List<String> = listOf(executable.toString(), "nerdctl", "--profile", CONTAINER_PROFILE, "--")
    private val json = Json { ignoreUnknownKeys = true }

    fun prepare(
        command: ContainerCommand.Open,
        workspace: ContainerWorkspace,
    ): Refinement<ContainerPlan.RetainedContainer, ContainerFailure> {
        when (val runtime = start()) {
            is Refinement.Rejected -> return runtime
            is Refinement.Refined -> Unit
        }
        val name = "kast-work-${workspace.key(command.name, architecture)}"
        val presence =
            when (val result = presence(name, command, workspace)) {
                is Refinement.Rejected -> return result
                is Refinement.Refined -> result.value
            }
        val selection = presence.selection(command.image)
        val image =
            when (val result = image(selection)) {
                is Refinement.Rejected -> return result
                is Refinement.Refined -> result.value
            }
        val plan = ContainerPlan(workspace, command.name, architecture, image, command.network)
        if (presence == ContainerPresence.Absent) {
            when (val pinned = pin(image)) {
                is Refinement.Rejected -> return pinned
                is Refinement.Refined -> Unit
            }
            when (val created = create(plan)) {
                is Refinement.Rejected -> return created
                is Refinement.Refined -> Unit
            }
        }
        val retained =
            when (
                val result =
                    when (presence) {
                        ContainerPresence.Absent -> inspect(plan)
                        is ContainerPresence.Present -> ContainerPlan.RetainedContainer.admit(presence.scope, image)
                    }
            ) {
                is Refinement.Rejected -> return result
                is Refinement.Refined -> result.value
            }
        return resume(plan, retained)
    }

    private fun resume(
        plan: ContainerPlan,
        retained: ContainerPlan.RetainedContainer,
    ): Refinement<ContainerPlan.RetainedContainer, ContainerFailure> {
        if (retained.running) return Refinement.Refined(retained)
        when (val started = process.capture(runner + listOf("start", retained.id))) {
            is Refinement.Rejected -> return started
            is Refinement.Refined -> Unit
        }
        return inspect(plan)
    }

    fun profiles(): Refinement<List<ColimaProfile>, ContainerFailure> =
        when (val result = process.capture(listOf(executable.toString(), "list", "--json"))) {
            is Refinement.Rejected -> result
            is Refinement.Refined ->
                decode {
                    result.value
                        .lineSequence()
                        .filter { it.isNotBlank() }
                        .map { json.decodeFromString<ColimaProfile>(it) }
                        .toList()
                }
        }

    private fun start(): Refinement<Unit, ContainerFailure> {
        val availability =
            when (val result = availability()) {
                is Refinement.Rejected -> return result
                is Refinement.Refined -> result.value
            }
        if (availability == ContainerRuntimeAvailability.RUNNING) return Refinement.Refined(Unit)
        System.err.println("kast container: stage=runtime outcome=STARTING profile=$CONTAINER_PROFILE")
        val args =
            listOf(executable.toString(), "start", "--profile", CONTAINER_PROFILE) +
                if (availability == ContainerRuntimeAvailability.ABSENT)
                    listOf(
                        "--runtime",
                        "containerd",
                        "--arch",
                        architecture.vm,
                        "--vm-type",
                        "vz",
                        "--mount-type",
                        "virtiofs",
                        "--mount",
                        "$home:w",
                        "--mount",
                        "/private/tmp:w",
                        "--cpus",
                        "4",
                        "--memory",
                        "8",
                        "--disk",
                        "48",
                        "--ssh-agent=false",
                        "--activate=false",
                        "--template=false",
                    )
                else emptyList()
        when (val started = process.capture(args)) {
            is Refinement.Rejected -> return started
            is Refinement.Refined -> Unit
        }
        return when (val result = availability()) {
            is Refinement.Rejected -> result
            is Refinement.Refined ->
                if (result.value == ContainerRuntimeAvailability.RUNNING) Refinement.Refined(Unit)
                else Refinement.Rejected(ContainerFailure.RUNTIME_REJECTED)
        }
    }

    fun availability(): Refinement<ContainerRuntimeAvailability, ContainerFailure> =
        when (val result = profiles()) {
            is Refinement.Rejected -> result
            is Refinement.Refined -> admitColimaProfiles(result.value, architecture)
        }

    private fun create(plan: ContainerPlan): Refinement<String, ContainerFailure> {
        for (path in plan.workspace.mounts) {
            when (
                val shared =
                    process.capture(
                        listOf(
                            executable.toString(),
                            "ssh",
                            "--profile",
                            CONTAINER_PROFILE,
                            "--",
                            "test",
                            "-d",
                            path.toString(),
                        )
                    )
            ) {
                is Refinement.Rejected ->
                    return if (shared.failure == ContainerFailure.PROCESS_FAILED)
                        Refinement.Rejected(ContainerFailure.PATH_NOT_SHARED)
                    else shared
                is Refinement.Refined -> Unit
            }
        }
        System.err.println("kast container: stage=create workspace=${plan.workspace.root} cache=${plan.cache}")
        return process.capture(runner + plan.createArguments)
    }

    private fun image(selection: ContainerImageSelection): Refinement<ContainerImage, ContainerFailure> {
        val reference =
            when (selection) {
                ContainerImageSelection.Local -> "kast-dev:${architecture.oci}"
                is ContainerImageSelection.Digest -> selection.reference.removePrefix("sha256:")
            }
        when (val fetched = fetchImage(selection)) {
            is Refinement.Rejected -> return fetched
            is Refinement.Refined -> Unit
        }
        return readImage(reference, selection)
    }

    private fun pin(image: ContainerImage): Refinement<ContainerImage, ContainerFailure> {
        if (image.source == image.reference) return Refinement.Refined(image)
        when (val tagged = process.capture(runner + listOf("tag", image.source, image.reference))) {
            is Refinement.Rejected -> return tagged
            is Refinement.Refined -> Unit
        }
        val selection =
            when (val parsed = ContainerImageSelection.Digest.parse(image.reference)) {
                is Refinement.Rejected -> return parsed
                is Refinement.Refined -> parsed.value
            }
        return readImage(image.reference, selection)
    }

    private fun readImage(
        reference: String,
        selection: ContainerImageSelection,
    ): Refinement<ContainerImage, ContainerFailure> {
        val documents =
            when (val result = process.capture(runner + listOf("image", "inspect", "--mode", "native", reference))) {
                is Refinement.Rejected -> return result
                is Refinement.Refined ->
                    when (val decoded = decode { json.decodeFromString<List<ContainerImageDocument>>(result.value) }) {
                        is Refinement.Rejected -> return decoded
                        is Refinement.Refined -> decoded.value
                    }
            }
        return ContainerImage.admitAliases(documents, architecture, selection, reference)
    }

    private fun fetchImage(selection: ContainerImageSelection): Refinement<Unit, ContainerFailure> {
        // Pull only immutable registry selections. Local images stay offline and never trigger a build.
        if (selection is ContainerImageSelection.Digest && '@' in selection.reference) {
            val local =
                when (val result = process.capture(runner + listOf("images", "--quiet", "--no-trunc"))) {
                    is Refinement.Rejected -> return result
                    is Refinement.Refined -> result.value
                }
            val digest = selection.reference.substringAfterLast('@')
            if (digest !in local.lineSequence().toSet()) {
                System.err.println("kast container: stage=image outcome=PULLING")
                when (val pulled = process.capture(runner + listOf("pull", selection.reference))) {
                    is Refinement.Rejected -> return pulled
                    is Refinement.Refined -> Unit
                }
            }
        }

        return Refinement.Refined(Unit)
    }

    private fun presence(
        name: String,
        command: ContainerCommand.Open,
        workspace: ContainerWorkspace,
    ): Refinement<ContainerPresence, ContainerFailure> {
        val names =
            when (val result = process.capture(runner + listOf("ps", "--all", "--format", "{{.Names}}"))) {
                is Refinement.Rejected -> return result
                is Refinement.Refined -> result.value.lineSequence().toSet()
            }
        if (name !in names) return Refinement.Refined(ContainerPresence.Absent)
        return when (val result = readContainer(name)) {
            is Refinement.Rejected -> result
            is Refinement.Refined ->
                when (
                    val scope =
                        ContainerScope.admit(result.value, workspace, command.name, architecture, command.network)
                ) {
                    is Refinement.Rejected -> scope
                    is Refinement.Refined -> Refinement.Refined(ContainerPresence.Present(scope.value))
                }
        }
    }

    private fun inspect(plan: ContainerPlan): Refinement<ContainerPlan.RetainedContainer, ContainerFailure> =
        when (val result = readContainer(plan.container)) {
            is Refinement.Rejected -> result
            is Refinement.Refined -> plan.admit(result.value)
        }

    private fun readContainer(name: String): Refinement<ContainerDocument, ContainerFailure> =
        when (val result = process.capture(runner + listOf("inspect", name))) {
            is Refinement.Rejected -> result
            is Refinement.Refined ->
                when (val decoded = decode { json.decodeFromString<List<ContainerDocument>>(result.value) }) {
                    is Refinement.Rejected -> decoded
                    is Refinement.Refined ->
                        decoded.value.singleOrNull()?.let { Refinement.Refined(it) }
                            ?: Refinement.Rejected(ContainerFailure.WORKSPACE_CONFLICT)
                }
        }

    private fun <T> decode(operation: () -> T): Refinement<T, ContainerFailure> =
        try {
            Refinement.Refined(operation())
        } catch (_: SerializationException) {
            Refinement.Rejected(ContainerFailure.OUTPUT_REJECTED)
        } catch (_: IllegalArgumentException) {
            Refinement.Rejected(ContainerFailure.OUTPUT_REJECTED)
        }
}

private sealed interface ContainerPresence {
    data object Absent : ContainerPresence

    data class Present(val scope: ContainerScope) : ContainerPresence

    fun selection(requested: ContainerImageSelection): ContainerImageSelection =
        when (this) {
            Absent -> requested
            is Present -> if (requested == ContainerImageSelection.Local) scope.imageSelection else requested
        }
}

internal enum class ContainerRuntimeAvailability {
    ABSENT,
    STOPPED,
    RUNNING,
}

internal fun admitColimaProfiles(
    profiles: List<ColimaProfile>,
    architecture: ContainerArchitecture,
): Refinement<ContainerRuntimeAvailability, ContainerFailure> {
    val selected = profiles.filter { it.name == CONTAINER_PROFILE }
    if (selected.isEmpty()) return Refinement.Refined(ContainerRuntimeAvailability.ABSENT)
    val profile = selected.singleOrNull() ?: return Refinement.Rejected(ContainerFailure.RUNTIME_REJECTED)
    if (profile.arch != architecture.vm || profile.runtime != "containerd")
        return Refinement.Rejected(ContainerFailure.RUNTIME_REJECTED)
    return when (profile.status) {
        "Running" -> Refinement.Refined(ContainerRuntimeAvailability.RUNNING)
        "Stopped" -> Refinement.Refined(ContainerRuntimeAvailability.STOPPED)
        else -> Refinement.Rejected(ContainerFailure.RUNTIME_REJECTED)
    }
}
