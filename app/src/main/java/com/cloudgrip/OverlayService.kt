package com.cloudgrip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.*
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.roundToInt

class OverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val gamepadController = GamepadController()

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showOverlay()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, createNotification())
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun showOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                GamepadOverlay(
                    controller = gamepadController,
                    onClose = { stopSelf() }
                )
            }
        }
        windowManager.addView(composeView, params)
    }

    private fun createNotification(): Notification {
        val channelId = "CloudGripChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "CloudGrip Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        return androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle("CloudGrip Active")
            .setContentText("Floating gamepad is running.")
            .setSmallIcon(android.R.drawable.ic_menu_always_landscape_portrait)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        if (::composeView.isInitialized) {
            composeView.disposeComposition()
            windowManager.removeView(composeView)
        }
    }

    companion object {
        const val ACTION_STOP = "com.cloudgrip.action.STOP"
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
}

@Composable
fun GamepadOverlay(controller: GamepadController, onClose: () -> Unit) {
    var offsetX by remember { mutableStateOf(100f) }
    var offsetY by remember { mutableStateOf(200f) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .background(Color.Black.copy(alpha = 0.75f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .padding(12.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { controller.sendButtonPress("X") }) { Text("X") }
                Button(onClick = { controller.sendButtonPress("Y") }) { Text("Y") }
                Button(onClick = { controller.sendButtonPress("A") }) { Text("A") }
                Button(onClick = { controller.sendButtonPress("B") }) { Text("B") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Close")
            }
        }
    }
}

/**
 * Handles the translation of UI button presses into system-level gamepad events.
 * Note: True system-level injection (uinput) requires root access.
 * This class provides the scaffolding for root-based uinput injection.
 */
class GamepadController {
    private val TAG = "GamepadController"

    fun sendButtonPress(button: String) {
        Log.d(TAG, "Button pressed: $button")
        // TODO in production: Write to /dev/uinput via JNI or execute su shell command
        // Example of shell command approach (requires root):
        // executeShellCommand("input keyevent ${getAndroidKeyCode(button)}")
    }

    fun sendAxisEvent(axis: String, value: Float) {
        Log.d(TAG, "Axis moved: $axis to $value")
        // TODO in production: Inject axis event
    }

    private fun getAndroidKeyCode(button: String): Int {
        return when (button) {
            "A" -> 96 // KeyEvent.KEYCODE_BUTTON_A
            "B" -> 97 // KeyEvent.KEYCODE_BUTTON_B
            "X" -> 99 // KeyEvent.KEYCODE_BUTTON_X
            "Y" -> 100 // KeyEvent.KEYCODE_BUTTON_Y
            else -> 0
        }
    }

    private fun executeShellCommand(command: String) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute command: $command", e)
        }
    }
}