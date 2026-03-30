package com.intellij.ide.plugins;

import com.intellij.platform.plugins.parser.impl.PluginDescriptorBuilder;
import com.intellij.platform.plugins.parser.impl.PluginDescriptorFromXmlStreamConsumer;
import com.intellij.platform.plugins.parser.impl.PluginDescriptorReaderContext;
import com.intellij.platform.plugins.parser.impl.XIncludeLoader;
import com.intellij.util.xml.dom.StaxFactory;
import org.codehaus.stax2.XMLStreamReader2;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Bridge PluginXmlPathResolver that implements the bridge {@link PathResolver} interface,
 * satisfying both the old 4-arg API (AA via kotlin-compiler.jar) and the new 3-arg API
 * (IJ 2025.3 PluginDescriptorLoader via app.jar).
 *
 * <p>Old-API methods delegate to the old {@code com.intellij.ide.plugins.XmlReader}
 * (available from the compat JAR extraction of kotlin-compiler.jar).
 *
 * <p>New-API methods use {@code PluginDescriptorFromXmlStreamConsumer} and the new
 * {@code com.intellij.platform.plugins.parser.impl.XmlReader} from app.jar.
 */
@SuppressWarnings({"rawtypes", "unchecked", "unused", "RedundantSuppression"})
public final class PluginXmlPathResolver implements PathResolver {

    /** Singleton used by PluginDescriptorLoader and AA's PluginStructureProvider. */
    public static final PathResolver DEFAULT_PATH_RESOLVER =
            new PluginXmlPathResolver(Collections.emptyList(), null);

    private final List<Path> pluginJarFiles;
    @SuppressWarnings("FieldCanBeLocal")
    private final Object zipFilePool; // Object — avoids old/new ZipFilePool type conflict

    /**
     * Constructor matching both old signature {@code (List<Path>, ZipFilePool)} and
     * new signature {@code (List<Path>, ZipEntryResolverPool)} via erasure to Object.
     */
    public PluginXmlPathResolver(List<? extends Path> pluginJarFiles, Object zipFilePool) {
        this.pluginJarFiles = new ArrayList<>(pluginJarFiles);
        this.zipFilePool = zipFilePool;
    }

    // ── Old API ─────────────────────────────────────────────────────────────────────────────

    @Override
    public boolean loadXIncludeReference(
            RawPluginDescriptor descriptor,
            ReadModuleContext context,
            DataLoader dataLoader,
            String base,
            String relativePath) {
        ResolvedResource resource = resolveResource(dataLoader, base, relativePath, /* ignoreNotFound = */ true);
        if (resource == null) return false;
        try (InputStream stream = resource.stream) {
            com.intellij.ide.plugins.XmlReader.readModuleDescriptor(
                    stream, context, this, dataLoader, resource.path, descriptor, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public RawPluginDescriptor resolvePath(
            ReadModuleContext readContext,
            DataLoader dataLoader,
            String relativePath,
            RawPluginDescriptor descriptor) {
        ResolvedResource resource = resolveResource(dataLoader, null, relativePath, /* ignoreNotFound = */ false);
        if (resource == null) return null;
        try (InputStream stream = resource.stream) {
            return com.intellij.ide.plugins.XmlReader.readModuleDescriptor(
                    stream, readContext, this, dataLoader, resource.path, descriptor, null);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public RawPluginDescriptor resolveModuleFile(
            ReadModuleContext readContext,
            DataLoader dataLoader,
            String relativePath,
            RawPluginDescriptor descriptor) {
        return resolvePath(readContext, dataLoader, relativePath, descriptor);
    }

    // ── New API ─────────────────────────────────────────────────────────────────────────────

    @Override
    public XIncludeLoader.LoadedXIncludeReference loadXIncludeReference(
            DataLoader dataLoader,
            String relativePath) {
        ResolvedResource resource = resolveResource(dataLoader, null, relativePath, /* ignoreNotFound = */ true);
        return resource == null ? null : new XIncludeLoader.LoadedXIncludeReference(resource.stream, resource.path);
    }

    @Override
    public PluginDescriptorBuilder resolvePath(
            PluginDescriptorReaderContext context,
            DataLoader dataLoader,
            String relativePath) {
        ResolvedResource resource = resolveResource(dataLoader, null, relativePath, /* ignoreNotFound = */ false);
        if (resource == null) return null;
        try (InputStream stream = resource.stream) {
            XIncludeLoader xil = PathResolverKt.toXIncludeLoader(this, dataLoader);
            PluginDescriptorFromXmlStreamConsumer consumer =
                    new PluginDescriptorFromXmlStreamConsumer(context, xil);
            XMLStreamReader2 reader = StaxFactory.createXmlStreamReader(stream);
            com.intellij.platform.plugins.parser.impl.XmlReader.readModuleDescriptor(
                    consumer, reader);
            return consumer.getBuilder();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public PluginDescriptorBuilder resolveModuleFile(
            PluginDescriptorReaderContext context,
            DataLoader dataLoader,
            String relativePath) {
        return resolvePath(context, dataLoader, relativePath);
    }

    private ResolvedResource resolveResource(
            DataLoader dataLoader,
            String base,
            String relativePath,
            boolean ignoreNotFound) {
        for (String candidatePath : candidatePaths(base, relativePath)) {
            try {
                InputStream stream = dataLoader.load(candidatePath, ignoreNotFound);
                if (stream != null) {
                    return new ResolvedResource(stream, candidatePath);
                }
            } catch (Exception ignored) {
                // Try the next candidate path. The bridge intentionally preserves lenient loading.
            }
        }
        return null;
    }

    private List<String> candidatePaths(String base, String relativePath) {
        Set<String> candidates = new LinkedHashSet<>();
        addCandidate(candidates, normalizePath(relativePath));
        addCandidate(candidates, stripLeadingSlash(normalizePath(relativePath)));

        if (base != null && relativePath != null && !relativePath.startsWith("/")) {
            String resolvedPath = resolveAgainstBase(base, relativePath);
            addCandidate(candidates, resolvedPath);
            addCandidate(candidates, stripLeadingSlash(resolvedPath));
        }

        return new ArrayList<>(candidates);
    }

    private void addCandidate(Set<String> candidates, String candidatePath) {
        if (candidatePath == null || candidatePath.isEmpty()) {
            return;
        }
        candidates.add(candidatePath);
    }

    private String resolveAgainstBase(String base, String relativePath) {
        String normalizedBase = normalizePath(base);
        int lastSlash = normalizedBase.lastIndexOf('/');
        String directory = lastSlash >= 0 ? normalizedBase.substring(0, lastSlash + 1) : "";
        return normalizePath(directory + relativePath);
    }

    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }

        boolean absolute = path.startsWith("/");
        String[] segments = path.split("/");
        Deque<String> normalizedSegments = new ArrayDeque<>();
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!normalizedSegments.isEmpty()) {
                    normalizedSegments.removeLast();
                }
                continue;
            }
            normalizedSegments.addLast(segment);
        }

        String joined = String.join("/", normalizedSegments);
        if (!absolute) {
            return joined;
        }
        return joined.isEmpty() ? "/" : "/" + joined;
    }

    private String stripLeadingSlash(String path) {
        if (path == null || path.isEmpty() || !path.startsWith("/")) {
            return path;
        }
        return path.substring(1);
    }

    private static final class ResolvedResource {
        private final InputStream stream;
        private final String path;

        private ResolvedResource(InputStream stream, String path) {
            this.stream = stream;
            this.path = path;
        }
    }
}
