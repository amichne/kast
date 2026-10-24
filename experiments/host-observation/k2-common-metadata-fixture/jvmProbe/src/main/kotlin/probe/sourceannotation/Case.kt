package probe.sourceannotation
annotation class SourceHint(val message: String)
fun sourceTarget(): String = "ok"
fun sourceCaller(): String = sourceTarget()
@SourceHint("sourceTarget")
fun unrelatedSourceAnnotation(): String = "unused"
