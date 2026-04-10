package io.github.amichne.kast.server

import io.github.amichne.kast.api.DescriptorRegistry
import io.github.amichne.kast.api.ServerInstanceDescriptor
import java.nio.file.Path

class DescriptorStore(
    private val registry: DescriptorRegistry,
) {
    constructor(daemonsFile: Path) : this(DescriptorRegistry(daemonsFile))

    fun write(descriptor: ServerInstanceDescriptor) {
        registry.register(descriptor)
    }

    fun delete(descriptor: ServerInstanceDescriptor) {
        registry.delete(descriptor)
    }
}
