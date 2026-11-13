package com.intellij.psi.impl;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.psi.PsiTreeChangeEvent;
import java.util.Objects;

/** Experimental platform-owned cause. No dynamic scope or count subtraction is used. */
public final class PsiContentProvenance {
  private PsiContentProvenance() {}

  public sealed interface Cause permits Unattributed, Content {}

  public enum Unattributed implements Cause { INSTANCE }

  public static final class Content implements Cause {
    private final VirtualFile file;

    private Content(VFileContentChangeEvent event) {
      file = Objects.requireNonNull(event.getFile());
    }
  }

  /** Called only by the new no-cached-PSI content route, once per contribution. */
  public static void emit(PsiManagerEx manager, VFileContentChangeEvent source) {
    ApplicationManager.getApplication().assertWriteAccessAllowed();
    PsiTreeChangeEventImpl event = new PsiTreeChangeEventImpl(manager, new Content(source));
    event.setPropertyName(PsiTreeChangeEvent.PROP_UNLOADED_PSI);
    manager.beforePropertyChange(event);
    manager.propertyChanged(event);
  }

  /** Executed by the authoritative tracker before publishing its increment. */
  public static boolean affectsSemanticProject(Project project, Cause cause) {
    return switch (cause) {
      case Unattributed ignored -> true;
      case Content content -> {
        VirtualFile file = content.file;
        if (project.isDisposed() || !file.isValid() || file.isDirectory()) yield true;
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        yield index.isInSourceContent(file) || index.isInLibrary(file);
      }
    };
  }
}
