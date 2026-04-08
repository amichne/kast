
package io.github.amichne.kast.standalone

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.extensions.ExtensionDescriptor
import com.intellij.openapi.extensions.PluginId
import com.intellij.util.messages.ListenerDescriptor
import com.intellij.util.messages.MessageBusOwner
import com.intellij.util.messages.Topic
import com.intellij.util.messages.impl.LazyListenerKt
import com.intellij.util.messages.impl.MessageBusImpl
import com.intellij.util.messages.impl.PluginListenerDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "UnstableApiUsage")
class LazyListenerCompatTest {
    @Test
    fun `subscribe lazy listeners tolerates raw listener descriptors stored in wrapped map`() {
        val module = testPluginDescriptor("sample.plugin")
        val topic = Topic.create("sample-topic", TestTopicListener::class.java)
        val rawDescriptor = listenerDescriptor(
            module = module,
            topic = topic,
            listenerClass = TestListenerHandler::class.java,
        )
        val storedDescriptors = mutableListOf<PluginListenerDescriptor>()
        @Suppress("UNCHECKED_CAST")
        (storedDescriptors as MutableList<Any>).add(rawDescriptor)
        val topicClassToListenerDescriptor = ConcurrentHashMap<String, MutableList<PluginListenerDescriptor>>()
        topicClassToListenerDescriptor[topic.listenerClass.name] = storedDescriptors
        val subscribers = ConcurrentLinkedQueue<MessageBusImpl.MessageHandlerHolder>()
        val seenDescriptors = mutableListOf<PluginListenerDescriptor>()
        val owner = object : MessageBusOwner {
            override fun createListener(descriptor: PluginListenerDescriptor): Any {
                seenDescriptors += descriptor
                return TestListenerHandler()
            }

            override fun isDisposed(): Boolean = false
        }

        LazyListenerKt.subscribeLazyListeners(
            topic,
            topicClassToListenerDescriptor,
            subscribers,
            owner,
        )

        assertTrue(topicClassToListenerDescriptor.isEmpty())
        assertEquals(1, seenDescriptors.size)
        assertSame(rawDescriptor, seenDescriptors.single().descriptor)
        assertSame(module, seenDescriptors.single().pluginDescriptor)

        val connection = subscribers.single()
        assertSame(module, readConnectionField(connection, "module"))
        assertSame(topic, readConnectionField(connection, "topic"))
        assert(
            listOf(TestListenerHandler::class.java) == readConnectionHandlers(connection).map { it.javaClass }
        )
    }

    @Test
    fun `unsubscribe lazy listeners removes matching raw descriptors and subscriber handlers`() {
        val removedModule = testPluginDescriptor("removed.plugin")
        val survivingModule = testPluginDescriptor("surviving.plugin")
        val topic = Topic.create("sample-topic", TestTopicListener::class.java)
        val removedDescriptor = listenerDescriptor(
            module = removedModule,
            topic = topic,
            listenerClass = TestListenerHandler::class.java,
        )
        val survivingDescriptor = listenerDescriptor(
            module = survivingModule,
            topic = topic,
            listenerClass = OtherListenerHandler::class.java,
        )

        val storedDescriptors = mutableListOf(PluginListenerDescriptor(survivingDescriptor, survivingModule))
        @Suppress("UNCHECKED_CAST")
        (storedDescriptors as MutableList<Any>).add(0, removedDescriptor)
        val topicClassToListenerDescriptor = ConcurrentHashMap<String, MutableList<PluginListenerDescriptor>>()
        topicClassToListenerDescriptor[topic.listenerClass.name] = storedDescriptors

        val subscribers = ConcurrentLinkedQueue<MessageBusImpl.MessageHandlerHolder>()
        subscribers += descriptorBasedConnection(
            module = removedModule,
            topic = topic,
            handlers = listOf(TestListenerHandler(), OtherListenerHandler()),
        )

        val changed = LazyListenerKt.unsubscribeLazyListeners(
            removedModule,
            listOf(removedDescriptor),
            topicClassToListenerDescriptor,
            subscribers,
        )

        assertTrue(changed)
        assertEquals(1, topicClassToListenerDescriptor.getValue(topic.listenerClass.name).size)
        assertSame(
            survivingModule,
            topicClassToListenerDescriptor.getValue(topic.listenerClass.name).single().pluginDescriptor,
        )

        val connection = subscribers.single()
        assertSame(removedModule, readConnectionField(connection, "module"))
        assertSame(topic, readConnectionField(connection, "topic"))
        assert(
            listOf(OtherListenerHandler::class.java) == readConnectionHandlers(connection).map { it.javaClass }
        )
    }

    private fun listenerDescriptor(
        module: IdeaPluginDescriptor,
        topic: Topic<*>,
        listenerClass: Class<*>,
    ): ListenerDescriptor = ListenerDescriptor(
        ExtensionDescriptor.Os.mac,
        listenerClass.name,
        topic.listenerClass.name,
        true,
        true,
    ).also { descriptor ->
        descriptor.javaClass.getField("pluginDescriptor").set(descriptor, module)
    }

    private fun testPluginDescriptor(id: String): IdeaPluginDescriptor {
        val pluginId = PluginId.getId(id)
        var enabled = true
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(IdeaPluginDescriptor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getPluginId" -> pluginId
                "getPluginClassLoader", "getClassLoader" -> javaClass.classLoader
                "getPluginPath" -> Path.of("/tmp/$id")
                "getPath" -> Path.of("/tmp/$id").toFile()
                "getName" -> id
                "getDescription",
                "getChangeNotes",
                "getProductCode",
                "getVendor",
                "getOrganization",
                "getResourceBundleBaseName",
                "getCategory",
                "getDisplayCategory",
                "getVendorEmail",
                "getVendorUrl",
                "getUrl",
                "getSinceBuild",
                "getUntilBuild",
                    -> ""
                "getVersion" -> "1.0"
                "getReleaseDate" -> null
                "getReleaseVersion" -> 0
                "getDependencies" -> emptyList<Any>()
                "getDescriptorPath" -> "META-INF/plugin.xml"
                "isEnabled" -> enabled
                "setEnabled" -> {
                    enabled = args?.single() as Boolean
                    null
                }
                "isBundled",
                "isLicenseOptional",
                "allowBundledUpdate",
                "isImplementationDetail",
                "isRequireRestart",
                    -> false
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                "toString" -> "TestPluginDescriptor($id)"
                else -> defaultReturnValue(method.returnType)
            }
        } as IdeaPluginDescriptor
    }

    private fun defaultReturnValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE,
        java.lang.Boolean::class.java,
            -> false
        Integer.TYPE,
        Integer::class.java,
            -> 0
        java.lang.Long.TYPE,
        java.lang.Long::class.java,
            -> 0L
        java.lang.Float.TYPE,
        java.lang.Float::class.java,
            -> 0f
        java.lang.Double.TYPE,
        java.lang.Double::class.java,
            -> 0.0
        java.lang.Short.TYPE,
        java.lang.Short::class.java,
            -> 0.toShort()
        java.lang.Byte.TYPE,
        java.lang.Byte::class.java,
            -> 0.toByte()
        Character.TYPE,
        Character::class.java,
            -> '\u0000'
        else -> null
    }

    private fun descriptorBasedConnection(
        module: IdeaPluginDescriptor,
        topic: Topic<*>,
        handlers: List<Any>,
    ): MessageBusImpl.MessageHandlerHolder {
        val connectionClass = Class.forName("com.intellij.util.messages.impl.DescriptorBasedMessageBusConnection")
        val constructor = connectionClass.getDeclaredConstructor(
            Class.forName("com.intellij.openapi.extensions.PluginDescriptor"),
            Topic::class.java,
            List::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(module, topic, handlers) as MessageBusImpl.MessageHandlerHolder
    }

    private fun readConnectionField(
        connection: MessageBusImpl.MessageHandlerHolder,
        fieldName: String,
    ): Any? {
        val field = connection.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(connection)
    }

    @Suppress("UNCHECKED_CAST")
    private fun readConnectionHandlers(connection: MessageBusImpl.MessageHandlerHolder): List<Any> {
        return readConnectionField(connection, "handlers") as List<Any>
    }
}

private interface TestTopicListener

private class TestListenerHandler : TestTopicListener

private class OtherListenerHandler : TestTopicListener
