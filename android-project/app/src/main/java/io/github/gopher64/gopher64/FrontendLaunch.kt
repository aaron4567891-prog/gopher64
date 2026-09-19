package io.github.gopher64.gopher64

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

internal object FrontendLaunch {
    fun romUri(intent: Intent?): Uri? {
        if (intent == null) return null
        val path = FrontendInput.source(intent.dataString,
            listOf("ROM", "rom", "romPath", "rom_path", "file_path").map { intent.getStringExtra(it) }) ?: return null
        if (path.startsWith('/')) return Uri.fromFile(File(path))
        return Uri.parse(path).normalizeScheme()
    }

    fun prepare(context: Context, uri: Uri): File {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment.orEmpty()
        if (uri.scheme == "content") {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) name = it.getString(0)
            }
        }
        val extension = FrontendInput.extension(name)
        val directory = File(context.cacheDir, "frontend-roms").apply { mkdirs() }
        // Only one game can run at a time; remove copies left after a crash.
        directory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        val output = File.createTempFile("game-", ".$extension", directory)
        try {
            val input = resolver.openInputStream(uri) ?: error("ROM access denied")
            input.use { source ->
                output.outputStream().use { destination ->
                    FrontendInput.copy(source, destination)
                }
            }
            return output
        } catch (error: Exception) {
            output.delete()
            throw error
        }
    }
}
