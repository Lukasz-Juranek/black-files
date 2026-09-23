package com.lj.blackfiles

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class Entry(val file: File, val isDir: Boolean, val size: Long, val modified: Long, val details: String) {
    val name: String get() = file.name
    val hidden get() = name.startsWith(".")
}

class Root(val label: String, val dir: File, val details: String)

/** [newestFirst] is the direction a sort starts in when picked: A→Z for names, newest/biggest first otherwise. */
enum class Sort(val label: String, val newestFirst: Boolean) { NAME("Name", false), DATE("Date", true), SIZE("Size", true) }

fun toast(c: Context, message: String) = Toast.makeText(c, message, Toast.LENGTH_SHORT).show()

/** Plain java.io.File access. Works because the app holds "All files access" (or legacy storage on Android 8-10). */
object Storage {
    fun hasAccess(c: Context) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /** Internal storage first, then any SD cards / USB drives. */
    fun roots(c: Context): List<Root> =
        c.getExternalFilesDirs(null).filterNotNull()
            .map { File(it.path.substringBefore("/Android/data")) }
            .distinct()
            .mapIndexed { i, dir ->
                Root(
                    if (i == 0) "Internal storage" else "SD card · ${dir.name}",
                    dir,
                    "${formatSize(dir.freeSpace)} free of ${formatSize(dir.totalSpace)}"
                )
            }

    /** Folders first, then files, in the chosen order. */
    fun list(dir: File, showHidden: Boolean, sort: Sort, descending: Boolean): List<Entry> {
        val dates = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val entries = (dir.listFiles() ?: emptyArray())
            .filter { showHidden || !it.name.startsWith(".") }
            .map { f ->
                val modified = f.lastModified()
                val date = dates.format(Date(modified))
                if (f.isDirectory) {
                    val n = f.list()?.size ?: 0
                    Entry(f, true, 0, modified, "$n item${if (n == 1) "" else "s"} · $date")
                } else {
                    val size = f.length()
                    Entry(f, false, size, modified, "${formatSize(size)} · $date")
                }
            }
        val byName = compareBy<Entry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val order = when (sort) {
            Sort.NAME -> byName
            Sort.DATE -> compareBy<Entry> { it.modified }.then(byName)
            Sort.SIZE -> compareBy<Entry> { it.size }.then(byName)
        }
        return entries.sortedWith(compareBy<Entry> { !it.isDir }.then(if (descending) order.reversed() else order))
    }

    fun mkdir(parent: File, name: String) {
        val dir = File(parent, name)
        require(!dir.exists()) { "$name already exists" }
        check(dir.mkdir()) { "can't create folder" }
    }

    fun rename(file: File, name: String) {
        val target = File(file.parentFile, name)
        require(!target.exists() || name.equals(file.name, ignoreCase = true)) { "$name already exists" }
        check(file.renameTo(target)) { "can't rename" }
    }

    fun delete(file: File) = check(file.deleteRecursively()) { "some files couldn't be deleted" }

    /** Copies or moves [src] into [destDir]. A name clash gets a " (1)" suffix instead of overwriting. */
    fun paste(src: File, destDir: File, move: Boolean) {
        val from = src.canonicalFile
        val to = destDir.canonicalFile
        require(to != from && !to.path.startsWith(from.path + File.separator)) { "can't paste a folder into itself" }
        if (move && from.parentFile == to) return
        val target = freeName(to, from.name, from.isDirectory)
        if (move && from.renameTo(target)) return
        check(from.copyRecursively(target)) { "copy failed" }
        if (move) check(from.deleteRecursively()) { "copied, but couldn't remove the original" }
    }

    fun freeName(dir: File, name: String, isDir: Boolean): File {
        val dot = if (isDir) -1 else name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var f = File(dir, name)
        var i = 1
        while (f.exists()) f = File(dir, "$base (${i++})$ext")
        return f
    }

    fun uri(c: Context, file: File): Uri = FileProvider.getUriForFile(c, "${c.packageName}.files", file)

    private fun mime(file: File) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT)) ?: "*/*"

    fun open(c: Context, file: File, chooser: Boolean) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(c, file), mime(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launch(c, if (chooser) Intent.createChooser(intent, "Open with") else intent)
    }

    fun share(c: Context, files: List<File>) {
        val uris = files.map { uri(c, it) }
        val types = files.map(::mime).distinct()
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.setType(types.singleOrNull() ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The chooser only passes read access on for URIs in clipData.
        intent.clipData = ClipData.newRawUri(files[0].name, uris[0]).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        launch(c, Intent.createChooser(intent, null))
    }

    private fun launch(c: Context, intent: Intent) {
        try {
            c.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(c, "No app can open this file")
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < 3) {
            value /= 1024
            unit++
        }
        return String.format(Locale.ROOT, "%.1f %sB", value, "KMGT"[unit])
    }
}
