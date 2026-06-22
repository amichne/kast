package io.github.amichne.kast.server

import io.github.amichne.kast.api.client.DescriptorRegistry
import io.github.amichne.kast.api.client.ServerInstanceDescriptor

class DescriptorStore(daemonsPath: String) {
    private val registry = DescriptorRegistry(daemonsPath)

    fun write(descriptor: ServerInstanceDescriptor) {
        registry.register(descriptor)
    }

    fun delete(descriptor: ServerInstanceDescriptor) {
        registry.delete(descriptor)
    }
}
