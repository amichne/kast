package io.github.amichne.kast.idea;

import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder;
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.project.Project;
import org.jetbrains.plugins.gradle.service.project.open.GradleProjectImportUtil;
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings;
import org.jetbrains.plugins.gradle.settings.GradleSettings;
import org.jetbrains.plugins.gradle.util.GradleConstants;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class IdeaGradleProjectLoadBridge {
    private IdeaGradleProjectLoadBridge() {
    }

    public static boolean isExternalGradleProjectLinked(Project project, Path externalProjectPath) {
        String normalizedExternalProjectPath = normalizePath(externalProjectPath);
        return GradleSettings.getInstance(project).getLinkedProjectsSettings().stream()
            .map(GradleProjectSettings::getExternalProjectPath)
            .filter(Objects::nonNull)
            .map(Path::of)
            .map(IdeaGradleProjectLoadBridge::normalizePath)
            .anyMatch(normalizedExternalProjectPath::equals);
    }

    public static void linkExternalGradleProject(
        Project project,
        Path externalProjectPath,
        CompletableFuture<Void> importFuture
    ) {
        GradleProjectSettings linkSettings =
            GradleProjectImportUtil.createLinkSettings(externalProjectPath, project);
        ImportSpecBuilder importSpec = importSpec(project, importFuture);
        ExternalSystemUtil.linkExternalProject(linkSettings, importSpec);
    }

    public static void refreshExternalGradleProject(
        Project project,
        Path externalProjectPath,
        CompletableFuture<Void> importFuture
    ) {
        ExternalSystemUtil.refreshProject(
            externalProjectPath.toAbsolutePath().normalize().toString(),
            importSpec(project, importFuture)
        );
    }

    private static ImportSpecBuilder importSpec(Project project, CompletableFuture<Void> importFuture) {
        return new ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
            .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
            .withImportProjectData(true)
            .withActivateToolWindowOnStart(false)
            .withActivateToolWindowOnFailure(false)
            .dontNavigateToError()
            .dontReportRefreshErrors()
            .withCallback(importFuture);
    }

    private static String normalizePath(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}
