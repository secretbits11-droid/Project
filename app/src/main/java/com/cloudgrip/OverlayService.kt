package com.cloudgrip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
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
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.*
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class OverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val gamepadController = GamepadController()

    private val boundsMap = mutableMapOf<String, android.graphics.Rect>()
    private val touchableRegion = android.graphics.Region()

    fun updateTouchableRegion(key: String, rect: android.graphics.Rect?) {
        if (rect == null) {
            boundsMap.remove(key)
        } else {
            boundsMap[key] = rect
        }
        val newRegion = android.graphics.Region()
        boundsMap.values.forEach { newRegion.op(it, android.graphics.Region.Op.UNION) }
        touchableRegion.set(newRegion)
        if (::composeView.isInitialized) {
            composeView.requestLayout()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val isLandscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        GamepadState.isLandscape.value = isLandscape
        if (!isLandscape) {
            GamepadState.isMinimized.value = true
        }
        if (::composeView.isInitialized) {
            windowManager.updateViewLayout(composeView, getLayoutParams())
        }
    }

    private fun getLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

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
        val params = getLayoutParams()

        composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                MaterialTheme {
                    GamepadOverlay(
                        controller = gamepadController,
                        onClose = { stopSelf() },
                        updateRegion = ::updateTouchableRegion
                    )
                }
            }
        }

        composeView.viewTreeObserver.addOnComputeInternalInsetsListener { info ->
            info.setTouchableInsets(android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
            info.touchableRegion.set(touchableRegion)
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

object GamepadState {
    val isMinimized = mutableStateOf(false)
    val isLandscape = mutableStateOf(true)
}

val LocalRegionUpdater = compositionLocalOf<(String, android.graphics.Rect?) -> Unit> { { _, _ -> } }

@Composable
fun Modifier.touchable(key: String): Modifier {
    val updater = LocalRegionUpdater.current
    DisposableEffect(key) {
        onDispose { updater(key, null) }
    }
    return this.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        updater(key, android.graphics.Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()))
    }
}

@Composable
fun GamepadOverlay(controller: GamepadController, onClose: () -> Unit, updateRegion: (String, android.graphics.Rect?) -> Unit) {
    CompositionLocalProvider(LocalRegionUpdater provides updateRegion) {
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        var isMinimized by GamepadState.isMinimized
        var currentMode by remember { mutableStateOf(OverlayMode.GAMEPAD) }

        LaunchedEffect(isLandscape) {
            if (!isLandscape) {
                isMinimized = true
            }
        }

        // Root Box does NOT consume touches outside children because it has no pointerInput modifier.
        // Touches falling outside the registered touchable regions will pass through to the OS.
        Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
            if (isMinimized) {
                MinimizedBubble(
                    onRestore = { isMinimized = false }
                )
            } else {
                Column(modifier = Modifier.wrapContentSize()) {
                    // Control Bar
                    Row(
                        modifier = Modifier
                            .wrapContentWidth()
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .touchable("control_bar"),
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

                    if (currentMode == OverlayMode.GAMEPAD) {
                        GamepadModeUI(controller)
                    } else {
                        RacingModeUI(controller)
                    }
                }
            }
        }
    }
}

@Composable
fun MinimizedBubble(onRestore: () -> Unit) {
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(200f) }

    // Wrap content so only the bubble itself intercepts touches
    Box(
        modifier = Modifier
            .wrapContentSize()
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .touchable("minimized_bubble")
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            }
    ) {
        FloatingActionButton(
            onClick = onRestore,
            modifier = Modifier.size(56.dp)
        ) {
            Text("🎮")
        }
    }
}

@Composable
fun GamepadModeUI(controller: GamepadController) {
    // Use wrapContentSize instead of fillMaxSize so layout bounds match actual controls
    Box(modifier = Modifier.wrapContentSize()) {
        // Top Bumpers
        Row(
            modifier = Modifier
                .wrapContentWidth()
                .padding(32.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.touchable("left_bumpers")) {
                GamepadButton(text = "LT", onPress = { controller.sendButtonDown("LT") }, onRelease = { controller.sendButtonUp("LT") })
                GamepadButton(text = "LB", onPress = { controller.sendButtonDown("LB") }, onRelease = { controller.sendButtonUp("LB") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.touchable("right_bumpers")) {
                GamepadButton(text = "RB", onPress = { controller.sendButtonDown("RB") }, onRelease = { controller.sendButtonUp("RB") })
                GamepadButton(text = "RT", onPress = { controller.sendButtonDown("RT") }, onRelease = { controller.sendButtonUp("RT") })
            }
        }

        // Center Buttons
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .touchable("center_buttons"),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom
        ) {
            GamepadButton(text = "Select", onPress = { controller.sendButtonDown("SELECT") }, onRelease = { controller.sendButtonUp("SELECT") })
            Spacer(modifier = Modifier.width(16.dp))
            GamepadButton(text = "Start", onPress = { controller.sendButtonDown("START") }, onRelease = { controller.sendButtonUp("START") })
        }

        // Left Controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Box(modifier = Modifier.touchable("dpad")) { DPad(controller) }
            Box(modifier = Modifier.touchable("left_joystick")) {
                Joystick(onMove = { x, y ->
                    controller.sendAxisEvent("LEFT_X", x)
                    controller.sendAxisEvent("LEFT_Y", y)
                })
            }
        }

        // Right Controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Box(modifier = Modifier.touchable("right_joystick")) {
                Joystick(onMove = { x, y ->
                    controller.sendAxisEvent("RIGHT_X", x)
                    controller.sendAxisEvent("RIGHT_Y", y)
                })
            }
            Box(modifier = Modifier.touchable("action_buttons")) { ActionButtons(controller) }
        }
    }
}

@Composable
fun RacingModeUI(controller: GamepadController) {
    Box(modifier = Modifier.wrapContentSize()) {
        // Left Controls
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(32.dp)
                .touchable("steering_wheel")
        ) {
            SteeringWheel(controller)
        }

        // Right Controls
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(32.dp)
                .touchable("pedals")
        ) {
            Pedals(controller)
        }
    }
}

@Composable
fun GamepadButton(
    text: String,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> onPress()
                is PressInteraction.Release, is PressInteraction.Cancel -> onRelease()
            }
        }
    }

    Button(
        onClick = { },
        modifier = modifier,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text)
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
                    val distance = kotlin.math.hypot(newX.toDouble(), newY.toDouble()).toFloat()
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
        GamepadButton(
            text = "Y",
            onPress = { controller.sendButtonDown("Y") },
            onRelease = { controller.sendButtonUp("Y") },
            modifier = Modifier.align(Alignment.TopCenter).size(48.dp)
        )
        GamepadButton(
            text = "A",
            onPress = { controller.sendButtonDown("A") },
            onRelease = { controller.sendButtonUp("A") },
            modifier = Modifier.align(Alignment.BottomCenter).size(48.dp)
        )
        GamepadButton(
            text = "X",
            onPress = { controller.sendButtonDown("X") },
            onRelease = { controller.sendButtonUp("X") },
            modifier = Modifier.align(Alignment.CenterStart).size(48.dp)
        )
        GamepadButton(
            text = "B",
            onPress = { controller.sendButtonDown("B") },
            onRelease = { controller.sendButtonUp("B") },
            modifier = Modifier.align(Alignment.CenterEnd).size(48.dp)
        )
    }
}

@Composable
fun DPad(controller: GamepadController) {
    Box(modifier = Modifier.size(140.dp)) {
        GamepadButton(
            text = "U",
            onPress = { controller.sendButtonDown("UP") },
            onRelease = { controller.sendButtonUp("UP") },
            modifier = Modifier.align(Alignment.TopCenter).size(44.dp)
        )
        GamepadButton(
            text = "D",
            onPress = { controller.sendButtonDown("DOWN") },
            onRelease = { controller.sendButtonUp("DOWN") },
            modifier = Modifier.align(Alignment.BottomCenter).size(44.dp)
        )
        GamepadButton(
            text = "L",
            onPress = { controller.sendButtonDown("LEFT") },
            onRelease = { controller.sendButtonUp("LEFT") },
            modifier = Modifier.align(Alignment.CenterStart).size(44.dp)
        )
        GamepadButton(
            text = "R",
            onPress = { controller.sendButtonDown("RIGHT") },
            onRelease = { controller.sendButtonUp("RIGHT") },
            modifier = Modifier.align(Alignment.CenterEnd).size(44.dp)
        )
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
        GamepadButton(
            text = "Brake",
            onPress = { controller.sendButtonDown("BRAKE") },
            onRelease = { controller.sendButtonUp("BRAKE") },
            modifier = Modifier
                .width(80.dp)
                .height(120.dp)
        )
        GamepadButton(
            text = "Gas",
            onPress = { controller.sendButtonDown("GAS") },
            onRelease = { controller.sendButtonUp("GAS") },
            modifier = Modifier
                .width(80.dp)
                .height(160.dp)
        )
    }
}

class GamepadAccessibilityService : AccessibilityService() {
    companion object {
        var instance: GamepadAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    fun dispatchKeyEvent(keyCode: Int, action: Int) {
        val eventTime = System.currentTimeMillis()
        val keyEvent = KeyEvent(eventTime, eventTime, action, keyCode, 0, 0, 
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_GAMEPAD)
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dispatchGesture(null, null, null)
            }
        } catch (e: Exception) {
            Log.e("GamepadAccessibility", "Failed to dispatch gesture", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
}

class GamepadController {
    private val TAG = "GamepadController"
    private val instrumentation = Instrumentation()
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun sendButtonDown(button: String) {
        Log.d(TAG, "Button down: $button")
        val keyCode = getAndroidKeyCode(button)
        if (keyCode != 0) {
            executor.execute {
                try {
                    instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to inject key down event for $button", e)
                    executeShellCommand("input keyevent --down $keyCode")
                }
            }
        }
    }

    fun sendButtonUp(button: String) {
        Log.d(TAG, "Button up: $button")
        val keyCode = getAndroidKeyCode(button)
        if (keyCode != 0) {
            executor.execute {
                try {
                    instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to inject key up event for $button", e)
                    executeShellCommand("input keyevent --up $keyCode")
                }
            }
        }
    }

    fun sendButtonPress(button: String) {
        sendButtonDown(button)
        executor.execute {
            try {
                Thread.sleep(50)
                sendButtonUp(button)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to complete button press for $button", e)
            }
        }
    }

    fun sendAxisEvent(axis: String, value: Float) {
        Log.d(TAG, "Axis moved: $axis to $value")
        executor.execute {
            try {
                when (axis) {
                    "LEFT_X" -> {
                        if (value < -0.5f) instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
                        else instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
                        
                        if (value > 0.5f) instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                        else instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
                    }
                    "LEFT_Y" -> {
                        if (value < -0.5f) instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
                        else instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP))
                        
                        if (value > 0.5f) instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
                        else instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inject axis event", e)
            }
        }
    }

    private fun getAndroidKeyCode(button: String): Int {
        return when (button) {
            "A" -> KeyEvent.KEYCODE_BUTTON_A
            "B" -> KeyEvent.KEYCODE_BUTTON_B
            "X" -> KeyEvent.KEYCODE_BUTTON_X
            "Y" -> KeyEvent.KEYCODE_BUTTON_Y
            "LB" -> KeyEvent.KEYCODE_BUTTON_L1
            "RB" -> KeyEvent.KEYCODE_BUTTON_R1
            "LT" -> KeyEvent.KEYCODE_BUTTON_L2
            "RT" -> KeyEvent.KEYCODE_BUTTON_R2
            "START" -> KeyEvent.KEYCODE_BUTTON_START
            "SELECT" -> KeyEvent.KEYCODE_BUTTON_SELECT
            "UP" -> KeyEvent.KEYCODE_DPAD_UP
            "DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
            "LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
            "RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "BRAKE" -> KeyEvent.KEYCODE_BUTTON_L2
            "GAS" -> KeyEvent.KEYCODE_BUTTON_R2
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