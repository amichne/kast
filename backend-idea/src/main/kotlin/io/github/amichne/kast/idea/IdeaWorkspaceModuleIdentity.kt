package io.github.amichne.kast.idea

@JvmInline
internal value class IdeaWorkspaceModuleIdentity private constructor(
    val value: String,
) : Comparable<IdeaWorkspaceModuleIdentity> {
    override fun compareTo(other: IdeaWorkspaceModuleIdentity): Int = value.compareTo(other.value)

    companion object {
        fun of(value: String): IdeaWorkspaceModuleIdentity {
            require(value.isNotBlank()) { "IDEA workspace module identity must be nonblank" }
            return IdeaWorkspaceModuleIdentity(value)
        }
    }
}
