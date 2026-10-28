package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Serializable

@Serializable
enum class ImpactStringConversionOriginDocument {
    SOURCE,
    SOURCE_MEMBER_GENERATED,
    LIBRARY,
    JAVA_SOURCE,
    JAVA_LIBRARY,
    SAM_CONSTRUCTOR,
    TYPEALIASED_CONSTRUCTOR,
    INTERSECTION_OVERRIDE,
    SUBSTITUTION_OVERRIDE,
    DELEGATED,
    JAVA_SYNTHETIC_PROPERTY,
    PROPERTY_BACKING_FIELD,
    PLUGIN,
    JS_DYNAMIC,
    NATIVE_FORWARD_DECLARATION,
}
