package io.github.gopher64.gopher64

import android.app.NativeActivity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

class SlintActivity : NativeActivity() {
    companion object {
        init {
            System.loadLibrary("gopher64")
        }
    }
    private external fun nativeOnActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
    private external fun nativeQueueFrontendRom(path: String)
    private val frontendWorker = Executors.newSingleThreadExecutor()
    private var frontendGame = false
    private var preparingFrontendGame = false
    private var gameRunning = false
    private var frontendFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        frontendGame = savedInstanceState?.getBoolean("frontendGame") ?: false
        gameRunning = savedInstanceState?.getBoolean("gameRunning") ?: false
        frontendFile = savedInstanceState?.getString("frontendFile")?.let { File(it) }
        if (savedInstanceState == null) acceptFrontendIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptFrontendIntent(intent)
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("frontendGame", frontendGame)
        state.putBoolean("gameRunning", gameRunning)
        state.putString("frontendFile", frontendFile?.path)
        super.onSaveInstanceState(state)
    }

    private fun acceptFrontendIntent(incoming: Intent?) {
        val uri = FrontendLaunch.romUri(incoming) ?: return
        if (gameRunning || preparingFrontendGame) {
            Toast.makeText(this, "Exit the current game before launching another ROM.", Toast.LENGTH_LONG).show()
            return
        }
        preparingFrontendGame = true
        Toast.makeText(this, "Opening game…", Toast.LENGTH_SHORT).show()
        frontendWorker.execute {
            try {
                // A private seekable copy handles opaque provider URIs and
                // temporary grants without granting broad storage access.
                val file = FrontendLaunch.prepare(this, uri)
                runOnUiThread {
                    if (isDestroyed || isFinishing || gameRunning) {
                        file.delete()
                        preparingFrontendGame = false
                        return@runOnUiThread
                    }
                    frontendFile = file
                    frontendGame = true
                    preparingFrontendGame = false
                    nativeQueueFrontendRom(android.net.Uri.fromFile(file).toString())
                }
            } catch (error: Exception) {
                runOnUiThread {
                    preparingFrontendGame = false
                    if (!isDestroyed) Toast.makeText(this,
                        "Could not open ROM. Use a supported N64 file and let the frontend grant read access.",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        if (requestCode == 3) gameRunning = true
        super.startActivityForResult(intent, requestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        nativeOnActivityResult(requestCode, resultCode, data)
        if (requestCode == 3) {
            gameRunning = false
            if (frontendGame) {
                frontendGame = false
                frontendFile?.delete()
                frontendFile = null
                moveTaskToBack(true)
            }
        }
    }

    override fun onDestroy() {
        frontendWorker.shutdownNow()
        super.onDestroy()
    }
}
