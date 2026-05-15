package com.intellij.ide.plugins;

import com.intellij.platform.plugins.parser.impl.PluginDescriptorBuilder;
import com.intellij.platform.plugins.parser.impl.PluginDescriptorReaderContext;
import com.intellij.platform.plugins.parser.impl.XIncludeLoader;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Bridge PathResolver that satisfies two incompatible API versions simultaneously:
 *
 * <p><b>Old API</b> (compiled into AA 2.3.20-ij253-87 via kotlin-compiler.jar):
 * <ul>
 *   <li>5-arg {@code loadXIncludeReference(RawPluginDescriptor, ReadModuleContext, DataLoader, String, String)}</li>
 *   <li>4-arg {@code resolvePath(ReadModuleContext, DataLoader, String, RawPluginDescriptor)}</li>
 *   <li>4-arg {@code resolveModuleFile(ReadModuleContext, DataLoader, String, RawPluginDescriptor)}</li>
 * </ul>
 *
 * <p><b>New API</b> (IJ 2025.3 platform, used by {@code PluginDescriptorLoader}):
 * <ul>
 *   <li>2-arg {@code loadXIncludeReference(DataLoader, String)}</li>
 *   <li>3-arg {@code resolvePath(PluginDescriptorReaderContext, DataLoader, String)}</li>
 *   <li>3-arg {@code resolveModuleFile(PluginDescriptorReaderContext, DataLoader, String)}</li>
 * </ul>
 *
 * <p>Java method overloading allows both sets to coexist since the parameter counts differ.
 * This class is placed first on the classpath via ide-plugin-compat.jar to win the
 * class-loading race over both the old PathResolver in kotlin-compiler.jar and the new
 * PathResolver in app.jar.
 */
@SuppressWarnings({"unused", "RedundantSuppression"})
public interface PathResolver {

    default boolean isFlat() {
        return false;
    }

    // ── Old API (called by AA's PluginStructureProvider via kotlin-compiler.jar) ──────────────

    boolean loadXIncludeReference(
            RawPluginDescriptor descriptor,
            ReadModuleContext context,
            DataLoader dataLoader,
            String base,
            String relativePath);

    RawPluginDescriptor resolvePath(
            ReadModuleContext readContext,
            DataLoader dataLoader,
            String relativePath,
            RawPluginDescriptor descriptor);

    RawPluginDescriptor resolveModuleFile(
            ReadModuleContext readContext,
            DataLoader dataLoader,
            String relativePath,
            RawPluginDescriptor descriptor);

    // ── New API (called by IJ 2025.3 PluginDescriptorLoader from app.jar) ────────────────────

    XIncludeLoader.LoadedXIncludeReference loadXIncludeReference(
            DataLoader dataLoader,
            String relativePath);

    PluginDescriptorBuilder resolvePath(
            PluginDescriptorReaderContext context,
            DataLoader dataLoader,
            String relativePath);

    PluginDescriptorBuilder resolveModuleFile(
            PluginDescriptorReaderContext context,
            DataLoader dataLoader,
            String relativePath);

    // ── New default method added in IJ 2025.3 ─────────────────────────────────────────────────

    default List<Path> resolveCustomModuleClassesRoots(PluginModuleId moduleId) {
        return Collections.emptyList();
    }
}
