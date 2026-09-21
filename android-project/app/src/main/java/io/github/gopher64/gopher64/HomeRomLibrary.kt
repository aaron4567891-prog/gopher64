package io.github.gopher64.gopher64

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract as Docs
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Scans and downloads off the UI thread; the existing Slint home owns the view. */
class HomeRomLibrary(private val activity: Activity, private val publish: (String) -> Unit) {
    private data class Game(val name: String, val uri: Uri)
    private val jobs = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    private val prefs = activity.getSharedPreferences("rom_library", Activity.MODE_PRIVATE)
    private val artDir = File(activity.cacheDir, "boxart").apply { mkdirs() }
    private val contentResolver get() = activity.contentResolver

    fun accept(data: Intent?) {
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val previous = prefs.getString("tree", null)
            prefs.edit().putString("tree", uri.toString()).apply()
            if (previous != null && previous != uri.toString()) runCatching {
                contentResolver.releasePersistableUriPermission(Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            reload()
        } catch (_: SecurityException) {
            send(emptyList(), "Folder access was not granted.", generation.get())
        }
    }

    private fun send(games: List<Game>, status: String, ticket: Int) {
        val rows = JSONArray()
        for (game in games) rows.put(JSONObject()
            .put("name", game.name.substringBeforeLast('.'))
            .put("uri", game.uri.toString())
            .put("cover", coverFile(game).takeIf { it.exists() }?.path ?: ""))
        val json = JSONObject().put("status", status).put("games", rows).toString()
        activity.runOnUiThread {
            if (!activity.isDestroyed && generation.get() == ticket) publish(json)
        }
    }

    fun reload() {
        val ticket = generation.incrementAndGet()
        val tree = prefs.getString("tree", null)
        if (tree == null) {
            send(emptyList(), "Choose a ROM folder to add your games.", ticket)
            return
        }
        send(emptyList(), "Scanning ROM folder…", ticket)
        jobs.execute {
            try {
                val found = scan(Uri.parse(tree), ticket)
                send(found, "${found.size} games • Loading covers…", ticket)
                if (found.isEmpty()) {
                    send(found, "No N64 ROMs found in this folder.", ticket)
                    return@execute
                }
                val index = runCatching { coverIndex() }.getOrDefault(emptyList())
                var failures = 0
                for (game in found) {
                    if (generation.get() != ticket) return@execute
                    val file = coverFile(game)
                    if (!file.exists() && failures < 3) {
                        val match = CoverNames.match(game.name, index)
                        if (match != null) runCatching {
                            val bytes = download("https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Nintendo_64/master/Named_Boxarts/" + Uri.encode(match), 8 * 1024 * 1024)
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192)
                            val temporary = File(file.path + ".tmp")
                            temporary.writeBytes(bytes)
                            check(temporary.renameTo(file))
                        }.onSuccess { failures = 0 }.onFailure { failures++ }
                    }
                }
                send(found, "${found.size} games • Box art: Libretro thumbnails", ticket)
            } catch (_: Exception) {
                send(emptyList(), "Could not read ROM folder. Select it again to grant access.", ticket)
            }
        }
    }

    fun close() {
        generation.incrementAndGet()
        jobs.shutdownNow()
    }

    private fun scan(tree: Uri, ticket: Int): List<Game> {
        val pending = java.util.ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        val result = mutableListOf<Game>()
        pending.add(Docs.getTreeDocumentId(tree))
        while (pending.isNotEmpty()) {
            if (generation.get() != ticket) return emptyList()
            val id = pending.removeFirst()
            if (!visited.add(id)) continue
            val children = Docs.buildChildDocumentsUriUsingTree(tree, id)
            val columns = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME, Docs.Document.COLUMN_MIME_TYPE)
            val cursor = contentResolver.query(children, columns, null, null, null)
                ?: error("Folder provider unavailable")
            cursor.use {
                while (it.moveToNext()) {
                    val child = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    if (it.getString(2) == Docs.Document.MIME_TYPE_DIR) pending.add(child)
                    else if (CoverNames.isRom(name)) result.add(Game(name, Docs.buildDocumentUriUsingTree(tree, child)))
                }
            }
        }
        return result.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun coverFile(game: Game): File {
        val key = MessageDigest.getInstance("SHA-256").digest(game.name.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(artDir, "$key.png")
    }

    private fun coverIndex(): List<String> {
        val file = File(artDir, "index.json")
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > 7 * 24 * 60 * 60 * 1000L) {
            runCatching {
                val bytes = download("https://api.github.com/repos/libretro-thumbnails/Nintendo_-_Nintendo_64/git/trees/master?recursive=1", 4 * 1024 * 1024)
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                require(!json.optBoolean("truncated"))
                require(json.has("tree"))
                file.writeBytes(bytes)
            }
        }
        if (!file.exists()) return emptyList()
        val tree = JSONObject(file.readText()).getJSONArray("tree")
        return (0 until tree.length()).map { tree.getJSONObject(it).getString("path") }
            .filter { it.startsWith("Named_Boxarts/") && it.endsWith(".png") }
            .map { it.removePrefix("Named_Boxarts/") }
    }

    private fun download(address: String, limit: Int): ByteArray {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", "Gopher64-ROM-Library")
            require(connection.responseCode == 200)
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= limit)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }

}
