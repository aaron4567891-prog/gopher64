package io.github.gopher64.gopher64

import android.content.Intent
import android.os.PowerManager
import android.content.Context
import android.os.Bundle
import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.util.Log
import org.libsdl.app.SDLActivity

class N64Activity : SDLActivity() {
    private var menuButton: android.view.View? = null
    private var menuDialog: android.app.AlertDialog? = null
    private external fun nativeMenuAction(action: Int)

    private fun showGameMenu() {
        if (exitRequested || isFinishing || menuDialog?.isShowing == true) return
        releaseTouch()
        val prefs = getSharedPreferences("touch_controls", MODE_PRIVATE)
        menuDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Game menu")
            .setItems(arrayOf("Resume", "Pause", "Save State", "Load State", "Touch Controls",
                if (prefs.getBoolean("show_menu", true)) "Hide Menu button" else "Show Menu button",
                "Exit Game")) { _, which ->
                when (which) {
                    0 -> nativeMenuAction(7)
                    1 -> nativeMenuAction(6)
                    2 -> nativeMenuAction(1)
                    3 -> android.app.AlertDialog.Builder(this)
                        .setTitle("Load State?")
                        .setMessage("Replace current progress with the saved state?")
                        .setPositiveButton("Load") { _, _ -> nativeMenuAction(2) }
                        .setNegativeButton("Cancel", null).show()
                    4 -> showTouchSettings()
                    5 -> {
                        val show = !prefs.getBoolean("show_menu", true)
                        prefs.edit().putBoolean("show_menu", show).apply()
                        menuButton?.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
                        if (!show) android.widget.Toast.makeText(this,
                            "Use Android Back to reopen the game menu.", android.widget.Toast.LENGTH_LONG).show()
                    }
                    6 -> android.app.AlertDialog.Builder(this)
                        .setTitle("Exit game?")
                        .setPositiveButton("Exit") { _, _ -> requestCleanExit() }
                        .setNegativeButton("Cancel", null).show()
                }
            }.setNegativeButton("Close", null).create()
        menuDialog?.show()
    }

    private fun showTouchSettings() {
        val prefs = getSharedPreferences("touch_controls", MODE_PRIVATE)
        releaseTouch()
        android.app.AlertDialog.Builder(this)
            .setTitle("Touch controls")
            .setMultiChoiceItems(arrayOf("Show touch controls"),
                booleanArrayOf(prefs.getBoolean("enabled", true))) { _, _, enabled ->
                releaseTouch()
                prefs.edit().putBoolean("enabled", enabled).apply()
                touchControls?.visibility = if (enabled)
                    android.view.View.VISIBLE else android.view.View.GONE
            }.setPositiveButton("Done", null).show()
    }

    private var touchControls: N64TouchControls? = null
    private external fun nativeTouch(buttons: Int, x: Int, y: Int)
    private external fun nativeRequestExit()

    private var exitRequested = false
    private var backCallback: OnBackInvokedCallback? = null

    private fun requestCleanExit() {
        if (exitRequested) return
        exitRequested = true
        releaseTouch()
        // Do not finish the Activity here. Let the native SDL thread process the
        // quit event, tear down parallel-rdp/Vulkan and audio, and return from
        // gopher64_sdl_main. SDLActivity will then finish normally.
        nativeRequestExit()
    }

    private fun releaseTouch() { touchControls?.release() }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        showGameMenu()
    }

    override fun onPause() {
        releaseTouch()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) releaseTouch()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 13+ predictive-back can bypass onBackPressed(). Register an
        // explicit callback so Back opens the menu without destroying the
        // SurfaceView. PRIORITY_OVERLAY keeps SDLActivity's own
        // callback from finishing the Activity first.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = OnBackInvokedCallback { showGameMenu() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                backCallback!!
            )
        }

        val prefs = getSharedPreferences("touch_controls", MODE_PRIVATE)
        val overlay = android.widget.FrameLayout(this)
        touchControls = N64TouchControls(this) { buttons, x, y ->
            nativeTouch(buttons, x, y)
        }
        overlay.addView(touchControls, android.widget.FrameLayout.LayoutParams(-1, -1))
        touchControls?.visibility = if (prefs.getBoolean("enabled", true))
            android.view.View.VISIBLE else android.view.View.GONE
        val toggle = android.widget.Button(this).apply {
            text = "Menu"
            alpha = 0.65f
            isFocusable = false
            visibility = if (prefs.getBoolean("show_menu", true))
                android.view.View.VISIBLE else android.view.View.GONE
            setOnClickListener { showGameMenu() }
        }
        menuButton = toggle
        overlay.addView(toggle, android.widget.FrameLayout.LayoutParams(-2, -2,
            android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL))
        addContentView(overlay, android.view.ViewGroup.LayoutParams(-1, -1))

        val powerManager = getContext().getSystemService(Context.POWER_SERVICE) as PowerManager

        if (powerManager.isSustainedPerformanceModeSupported) {
            Log.v("SDL", "Enabling sustained performance mode")
            window.setSustainedPerformanceMode(true)
        } else {
            Log.v("SDL", "Sustained performance mode not supported")
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
            backCallback = null
        }
        menuDialog?.dismiss()
        releaseTouch()
        super.onDestroy()
    }

    override fun getLibraries(): Array<String> = arrayOf(
        "SDL3",
        "SDL3_ttf",
        "gopher64"
    )

    override fun getMainFunction(): String = "gopher64_sdl_main"

    override fun getArguments(): Array<String> {
        val intent = intent ?: return super.getArguments()
        val args = intent.getStringArrayExtra("args") ?: return super.getArguments()

        val dataIntent = Intent()
        val file_path = intent.getStringExtra("file_path")
        if (file_path != null) {
            dataIntent.putExtra("file_path", file_path)
        }
        val cheats_path = intent.getStringExtra("cheats_path")
        if (cheats_path != null) {
            dataIntent.putExtra("cheats_path", cheats_path)
        }
        setResult(RESULT_OK, dataIntent)
        return args
    }
}
