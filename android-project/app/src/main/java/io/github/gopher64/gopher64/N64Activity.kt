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
        requestCleanExit()
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
        // explicit callback so a back swipe requests native shutdown before the
        // SurfaceView is destroyed. PRIORITY_OVERLAY keeps SDLActivity's own
        // callback from finishing the Activity first.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = OnBackInvokedCallback { requestCleanExit() }
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
            text = "Controls"
            alpha = 0.65f
            isFocusable = false
            setOnClickListener {
                releaseTouch()
                android.app.AlertDialog.Builder(this@N64Activity)
                    .setTitle("Touch controls")
                    .setMultiChoiceItems(arrayOf("Show touch controls"),
                        booleanArrayOf(prefs.getBoolean("enabled", true))) { _, _, enabled ->
                        releaseTouch()
                        prefs.edit().putBoolean("enabled", enabled).apply()
                        touchControls?.visibility = if (enabled)
                            android.view.View.VISIBLE else android.view.View.GONE
                    }.setPositiveButton("Done", null).show()
            }
        }
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
