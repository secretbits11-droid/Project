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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or 
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        // Ensure the overlay respects touch pass-through by setting specific window features if needed
        params.gravity = Gravity.TOP or Gravity.START

        composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                MaterialTheme {
                    GamepadOverlay(
                        controller = gamepadController,
                        onClose = { stopSelf() }
                    )
                }
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
        return NotificationCompat.Builder(this, channelId)
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
            if (composeView.isAttachedToWindow) {
                windowManager.removeView(composeView)
            }
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

enum class OverlayMode {
    GAMEPAD, RACING
}

@Composable
fun GamepadOverlay(controller: GamepadController, onClose: () -> Unit) {
    var isMinimized by remember { mutableStateOf(false) }
    var currentMode by remember { mutableStateOf(OverlayMode.GAMEPAD) }

    if (isMinimized) {
        MinimizedBubble(
            onRestore = { isMinimized = false }
        )
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            if (currentMode == OverlayMode.GAMEPAD) {
                GamepadModeUI(controller)
            } else {
                RacingModeUI(controller)
            }

            // Control Bar
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { isMinimized = true }) { Text("Minimize") }
                Button(onClick = { 
                    currentMode = if (currentMode == OverlayMode.GAMEPAD) OverlayMode.RACING else OverlayMode.GAMEPAD 
                }) { 
                    Text(if (currentMode == OverlayMode.GAMEPAD) "Racing Mode" else "Gamepad Mode") 
                }
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
fun MinimizedBubble(onRestore: () -> Unit) {
    var offsetX by remember { mutableStateOf(100f) }
    var offsetY by remember { mutableStateOf(100f) }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        FloatingActionButton(
            onClick = onRestore,
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                }
        ) {
            Text("🎮")
        }
    }
}

@Composable
fun GamepadModeUI(controller: GamepadController) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Top Bumpers
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { controller.sendButtonPress("LT") }) { Text("LT") }
                Button(onClick = { controller.sendButtonPress("LB") }) { Text("LB") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { controller.sendButtonPress("RB") }) { Text("RB") }
                Button(onClick = { controller.sendButtonPress("RT") }) { Text("RT") }
            }
        }

        // Center Buttons
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom
        ) {
            Button(onClick = { controller.sendButtonPress("SELECT") }) { Text("Select") }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = { controller.sendButtonPress("START") }) { Text("Start") }
        }

        // Left Controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            DPad(controller)
            Joystick(onMove = { x, y -> 
                controller.sendAxisEvent("LEFT_X", x)
                controller.sendAxisEvent("LEFT_Y", y)
            })
        }

        // Right Controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Joystick(onMove = { x, y -> 
                controller.sendAxisEvent("RIGHT_X", x)
                controller.sendAxisEvent("RIGHT_Y", y)
            })
            ActionButtons(controller)
        }
    }
}

@Composable
fun RacingModeUI(controller: GamepadController) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Left Controls
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(32.dp)
        ) {
            SteeringWheel(controller)
        }

        // Right Controls
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(32.dp)
        ) {
            Pedals(controller)
        }
    }
}

@Composable
fun Joystick(
    modifier: Modifier = Modifier,
    onMove: (x: Float, y: Float) -> Unit
) {
    var thumbX by remember { mutableStateOf(0f) }
    var thumbY by remember { mutableStateOf(0f) }
    val maxRadius = 100f

    Box(
        modifier = modifier
            .size(120.dp)
            .background(Color.DarkGray.copy(alpha = 0.5f), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        thumbX = 0f
                        thumbY = 0f
                        onMove(0f, 0f)
                    },
                    onDragCancel = {
                        thumbX = 0f
                        thumbY = 0f
                        onMove(0f, 0f)
                    }
                ) { change, dragAmount ->
                    change.consume()
                    val newX = thumbX + dragAmount.x
                    val newY = thumbY + dragAmount.y
                    val distance = kotlin.math.hypot(newX, newY)
                    if (distance <= maxRadius) {
                        thumbX = newX
                        thumbY = newY
                    } else {
                        val ratio = maxRadius / distance
                        thumbX = newX * ratio
                        thumbY = newY * ratio
                    }
                    onMove(thumbX / maxRadius, thumbY / maxRadius)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbX.roundToInt(), thumbY.roundToInt()) }
                .size(40.dp)
                .background(Color.LightGray, CircleShape)
        )
    }
}

@Composable
fun ActionButtons(controller: GamepadController) {
    Box(modifier = Modifier.size(140.dp)) {
        Button(
            onClick = { controller.sendButtonPress("Y") }, 
            modifier = Modifier.align(Alignment.TopCenter).size(48.dp), 
            shape = CircleShape, 
            contentPadding = PaddingValues(0.dp)
        ) { Text("Y") }
        Button(
            onClick = { controller.sendButtonPress("A") }, 
            modifier = Modifier.align(Alignment.BottomCenter).size(48.dp), 
            shape = CircleShape, 
            contentPadding = PaddingValues(0.dp)
        ) { Text("A") }
        Button(
            onClick = { controller.sendButtonPress("X") }, 
            modifier = Modifier.align(Alignment.CenterStart).size(48.dp), 
            shape = CircleShape, 
            contentPadding = PaddingValues(0.dp)
        ) { Text("X") }
        Button(
            onClick = { controller.sendButtonPress("B") }, 
            modifier = Modifier.align(Alignment.CenterEnd).size(48.dp), 
            shape = CircleShape, 
            contentPadding = PaddingValues(0.dp)
        ) { Text("B") }
    }
}

@Composable
fun DPad(controller: GamepadController) {
    Box(modifier = Modifier.size(140.dp)) {
        Button(
            onClick = { controller.sendButtonPress("UP") }, 
            modifier = Modifier.align(Alignment.TopCenter).size(44.dp), 
            shape = RoundedCornerShape(8.dp), 
            contentPadding = PaddingValues(0.dp)
        ) { Text("U") }
        Button(
            onClick = { controller.sendButtonPress("DOWN") }, 
            modifier = Modifier.align(Alignment.BottomCenter).size(44.dp), 
            shape = RoundedCornerShape(8.dp), 
            contentPadding = PaddingValues(0.dp)
        ) { Text("D") }
        Button(
            onClick = { controller.sendButtonPress("LEFT") }, 
            modifier = Modifier.align(Alignment.CenterStart).size(44.dp), 
            shape = RoundedCornerShape(8.dp), 
            contentPadding = PaddingValues(0.dp)
        ) { Text("L") }
        Button(
            onClick = { controller.sendButtonPress("RIGHT") }, 
            modifier = Modifier.align(Alignment.CenterEnd).size(44.dp), 
            shape = RoundedCornerShape(8.dp), 
            contentPadding = PaddingValues(0.dp)
        ) { Text("R") }
    }
}

@Composable
fun SteeringWheel(controller: GamepadController) {
    var rotationAngle by remember { mutableStateOf(0f) }
    
    Box(
        modifier = Modifier
            .size(200.dp)
            .background(Color.DarkGray.copy(alpha = 0.5f), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        rotationAngle = 0f
                        controller.sendAxisEvent("STEERING", 0f)
                    },
                    onDragCancel = {
                        rotationAngle = 0f
                        controller.sendAxisEvent("STEERING", 0f)
                    }
                ) { change, dragAmount ->
                    change.consume()
                    rotationAngle += dragAmount.x * 0.5f
                    rotationAngle = rotationAngle.coerceIn(-90f, 90f)
                    controller.sendAxisEvent("STEERING", rotationAngle / 90f)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            rotate(rotationAngle) {
                drawCircle(color = Color.LightGray, style = Stroke(width = 16f))
                drawLine(
                    color = Color.LightGray, 
                    start = Offset(16f, size.height / 2), 
                    end = Offset(size.width - 16f, size.height / 2), 
                    strokeWidth = 16f
                )
                drawLine(
                    color = Color.LightGray, 
                    start = Offset(size.width / 2, size.height / 2), 
                    end = Offset(size.width / 2, size.height - 16f), 
                    strokeWidth = 16f
                )
            }
        }
    }
}

@Composable
fun Pedals(controller: GamepadController) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
        Button(
            onClick = { controller.sendButtonPress("BRAKE") },
            modifier = Modifier
                .width(80.dp)
                .height(120.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.7f))
        ) {
            Text("Brake")
        }
        Button(
            onClick = { controller.sendButtonPress("GAS") },
            modifier = Modifier
                .width(80.dp)
                .height(160.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Green.copy(alpha = 0.7f))
        ) {
            Text("Gas")
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
            "LB" -> 102 // KeyEvent.KEYCODE_BUTTON_L1
            "RB" -> 103 // KeyEvent.KEYCODE_BUTTON_R1
            "LT" -> 104 // KeyEvent.KEYCODE_BUTTON_L2
            "RT" -> 105 // KeyEvent.KEYCODE_BUTTON_R2
            "START" -> 108 // KeyEvent.KEYCODE_BUTTON_START
            "SELECT" -> 109 // KeyEvent.KEYCODE_BUTTON_SELECT
            "UP" -> 19 // KeyEvent.KEYCODE_DPAD_UP
            "DOWN" -> 20 // KeyEvent.KEYCODE_DPAD_DOWN
            "LEFT" -> 21 // KeyEvent.KEYCODE_DPAD_LEFT
            "RIGHT" -> 22 // KeyEvent.KEYCODE_DPAD_RIGHT
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
