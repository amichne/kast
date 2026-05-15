package com.intellij.openapi.fileTypes;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.util.KeyedLazyInstance;

/**
 * Guarded drop-in replacement for the IJ 2025.3 BinaryFileTypeDecompilers.
 *
 * <p>The standalone Analysis API host disposes the application environment in-process.
 * During teardown, extension change notifications can race with application shutdown and
 * queue an EDT task after the application has already been cleared from ApplicationManager.
 * The stock implementation then throws from FileDocumentManager.getInstance().</p>
 */
public final class BinaryFileTypeDecompilers extends FileTypeExtension<BinaryFileDecompiler> {
    private static final ExtensionPointName<KeyedLazyInstance<BinaryFileDecompiler>> EP_NAME =
            new ExtensionPointName<>("com.intellij.filetype.decompiler");
    private static final BinaryFileTypeDecompilers FALLBACK_INSTANCE = new BinaryFileTypeDecompilers(false);

    public BinaryFileTypeDecompilers() {
        this(true);
    }

    private BinaryFileTypeDecompilers(boolean registerChangeListener) {
        super(EP_NAME);

        Application application = ApplicationManager.getApplication();
        if (registerChangeListener && application != null && !application.isUnitTestMode()) {
            EP_NAME.addChangeListener(this::notifyDecompilerSetChange, application);
        }
    }

    public void notifyDecompilerSetChange() {
        Application application = ApplicationManager.getApplication();
        if (application == null || application.isDisposed()) {
            return;
        }

        application.invokeLater(() -> {
            Application currentApplication = ApplicationManager.getApplication();
            if (currentApplication == null || currentApplication.isDisposed()) {
                return;
            }

            FileDocumentManager.getInstance().reloadBinaryFiles();
        }, ModalityState.nonModal());
    }

    public static BinaryFileTypeDecompilers getInstance() {
        Application application = ApplicationManager.getApplication();
        if (application == null || application.isDisposed()) {
            return FALLBACK_INSTANCE;
        }

        BinaryFileTypeDecompilers service = application.getService(BinaryFileTypeDecompilers.class);
        return service != null ? service : FALLBACK_INSTANCE;
    }
}
