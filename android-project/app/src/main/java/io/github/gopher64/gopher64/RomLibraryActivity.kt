package io.github.gopher64.gopher64

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract as Docs
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** The tree grant stays on Android; document URIs go to the existing ROM loader. */
class RomLibraryActivity : Activity() {
    private data class Game(val name: String, val uri: Uri)
    private val jobs = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    private val games = mutableListOf<Game>()
    private lateinit var status: TextView
    private lateinit var adapter: Covers
    private val prefs by lazy { getSharedPreferences("rom_library", MODE_PRIVATE) }
    private val artDir by lazy { File(cacheDir, "boxart").apply { mkdirs() } }
    private val bitmaps = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left + dp(12), bars.top + dp(12), bars.right + dp(12), bars.bottom + dp(12))
                insets
            }
        }
        val toolbar = LinearLayout(this)
        toolbar.addView(Button(this).apply { text = "ROM Folder"; setOnClickListener { chooseFolder() } })
        toolbar.addView(Button(this).apply { text = "Refresh"; setOnClickListener { reload() } })
        toolbar.addView(Button(this).apply { text = "Back"; setOnClickListener { finish() } })
        layout.addView(toolbar)
        status = TextView(this).apply { text = "Choose a folder containing N64 ROMs." }
        layout.addView(status)
        layout.addView(TextView(this).apply { text = "Box art: Libretro thumbnails • downloaded and cached automatically"; textSize = 12f })
        adapter = Covers()
        layout.addView(GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(170)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(8)
            verticalSpacing = dp(8)
            adapter = this@RomLibraryActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                setResult(RESULT_OK, Intent().setData(games[position].uri))
                finish()
            }
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        if (prefs.contains("tree")) reload() else if (savedInstanceState == null) chooseFolder()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun chooseFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            prefs.getString("tree", null)?.let { putExtra(Docs.EXTRA_INITIAL_URI, Uri.parse(it)) }
        }, 10)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 10 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val old = prefs.getString("tree", null)
            prefs.edit().putString("tree", uri.toString()).apply()
            if (old != null && old != uri.toString()) {
                runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            reload()
        } catch (_: SecurityException) {
            status.text = "Folder access was not granted. Please choose the folder again."
        }
    }

    private fun reload() {
        val tree = prefs.getString("tree", null) ?: return chooseFolder()
        val ticket = generation.incrementAndGet()
        games.clear()
        adapter.notifyDataSetChanged()
        status.text = "Scanning ROM folder…"
        jobs.execute {
            try {
                val found = scan(Uri.parse(tree), ticket)
                if (generation.get() != ticket) return@execute
                runOnUiThread {
                    if (generation.get() == ticket) {
                        games.addAll(found)
                        adapter.notifyDataSetChanged()
                        status.text = if (found.isEmpty()) "No N64 ROMs found. Choose another folder." else "${found.size} games • Loading covers…"
                    }
                }
                if (found.isEmpty()) return@execute
                val index = runCatching { coverIndex() }.getOrDefault(emptyList())
                var covered = 0
                var failures = 0
                for (game in found) {
                    if (generation.get() != ticket) return@execute
                    val file = coverFile(game)
                    if (!file.exists()) {
                        val match = CoverNames.match(game.name, index)
                        if (match != null && failures < 3) runCatching {
                            val bytes = download("https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Nintendo_64/master/Named_Boxarts/" + Uri.encode(match), 8 * 1024 * 1024)
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192)
                            val temporary = File(file.path + ".tmp")
                            temporary.writeBytes(bytes)
                            check(temporary.renameTo(file))
                        }.onSuccess { failures = 0 }.onFailure { failures++ }
                    }
                    if (file.exists()) covered++
                    runOnUiThread { if (generation.get() == ticket) adapter.notifyDataSetChanged() }
                }
                runOnUiThread {
                    if (generation.get() == ticket) status.text = "${found.size} games • $covered covers. Missing covers? Check ROM filenames or refresh online."
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (generation.get() == ticket) status.text = "Could not read ROM folder. Use ROM Folder to grant access again."
                }
            }
        }
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

    private inner class Covers : BaseAdapter() {
        override fun getCount() = games.size
        override fun getItem(position: Int) = games[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, reusable: View?, parent: ViewGroup): View {
            val cell = (reusable as? LinearLayout) ?: LinearLayout(this@RomLibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(-1, dp(125)))
                addView(TextView(context).apply { maxLines = 2; gravity = android.view.Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(50)))
            }
            val game = games[position]
            val file = coverFile(game)
            val image = cell.getChildAt(0) as ImageView
            var bitmap = bitmaps.get(file.name)
            if (bitmap == null && file.exists()) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, options)
                options.inSampleSize = 1
                while (options.outWidth / options.inSampleSize > 512 || options.outHeight / options.inSampleSize > 512) options.inSampleSize *= 2
                options.inJustDecodeBounds = false
                bitmap = BitmapFactory.decodeFile(file.path, options)
                if (bitmap != null) bitmaps.put(file.name, bitmap) else file.delete()
            }
            if (bitmap != null) image.setImageBitmap(bitmap) else image.setImageResource(android.R.drawable.ic_menu_gallery)
            image.contentDescription = "Cover for ${game.name}"
            (cell.getChildAt(1) as TextView).text = game.name.substringBeforeLast('.')
            return cell
        }
    }

    override fun onDestroy() {
        generation.incrementAndGet()
        jobs.shutdownNow()
        super.onDestroy()
    }
}
