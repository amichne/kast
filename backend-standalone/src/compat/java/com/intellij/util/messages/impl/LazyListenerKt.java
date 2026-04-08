package com.intellij.util.messages.impl;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.extensions.ExtensionNotApplicableException;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.progress.Cancellation;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.util.messages.ListenerDescriptor;
import com.intellij.util.messages.MessageBusOwner;
import com.intellij.util.messages.Topic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Compatibility shim for IJ 2025.3's lazy-listener helpers.
 *
 * <p>The standalone runtime intentionally shadows a few plugin parser classes with a compat JAR
 * so the Kotlin Analysis API can coexist with the newer IDEA runtime. In that mixed setup,
 * `MessageBusImpl.topicClassToListenerDescriptor` can occasionally contain raw legacy
 * {@link ListenerDescriptor} instances where the newer runtime expects wrapped
 * {@link PluginListenerDescriptor} values. The stock IJ implementation casts eagerly and crashes
 * with {@link ClassCastException}.
 *
 * <p>This shim accepts either representation and normalizes raw descriptors on the fly.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class LazyListenerKt {
    private LazyListenerKt() {}

    public static void subscribeLazyListeners(
            Topic<?> topic,
            Map<String, List<PluginListenerDescriptor>> topicClassToListenerDescriptor,
            ConcurrentLinkedQueue<MessageBusImpl.MessageHandlerHolder> subscribers,
            MessageBusOwner owner) {
        try (AccessToken ignored = Cancellation.withNonCancelableSection()) {
            List<?> descriptors = (List<?>) topicClassToListenerDescriptor.remove(topic.getListenerClass().getName());
            if (descriptors == null) {
                return;
            }

            Map<PluginDescriptor, List<Object>> handlersByPlugin = new LinkedHashMap<>();
            for (Object candidate : descriptors) {
                PluginListenerDescriptor descriptor = normalizeDescriptor(candidate);
                if (descriptor == null) {
                    continue;
                }

                List<Object> handlers = handlersByPlugin.computeIfAbsent(
                        descriptor.getPluginDescriptor(),
                        unused -> new ArrayList<>());
                try {
                    Object listener = Objects.requireNonNull(owner.createListener(descriptor), "createListener(...)");
                    handlers.add(listener);
                }
                catch (ExtensionNotApplicableException ignoredException) {
                    // Keep parity with the platform implementation: simply skip this listener.
                }
                catch (ProcessCanceledException exception) {
                    throw exception;
                }
                catch (Throwable exception) {
                    // Standalone mode: MockComponentManager throws UnsupportedOperationException
                    // from createListener. Fall back to direct reflective instantiation so that
                    // K2 FIR session invalidation listeners (and any other lazy listeners) are
                    // still registered.
                    Object fallback = instantiateListenerDirectly(descriptor);
                    if (fallback != null) {
                        handlers.add(fallback);
                    } else {
                        MessageBusImpl.LOG.error("Cannot create listener", exception);
                    }
                }
            }

            for (Map.Entry<PluginDescriptor, List<Object>> entry : handlersByPlugin.entrySet()) {
                subscribers.add(new DescriptorBasedMessageBusConnection(entry.getKey(), topic, entry.getValue()));
            }
        }
    }

    public static boolean unsubscribeLazyListeners(
            IdeaPluginDescriptor module,
            List<ListenerDescriptor> listenerDescriptors,
            Map<String, List<PluginListenerDescriptor>> topicClassToListenerDescriptor,
            ConcurrentLinkedQueue<MessageBusImpl.MessageHandlerHolder> subscribers) {
        Iterator<Map.Entry<String, List<PluginListenerDescriptor>>> mapIterator =
                topicClassToListenerDescriptor.entrySet().iterator();
        while (mapIterator.hasNext()) {
            Map.Entry<String, List<PluginListenerDescriptor>> entry = mapIterator.next();
            List descriptors = entry.getValue();
            if (removeDescriptorsForModule(module, descriptors) && descriptors.isEmpty()) {
                mapIterator.remove();
            }
        }

        if (listenerDescriptors.isEmpty() || subscribers.isEmpty()) {
            return false;
        }

        Map<String, Set<String>> topicClassToListenerClassNames = new HashMap<>();
        for (ListenerDescriptor descriptor : listenerDescriptors) {
            topicClassToListenerClassNames
                    .computeIfAbsent(descriptor.topicClassName, unused -> new HashSet<>())
                    .add(descriptor.listenerClassName);
        }

        boolean changed = false;
        List<DescriptorBasedMessageBusConnection> replacementConnections = null;
        Iterator<MessageBusImpl.MessageHandlerHolder> iterator = subscribers.iterator();
        while (iterator.hasNext()) {
            MessageBusImpl.MessageHandlerHolder holder = iterator.next();
            if (!(holder instanceof DescriptorBasedMessageBusConnection)) {
                continue;
            }

            DescriptorBasedMessageBusConnection connection = (DescriptorBasedMessageBusConnection) holder;
            if (connection.module != module) {
                continue;
            }

            Set<String> listenerClassNames =
                    topicClassToListenerClassNames.get(connection.topic.getListenerClass().getName());
            if (listenerClassNames == null) {
                continue;
            }

            List<Object> newHandlers = computeNewHandlers(connection.handlers, listenerClassNames);
            if (newHandlers == null) {
                continue;
            }

            changed = true;
            iterator.remove();
            if (!newHandlers.isEmpty()) {
                if (replacementConnections == null) {
                    replacementConnections = new ArrayList<>();
                }
                replacementConnections.add(new DescriptorBasedMessageBusConnection(module, connection.topic, newHandlers));
            }
        }

        if (replacementConnections != null) {
            subscribers.addAll(replacementConnections);
        }
        return changed;
    }

    private static PluginListenerDescriptor normalizeDescriptor(Object candidate) {
        if (candidate instanceof PluginListenerDescriptor) {
            return (PluginListenerDescriptor) candidate;
        }
        if (candidate instanceof ListenerDescriptor) {
            ListenerDescriptor descriptor = (ListenerDescriptor) candidate;
            PluginDescriptor pluginDescriptor = extractPluginDescriptor(descriptor);
            if (pluginDescriptor == null) {
                MessageBusImpl.LOG.warn("Skipping lazy listener without plugin descriptor: " + descriptor.listenerClassName);
                return null;
            }
            return new PluginListenerDescriptor(descriptor, pluginDescriptor);
        }

        MessageBusImpl.LOG.warn("Skipping unexpected lazy listener descriptor: " + candidate);
        return null;
    }

    private static boolean removeDescriptorsForModule(IdeaPluginDescriptor module, List descriptors) {
        boolean changed = false;
        Iterator iterator = descriptors.iterator();
        while (iterator.hasNext()) {
            Object descriptor = iterator.next();
            if (matchesModule(module, descriptor)) {
                iterator.remove();
                changed = true;
            }
        }
        return changed;
    }

    private static boolean matchesModule(IdeaPluginDescriptor module, Object descriptor) {
        if (descriptor instanceof PluginListenerDescriptor) {
            return ((PluginListenerDescriptor) descriptor).getPluginDescriptor() == module;
        }
        if (descriptor instanceof ListenerDescriptor) {
            return extractPluginDescriptor((ListenerDescriptor) descriptor) == module;
        }
        return false;
    }

    private static PluginDescriptor extractPluginDescriptor(ListenerDescriptor descriptor) {
        try {
            Object value = descriptor.getClass().getField("pluginDescriptor").get(descriptor);
            return value instanceof PluginDescriptor ? (PluginDescriptor) value : null;
        }
        catch (NoSuchFieldException | IllegalAccessException ignored) {
            return null;
        }
    }

    private static List<Object> computeNewHandlers(List<?> handlers, Set<String> listenerClassNames) {
        List<Object> filteredHandlers = null;
        for (int index = 0; index < handlers.size(); index++) {
            Object handler = handlers.get(index);
            if (listenerClassNames.contains(handler.getClass().getName())) {
                if (filteredHandlers == null) {
                    filteredHandlers = index == 0
                            ? new ArrayList<>()
                            : new ArrayList<>(handlers.subList(0, index));
                }
            }
            else if (filteredHandlers != null) {
                filteredHandlers.add(handler);
            }
        }
        return filteredHandlers;
    }

    /**
     * Reflective fallback for standalone mode where {@code MockComponentManager.createListener}
     * throws {@link UnsupportedOperationException}. Loads the listener class using the plugin
     * descriptor's classloader and instantiates it via the no-arg constructor.
     *
     * @return the instantiated listener, or {@code null} if instantiation fails
     */
    private static Object instantiateListenerDirectly(PluginListenerDescriptor descriptor) {
        try {
            String listenerClassName = descriptor.getDescriptor().listenerClassName;
            ClassLoader classLoader = descriptor.getPluginDescriptor().getPluginClassLoader();
            if (classLoader == null) {
                classLoader = Thread.currentThread().getContextClassLoader();
            }
            if (classLoader == null) {
                classLoader = LazyListenerKt.class.getClassLoader();
            }
            Class<?> listenerClass = Class.forName(listenerClassName, true, classLoader);
            java.lang.reflect.Constructor<?> constructor = listenerClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Throwable fallbackException) {
            return null;
        }
    }
}
