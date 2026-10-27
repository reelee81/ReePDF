package hu.reelee81.reepdf.core

import java.io.File

object OutputExports {
    fun removeOld(dir: File, protectedNames: Set<String>, keepRecent: Int = 0) {
        val pdfs = dir
            .listFiles { f -> f.isFile && f.extension.equals("pdf", true) && f.name !in protectedNames }
            ?.toList()
            .orEmpty()

        val sorted = pdfs.sortedByDescending { it.lastModified() }

        if (sorted.size > keepRecent) {
            sorted.drop(keepRecent).forEach { it.delete() }
        }
    }
}
