package com.ankiwear.wear

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ankiwear.wear.review.SideButtonTracker
import com.ankiwear.wear.review.SideButtons

class MainActivity : ComponentActivity() {

    private lateinit var dataLayerClient: DataLayerClient
    private val sideButton = SideButtonTracker()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the screen on while the app is in the foreground so the user doesn't
        // lose their place mid-review. The flag is automatically ignored once the
        // activity goes to background (home button, app switch, etc.).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dataLayerClient = DataLayerClient(this)
        val prefs = ReviewPrefs(this)
        // Demo mode shows built-in sample decks without a phone: handy for trying the UI
        // and for the CI screenshot run (adb shell am start ... --ez demo true).
        val demo = intent?.getBooleanExtra(EXTRA_DEMO, false) == true

        setContent {
            WearApp(dataLayerClient, prefs = prefs, demo = demo)
        }
    }

    override fun onResume() {
        super.onResume()
        dataLayerClient.register()
    }

    override fun onPause() {
        super.onPause()
        dataLayerClient.unregister()
    }

    /**
     * While a card is on screen the side button (Back on a Galaxy Watch; stem buttons on
     * watches that have them) answers instead of navigating: press to show the answer,
     * press again for Good, hold for Again. The top button is reserved by Wear OS and never
     * reaches apps.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handler = SideButtons.handler
        if (handler != null && event.keyCode in SIDE_BUTTON_KEYS) {
            val press = when (event.action) {
                KeyEvent.ACTION_DOWN -> sideButton.onDown(
                    repeatCount = event.repeatCount,
                    isLongPress = event.isLongPress,
                    downTime = event.downTime,
                    eventTime = event.eventTime
                )
                KeyEvent.ACTION_UP -> sideButton.onUp(
                    canceled = event.isCanceled,
                    downTime = event.downTime,
                    eventTime = event.eventTime
                )
                else -> null
            }
            press?.let(handler)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_DEMO = "demo"

        private val SIDE_BUTTON_KEYS = setOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_STEM_1,
            KeyEvent.KEYCODE_STEM_2,
            KeyEvent.KEYCODE_STEM_3
        )
    }
}
