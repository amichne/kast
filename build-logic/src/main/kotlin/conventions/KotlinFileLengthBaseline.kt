package conventions

/** Admitted, repository-relative allowances. The parser owns the checked-in TSV contract. */
class KotlinFileLengthBaseline private constructor(private val limits: Map<SourcePath, LineLimit>) {
    sealed interface Admission {
        data class Accepted(val baseline: KotlinFileLengthBaseline) : Admission

        data class Rejected(val reason: Failure, val line: Int) : Admission
    }

    enum class Failure {
        INVALID_ROW,
        INVALID_PATH,
        INVALID_LIMIT,
        DUPLICATE_PATH,
    }

    private data class SourcePath(val relative: String)

    private data class LineLimit(val count: Int)

    fun limitFor(relativePath: String, defaultLimit: Int): Int =
        maxOf(defaultLimit, limits[SourcePath(relativePath)]?.count ?: defaultLimit)

    companion object {
        val EMPTY = KotlinFileLengthBaseline(emptyMap())

        fun parse(lines: List<String>): Admission {
            val limits = linkedMapOf<SourcePath, LineLimit>()
            for ((index, line) in lines.withIndex()) {
                if (line.isBlank() || line.startsWith("#")) continue
                val columns = line.split('\t')
                if (columns.size != 2) return Admission.Rejected(Failure.INVALID_ROW, index + 1)
                val (path, rawLimit) = columns
                if (
                    !path.endsWith(".kt") ||
                        path.any { it.isISOControl() || it == '\\' || it == ':' } ||
                        path.split('/').any { it.isEmpty() || it == "." || it == ".." }
                ) {
                    return Admission.Rejected(Failure.INVALID_PATH, index + 1)
                }
                val limit = rawLimit.toIntOrNull()
                if (limit == null || limit <= 0 || rawLimit != limit.toString()) {
                    return Admission.Rejected(Failure.INVALID_LIMIT, index + 1)
                }
                val sourcePath = SourcePath(path)
                if (sourcePath in limits) return Admission.Rejected(Failure.DUPLICATE_PATH, index + 1)
                limits[sourcePath] = LineLimit(limit)
            }
            return Admission.Accepted(KotlinFileLengthBaseline(limits.toMap()))
        }
    }
}
