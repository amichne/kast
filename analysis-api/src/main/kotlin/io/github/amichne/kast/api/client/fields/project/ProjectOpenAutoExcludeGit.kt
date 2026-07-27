package io.github.amichne.kast.api.client.fields

data class ProjectOpenAutoExcludeGit(
    override val value: Boolean,
) : ConfigurationField<Boolean>() {
    override val section: String get() = "projectOpen"
    override val key: String get() = "autoExcludeGit"
    override val default: ConfigurationDefault<Boolean> get() = ConfigurationDefault(true)
}
