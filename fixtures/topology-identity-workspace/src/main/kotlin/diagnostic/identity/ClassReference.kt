package diagnostic.identity

import kotlin.reflect.KClass

class ClassReferenceTarget

fun classReferenceProbe(): KClass<ClassReferenceTarget> = ClassReferenceTarget::class
