package io.github.amichne.kast.headless;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.project.DumbService;
import org.jetbrains.plugins.gradle.service.project.open.GradleProjectImportUtil;
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings;
import org.jetbrains.plugins.gradle.util.GradleConstants;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class HeadlessGradleProjectImportBridge {
    private HeadlessGradleProjectImportBridge() {
    }

    public static boolean canLinkAndRefreshGradleProject(String externalProjectPath, Project project) {
        return GradleProjectImportUtil.canLinkAndRefreshGradleProject(externalProjectPath, project);
    }

    public static void linkAndImportGradleProject(Project project, String externalProjectPath) {
        CompletableFuture<Void> importFuture = new CompletableFuture<>();
        try {
            GradleProjectSettings linkSettings =
                GradleProjectImportUtil.createLinkSettings(Path.of(externalProjectPath), project);
            ImportSpecBuilder importSpec = new ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
                .withCallback(importFuture);
            ExternalSystemUtil.linkExternalProject(linkSettings, importSpec);
            importFuture.get(5, TimeUnit.MINUTES);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while importing Gradle project: " + externalProjectPath, error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            throw new IllegalStateException("Gradle project import failed: " + externalProjectPath, cause);
        } catch (TimeoutException error) {
            throw new IllegalStateException("Timed out importing Gradle project: " + externalProjectPath, error);
        }
    }

    public static void awaitSmartMode(Project project) {
        DumbService.getInstance(project).waitForSmartMode();
    }
}
