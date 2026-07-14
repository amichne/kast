package io.github.amichne.kast.indexstore.api.reference

@JvmInline
value class SourceIndexGeneration(val value: Long) {
    init {
        require(value >= 0) { "Source index generation must be non-negative" }
    }
}
