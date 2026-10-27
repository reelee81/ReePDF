package hu.reelee81.reepdf.pdf

import java.io.File
import org.junit.Assume.assumeTrue

internal object TestPdfs {
    private val input: File? = projectFolder("work/00_test_PDFs")
    private val output: File = projectFolder("work/00_test_PDFs_work") ?: File("../work/00_test_PDFs_work")

    fun file(name: String): File {
        val found = input?.walkTopDown()?.firstOrNull { it.isFile && it.name == name }
        assumeTrue("$name is not present in work/00_test_PDFs", found != null)
        return requireNotNull(found)
    }

    fun folder(name: String): File {
        val found = input?.walkTopDown()?.firstOrNull { it.isDirectory && it.name == name }
        assumeTrue("The $name folder is not present in work/00_test_PDFs", found != null)
        return requireNotNull(found)
    }

    fun outputFolder(name: String): File = File(output, name).apply { mkdirs() }

    private fun projectFolder(name: String): File? =
        listOf(File("../$name"), File(name)).firstOrNull { it.isDirectory }
}
