package io.github.amichne.kast.workspace.intellij;

import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder;
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import org.jetbrains.plugins.gradle.service.project.open.GradleProjectImportUtil;
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings;
import org.jetbrains.plugins.gradle.settings.GradleSettings;
import org.jetbrains.plugins.gradle.settings.GradleSystemSettings;
import org.jetbrains.plugins.gradle.util.GradleConstants;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Sole live IntelliJ effect adapter for workspace refresh and Gradle import initiation.
 *
 * <p>Every method consumes its live project or VFS objects synchronously and returns only detached
 * values or caller-owned futures. No IntelliJ or Gradle model object escapes this boundary.</p>
 */
public final class IntellijWorkspaceEffects {
    private IntellijWorkspaceEffects() {
    }

    public static void configureDependencySourceDownloads(boolean enabled) {
        GradleSystemSettings.getInstance().setDownloadSources(enabled);
    }

    public static void configureGradleVmOptions(Project project, String options) {
        GradleSettings.getInstance(project).setGradleVmOptions(options);
    }

    public static String gradleVmOptions(Project project) {
        return GradleSettings.getInstance(project).getGradleVmOptions();
    }

    public static boolean canLinkAndRefreshGradleProject(String externalProjectPath, Project project) {
        return isGradleProjectLinked(project, externalProjectPath)
            || GradleProjectImportUtil.canLinkAndRefreshGradleProject(externalProjectPath, project, false);
    }

    public static boolean isGradleProjectLinked(Project project, String externalProjectPath) {
        String expectedPath = normalize(Path.of(externalProjectPath));
        return GradleSettings.getInstance(project).getLinkedProjectsSettings().stream()
            .map(GradleProjectSettings::getExternalProjectPath)
            .filter(Objects::nonNull)
            .filter(path -> !path.isBlank())
            .map(Path::of)
            .map(IntellijWorkspaceEffects::normalize)
            .anyMatch(expectedPath::equals);
    }

    public static void linkOrRefreshGradleProject(
        Project project,
        String externalProjectPath,
        CompletableFuture<Void> importFuture
    ) {
        ImportSpecBuilder importSpec = new ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
            .withCallback(importFuture);
        if (isGradleProjectLinked(project, externalProjectPath)) {
            ExternalSystemUtil.refreshProject(externalProjectPath, importSpec);
        } else {
            GradleProjectSettings linkSettings =
                GradleProjectImportUtil.createLinkSettings(Path.of(externalProjectPath), project);
            ExternalSystemUtil.linkExternalProject(linkSettings, importSpec);
        }
    }

    public static void linkExternalGradleProject(
        Project project,
        Path externalProjectPath,
        CompletableFuture<Void> importFuture
    ) {
        GradleProjectSettings linkSettings = new GradleProjectSettings(normalize(externalProjectPath));
        ExternalSystemUtil.linkExternalProject(linkSettings, backgroundImportSpec(project, importFuture));
    }

    public static void refreshExternalGradleProject(
        Project project,
        Path externalProjectPath,
        CompletableFuture<Void> importFuture
    ) {
        ExternalSystemUtil.refreshProject(
            normalize(externalProjectPath),
            backgroundImportSpec(project, importFuture)
        );
    }

    public static void refreshNioFiles(Collection<Path> roots) {
        LocalFileSystem.getInstance().refreshNioFiles(roots, false, true, () -> {
        });
    }

    private static ImportSpecBuilder backgroundImportSpec(
        Project project,
        CompletableFuture<Void> importFuture
    ) {
        return new ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
            .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
            .withImportProjectData(true)
            .withActivateToolWindowOnStart(false)
            .withActivateToolWindowOnFailure(false)
            .dontNavigateToError()
            .dontReportRefreshErrors()
            .withCallback(importFuture);
    }

    private static String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}
