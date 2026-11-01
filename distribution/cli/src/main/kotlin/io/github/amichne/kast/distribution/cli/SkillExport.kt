package io.github.amichne.kast.distribution.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** The complete staged tree is published with a single no-replace operation. */
internal fun exportCliSkill(
    root: Path,
    parent: Path,
    publish: (Path, Path) -> DirectoryPublication = ::publishDirectoryNoReplace,
): ProvisionOutcome {
    val destination = parent.toAbsolutePath().normalize().resolve("kast")
    val skill =
        when (val admission = AdmittedCliSkill.admit(root)) {
            is SkillAdmission.Admitted -> admission.skill
            is SkillAdmission.Rejected ->
                return skillRejection(destination, ProvisionStage.PAYLOAD_ADMISSION, admission.reason)
        }
    return try {
        exportAdmittedSkill(skill, destination, publish)
    } catch (_: IOException) {
        skillRejection(destination, ProvisionStage.STAGING, ProvisionFailure.DESTINATION_UNAVAILABLE)
    } catch (_: SecurityException) {
        skillRejection(destination, ProvisionStage.STAGING, ProvisionFailure.DESTINATION_UNAVAILABLE)
    }
}

private fun exportAdmittedSkill(
    skill: AdmittedCliSkill,
    destination: Path,
    publish: (Path, Path) -> DirectoryPublication,
): ProvisionOutcome {
    if (Files.exists(destination, NOFOLLOW_LINKS)) return existingSkill(skill, destination)
    val ancestor = generateSequence(destination.parent) { it.parent }.first { Files.exists(it, NOFOLLOW_LINKS) }
    if (!Files.isDirectory(ancestor, NOFOLLOW_LINKS) || ancestor.toRealPath() != ancestor)
        return skillRejection(destination, ProvisionStage.DESTINATION_ADMISSION, ProvisionFailure.UNSUPPORTED_FILE_KIND)
    Files.createDirectories(destination.parent)
    return publishStagedSkill(skill, destination, publish)
}

private fun publishStagedSkill(
    skill: AdmittedCliSkill,
    destination: Path,
    publish: (Path, Path) -> DirectoryPublication,
): ProvisionOutcome {
    val staged = Files.createTempDirectory(destination.parent, ".kast-skill-")
    return try {
        skill.materialize(staged)
        publicationOutcome(publish(staged, destination), skill, destination)
    } finally {
        discardSkillStage(staged)
    }
}

private fun publicationOutcome(
    publication: DirectoryPublication,
    skill: AdmittedCliSkill,
    destination: Path,
): ProvisionOutcome =
    when (publication) {
        DirectoryPublication.PUBLISHED -> ProvisionOutcome.Installed(destination.toString())
        DirectoryPublication.TARGET_EXISTS -> existingSkill(skill, destination)
        DirectoryPublication.UNSUPPORTED ->
            skillRejection(destination, ProvisionStage.PUBLICATION, ProvisionFailure.PLATFORM_UNSUPPORTED)
        DirectoryPublication.IO_FAILURE ->
            skillRejection(destination, ProvisionStage.PUBLICATION, ProvisionFailure.DESTINATION_UNAVAILABLE)
    }

private fun existingSkill(skill: AdmittedCliSkill, destination: Path): ProvisionOutcome =
    if (!Files.isDirectory(destination, NOFOLLOW_LINKS))
        skillRejection(destination, ProvisionStage.DESTINATION_ADMISSION, ProvisionFailure.UNSUPPORTED_FILE_KIND)
    else if (skill.identical(destination)) ProvisionOutcome.AlreadyInstalled(destination.toString())
    else skillRejection(destination, ProvisionStage.DESTINATION_ADMISSION, ProvisionFailure.SKILL_EXISTS)

private fun discardSkillStage(staged: Path) {
    if (Files.exists(staged, NOFOLLOW_LINKS))
        Files.walk(staged).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
}

private fun skillRejection(
    destination: Path,
    stage: ProvisionStage,
    reason: ProvisionFailure,
): ProvisionOutcome.Rejected = ProvisionOutcome.Rejected(stage, reason, destination.toString())
