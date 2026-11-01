package com.ocam.lut

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

private const val TAG = "LutStore"

/** What the picker and the settings sheet show for one file on disk. */
class LutFile(val name: String, val file: File) {
    /** What it is called without the extension, which is what the button shows. */
    val label: String get() = name.removeSuffix(".cube").removeSuffix(".CUBE")
}

/**
 * The film simulations this phone has, as files.
 *
 * They live in the app's own folder on external storage - visible over USB and to any file
 * manager, under Android/data/com.ocam/files/LUTs - so they can be dropped in by hand, and
 * reachable without asking for a storage permission. Anything picked through ADD is copied in
 * there, because a document the picker hands over may be gone by the next time the app starts.
 */
class LutStore(context: Context) {

    private val appContext = context.applicationContext

    val folder: File =
        File(appContext.getExternalFilesDir(null), "LUTs").apply { mkdirs() }

    /** Every cube in the folder, by name. */
    fun list(): List<LutFile> = folder.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".cube", ignoreCase = true) }
        ?.sortedBy { it.name.lowercase() }
        ?.map { LutFile(it.name, it) }
        .orEmpty()

    fun find(name: String): LutFile? = list().firstOrNull { it.name == name }

    /**
     * Copy a picked document in. Returns the file's name, which is what the choice is stored as.
     *
     * @throws IllegalArgumentException when the file is not a cube this can use - it is parsed
     *   here, before it is kept, so a file that cannot be used never becomes a choice.
     */
    fun import(uri: Uri): String {
        val name = displayName(uri)
        require(name.endsWith(".cube", ignoreCase = true)) { "$name is not a .cube file" }

        val resolver = appContext.contentResolver
        // Parse first and keep second: the same stream cannot be read twice, so it goes to a
        // temporary file, which is only given its real name once it has proved to be a cube.
        val staged = File(folder, ".importing")
        resolver.openInputStream(uri)?.use { input ->
            staged.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("Cannot read $name")

        try {
            staged.inputStream().use { CubeLut.parse(name, it) }
        } catch (t: Throwable) {
            staged.delete()
            throw t
        }

        val destination = File(folder, name)
        staged.renameTo(destination)
        return name
    }

    fun delete(name: String) {
        runCatching { File(folder, name).delete() }
    }

    /** Read a cube off disk, or null when it has gone or turns out not to be readable. */
    fun load(name: String): CubeLut? = runCatching {
        File(folder, name).inputStream().use { CubeLut.parse(name, it) }
    }.onFailure { Log.w(TAG, "Cannot load $name", it) }.getOrNull()

    private fun displayName(uri: Uri): String {
        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) {
                cursor.getString(column)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "picked.cube"
    }
}
