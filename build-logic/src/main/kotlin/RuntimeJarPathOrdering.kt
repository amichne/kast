import java.io.File

internal object RuntimeJarPathOrdering {
    fun inOrder(files: Iterable<File>): List<String> =
        files
            .filter { file -> file.name.endsWith(".jar") }
            .map(File::getAbsolutePath)
}
