package com.lj.blackfiles

import java.io.File

/**
 * Finds likely duplicates: files with the same size whose names match once copy markers are
 * stripped, e.g. "photo.jpg", "photo (1).jpg", "photo - Copy.jpg", "photo_2.jpg", "Kopia photo.jpg".
 */
object Duplicates {
    /** [files] is ordered so the first is the one to keep: shortest name, then oldest. */
    class Group(val size: Long, val files: List<File>)

    private val copyPrefix = Regex("""^(copy of|kopia)\s+""")
    private val copySuffixes = listOf(
        Regex("""\s*[(\[]\s*\d+\s*[)\]]$"""),                   // "name (1)", "name[2]"
        Regex("""(^|[\s_-]+)(copy|kopia)(\s*\(?\d+\)?)?$"""),   // "name - Copy", "name_copy2", "name - kopia (2)"
        Regex("""[\s_-]+\d{1,2}$"""),                          // "name_1", "name-2"
    )
    private val separators = Regex("""[\s_.-]+""")

    fun find(root: File, includeHidden: Boolean): List<Group> {
        fun visible(f: File) = includeHidden || f == root || !f.name.startsWith(".")
        return root.walkTopDown()
            .onEnter(::visible)
            .filter { it.isFile && visible(it) }
            .map { it to it.length() }
            .filter { it.second > 0 }
            .groupBy({ it.second to key(it.first.name) }, { it.first })
            .filter { it.value.size > 1 }
            .map { (k, files) ->
                Group(k.first, files.sortedWith(compareBy<File>({ it.name.length }, { it.lastModified() })))
            }
            .sortedByDescending { it.size * (it.files.size - 1) }
    }

    fun key(name: String): String {
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0) name.substring(dot).lowercase() else ""
        val original = (if (dot > 0) name.substring(0, dot) else name).lowercase()
        var stem = original.replace(copyPrefix, "")
        while (true) {
            val before = stem
            for (r in copySuffixes) stem = stem.replace(r, "")
            if (stem == before) break
        }
        // A name that is only a number ("1.jpg") says nothing, so compare it as-is.
        if (stem.isBlank()) stem = original
        return stem.replace(separators, " ").trim() + ext
    }
}
