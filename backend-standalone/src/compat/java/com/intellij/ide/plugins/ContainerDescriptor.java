package com.intellij.ide.plugins;

import com.intellij.openapi.components.ServiceDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hybrid ContainerDescriptor that satisfies two incompatible API versions simultaneously:
 *
 * <p><b>Old API</b> (compiled into AA 2.3.20-ij253-87 via kotlin-compiler.jar):
 * <ul>
 *   <li>no-arg constructor</li>
 *   <li>{@code getServices()} method</li>
 *   <li>{@code addService(ServiceDescriptor)} method</li>
 *   <li>{@code getDistinctExtensionPointCount()}/{@code setDistinctExtensionPointCount(int)}</li>
 *   <li>mutable public fields {@code listeners}, {@code extensionPoints}</li>
 * </ul>
 *
 * <p><b>New API</b> (IJ 2025.3 platform, used by {@code ParserElementsConversionKt.convert()}):
 * <ul>
 *   <li>{@code ContainerDescriptor(List, List, List, List)} 4-arg constructor</li>
 * </ul>
 *
 * <p>This class is placed first on the classpath via ide-plugin-compat.jar so it wins the
 * class-loading race over both the old class in kotlin-compiler.jar and the new class in app.jar.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class ContainerDescriptor {

    // Public services field for the new API plus getter/addService for the old API.
    public List services = new ArrayList();

    // Public mutable fields (old API) — AA reads these directly via getfield bytecode.
    public List components = new ArrayList();
    public List listeners = new ArrayList();
    public List extensionPoints = new ArrayList();
    public transient Map extensions;

    private int distinctExtensionPointCount;

    /** No-arg constructor from the old API. */
    public ContainerDescriptor() {}

    /**
     * 4-arg constructor required by IJ 2025.3's {@code ParserElementsConversionKt.convert()}.
     * Parameter order: services, components, listeners, extensionPoints.
     */
    public ContainerDescriptor(List services, List components, List listeners, List extensionPoints) {
        this.services = services != null ? services : new ArrayList();
        this.components = components != null ? components : new ArrayList();
        this.listeners = listeners != null ? listeners : new ArrayList();
        this.extensionPoints = extensionPoints != null ? extensionPoints : new ArrayList();
    }

    /** Returns the services list. Called by AA's {@code PluginStructureProvider.getServices()}. */
    public List getServices() {
        return services;
    }

    /** Adds a service descriptor. Part of the old API used by some AA initialisation paths. */
    public void addService(ServiceDescriptor descriptor) {
        services.add(descriptor);
    }

    public int getDistinctExtensionPointCount() {
        return distinctExtensionPointCount;
    }

    public void setDistinctExtensionPointCount(int distinctExtensionPointCount) {
        this.distinctExtensionPointCount = distinctExtensionPointCount;
    }

    @Override
    public String toString() {
        return "ContainerDescriptor(services=" + services
                + ", components=" + components
                + ", listeners=" + listeners
                + ", extensionPoints=" + extensionPoints + ")";
    }
}
