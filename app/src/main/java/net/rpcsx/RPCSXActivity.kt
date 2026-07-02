package net.rpcsx

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isInvisible
import androidx.core.view.updateLayoutParams
import net.rpcsx.databinding.ActivityRpcs3Binding
import net.rpcsx.dialogs.AlertDialogQueue
import net.rpcsx.overlay.State
import net.rpcsx.utils.GeneralSettings
import net.rpcsx.utils.InputBindingPrefs
import net.rpcsx.utils.ShakeMotionDetector
import net.rpcsx.utils.MotionSensorManager
import kotlin.concurrent.thread
import kotlin.math.abs

class RPCSXActivity : ComponentActivity() {
    private lateinit var binding: ActivityRpcs3Binding
    private lateinit var unregisterUsbEventListener: () -> Unit
    private var gamePadState: State = State()
    private var usesAxisL2 = false
    private var usesAxisR2 = false
    private var bootThread: Thread? = null
    private val inputBindings by lazy { InputBindingPrefs.loadBindings() }
    
    private lateinit var shakeDetector: ShakeMotionDetector
    private lateinit var motionSensorManager: MotionSensorManager

    private val watcherHandler = Handler(Looper.getMainLooper())
    private val stateWatcher = object : Runnable {
        override fun run() {
            if (RPCSX.getState() == EmulatorState.Stopped) {
                finish()
                return
            }
            watcherHandler.postDelayed(this, 500)
        }
    }

    private fun startExitWatcher() {
        watcherHandler.removeCallbacks(stateWatcher)
        watcherHandler.post(stateWatcher)
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when (RPCSX.getState()) {
                EmulatorState.Running, EmulatorState.Paused ->
                    runCatching { RPCSX.instance.openHomeMenu() }
                else ->
                    finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRpcs3Binding.inflate(layoutInflater)
        setContentView(binding.root)

        unregisterUsbEventListener = listenUsbEvents(this)
        enableFullScreenImmersive()
        onBackPressedDispatcher.addCallback(this, backCallback)

        net.rpcsx.utils.ThermalManager.register(this)
        net.rpcsx.utils.AdpfManager.register(this)

        if ((GeneralSettings["sustained_performance"] as? Boolean) == true) {
            (getSystemService(POWER_SERVICE) as? PowerManager)?.let { pm ->
                if (pm.isSustainedPerformanceModeSupported) {
                    window.setSustainedPerformanceMode(true)
                }
            }
        }

        binding.oscToggle.setOnClickListener {
            binding.padOverlay.isInvisible = !binding.padOverlay.isInvisible
            binding.oscToggle.setImageResource(if (binding.padOverlay.isInvisible) R.drawable.ic_osc_off else R.drawable.ic_show_osc)
        }

        shakeDetector = ShakeMotionDetector(this) { pressed ->
            injectShakeEvent(pressed)
        }

        motionSensorManager = MotionSensorManager(this)

        val gamePath = intent.getStringExtra("path")!!
        RPCSX.lastPlayedGame = gamePath

        bootThread = thread {
            if (RPCSX.getState() != EmulatorState.Stopped) {
                val state = RPCSX.getState()
                Log.w("RPCSX State", state.name)

                if (state == EmulatorState.Paused && RPCSX.activeGame.value == gamePath) {
                    RPCSX.instance.resume()
                    runOnUiThread { startExitWatcher() }
                    return@thread
                }

                if (RPCSX.getState() != EmulatorState.Stopping && RPCSX.getState() != EmulatorState.Stopped) {
                    RPCSX.instance.kill()

                    while (RPCSX.getState() != EmulatorState.Stopped) {
                        Thread.sleep(300)
                        if (Thread.interrupted()) {
                            return@thread
                        }
                    }
                }
            }

            Log.w("RPCSX State", RPCSX.getState().name)
            RPCSX.activeGame.value = gamePath

            val bootResult = RPCSX.boot(gamePath)
            if (bootResult != BootResult.NoErrors) {
                AlertDialogQueue.showDialog(
                    getString(R.string.failed_to_boot),
                    getString(R.string.error_with_msg, bootResult.name)
                )
                finish()
            } else {
                runOnUiThread { startExitWatcher() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        shakeDetector.start()
        motionSensorManager.start()
    }

    override fun onPause() {
        super.onPause()
        shakeDetector.stop()
        motionSensorManager.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        watcherHandler.removeCallbacks(stateWatcher)
        RPCSX.state.value = EmulatorState.Paused
        net.rpcsx.utils.ThermalManager.unregister()
        net.rpcsx.utils.AdpfManager.unregister()
        unregisterUsbEventListener()
        bootThread?.interrupt()
        bootThread?.join()
    }

    private fun injectShakeEvent(pressed: Boolean) {
        val padBit = inputBindings[InputBindingPrefs.KEYCODE_SHAKE_MOTION] ?: return
        if (padBit.first == 0) return
        
        if (pressed) {
            RPCSX.shakeDigital1 = if (padBit.second == 0) padBit.first else 0
            RPCSX.shakeDigital2 = if (padBit.second == 1) padBit.first else 0
        } else {
            RPCSX.shakeDigital1 = 0
            RPCSX.shakeDigital2 = 0
        }
        sendGamepadData()
    }

    private fun keyCodeToPadBit(keyCode: Int): Pair<Int, Int> {
        val event = inputBindings[keyCode] ?: Pair(0, 0)
        
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            if (usesAxisR2) return Pair(0, 0) else return event
        }
        
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
            if (usesAxisL2) return Pair(0, 0) else return event
        }
        
        return event
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null || (event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD)) == 0 || event.repeatCount != 0) {
            return super.onKeyDown(keyCode, event)
        }
        val padBit = keyCodeToPadBit(keyCode)
        if (padBit.first == 0) {
            return super.onKeyDown(keyCode, event)
        }

        gamePadState.digital[padBit.second] = gamePadState.digital[padBit.second] or padBit.first
        sendGamepadData()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null || event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) {
            return super.onKeyUp(keyCode, event)
        }

        val padBit = keyCodeToPadBit(keyCode)
        if (padBit.first == 0) {
            return super.onKeyUp(keyCode, event)
        }

        gamePadState.digital[padBit.second] =
            gamePadState.digital[padBit.second] and padBit.first.inv()
        sendGamepadData()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event == null || event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || event.action != MotionEvent.ACTION_MOVE) {
            return super.onGenericMotionEvent(event)
        }

        if (event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.1) {
            gamePadState.digital[1] =
                gamePadState.digital[1] or Digital2Flags.CELL_PAD_CTRL_L2.bit
            usesAxisL2 = true
        } else if (usesAxisL2) {
            usesAxisL2 = false
            gamePadState.digital[1] =
                gamePadState.digital[1] and Digital2Flags.CELL_PAD_CTRL_L2.bit.inv()
        }

        if (event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.1) {
            gamePadState.digital[1] =
                gamePadState.digital[1] or Digital2Flags.CELL_PAD_CTRL_R2.bit
            usesAxisR2 = true
        } else if (usesAxisR2) {
            usesAxisR2 = false
            gamePadState.digital[1] =
                gamePadState.digital[1] and Digital2Flags.CELL_PAD_CTRL_R2.bit.inv()
        }

        val dpadX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val dpadY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        gamePadState.digital[0] =
            gamePadState.digital[0] and (Digital1Flags.CELL_PAD_CTRL_LEFT.bit or Digital1Flags.CELL_PAD_CTRL_RIGHT.bit or Digital1Flags.CELL_PAD_CTRL_UP.bit or Digital1Flags.CELL_PAD_CTRL_DOWN.bit).inv()
        if (abs(dpadX) > 0.1f) {
            if (dpadX < 0) {
                gamePadState.digital[0] =
                    gamePadState.digital[0] or Digital1Flags.CELL_PAD_CTRL_LEFT.bit
            } else {
                gamePadState.digital[0] =
                    gamePadState.digital[0] or Digital1Flags.CELL_PAD_CTRL_RIGHT.bit
            }
        }

        if (abs(dpadY) > 0.1f) {
            if (dpadY < 0) {
                gamePadState.digital[0] =
                    gamePadState.digital[0] or Digital1Flags.CELL_PAD_CTRL_UP.bit
            } else {
                gamePadState.digital[0] =
                    gamePadState.digital[0] or Digital1Flags.CELL_PAD_CTRL_DOWN.bit
            }
        }

        gamePadState.leftStickX = (event.getAxisValue(MotionEvent.AXIS_X) * 127 + 128).toInt()
        gamePadState.leftStickY = (event.getAxisValue(MotionEvent.AXIS_Y) * 127 + 128).toInt()
        gamePadState.rightStickX = (event.getAxisValue(MotionEvent.AXIS_Z) * 127 + 128).toInt()
        gamePadState.rightStickY = (event.getAxisValue(MotionEvent.AXIS_RZ) * 127 + 128).toInt()

        sendGamepadData()
        return true
    }

    private fun sendGamepadData() {
        val finalDigital1 = gamePadState.digital[0] or RPCSX.shakeDigital1
        val finalDigital2 = gamePadState.digital[1] or RPCSX.shakeDigital2
        
        val motionEnabled = GeneralSettings["motion_sensor_enabled"] as? Boolean ?: false
        val targetStick = GeneralSettings["motion_target_stick"] as? Int ?: 1
        val motionMode = GeneralSettings["motion_mode"] as? String ?: "priority" // "priority" или "sum"

        val finalLeftX: Int
        val finalLeftY: Int
        val finalRightX: Int
        val finalRightY: Int
        
        if (targetStick == 0) {
            // Гироскоп → левый стик
            if (motionEnabled) {
                if (motionMode == "priority") {
                    // Priority mode: гироскоп только если виртуальный стик неактивен
                    val virtualStickActive = binding.padOverlay.leftStickActive()
                    if (!virtualStickActive) {
                        finalLeftX = RPCSX.motionLeftStickX
                        finalLeftY = RPCSX.motionLeftStickY
                    } else {
                        finalLeftX = gamePadState.leftStickX
                        finalLeftY = gamePadState.leftStickY
                    }
                } else {
                    // Sum mode: гироскоп добавляется к виртуальному стику
                    val virtualX = gamePadState.leftStickX
                    val virtualY = gamePadState.leftStickY
                    val motionX = RPCSX.motionLeftStickX - 128
                    val motionY = RPCSX.motionLeftStickY - 128
                    finalLeftX = (virtualX + motionX).coerceIn(0, 255)
                    finalLeftY = (virtualY + motionY).coerceIn(0, 255)
                }
            } else {
                finalLeftX = gamePadState.leftStickX
                finalLeftY = gamePadState.leftStickY
            }
            finalRightX = gamePadState.rightStickX
            finalRightY = gamePadState.rightStickY
        } else {
            // Гироскоп → правый стик
            finalLeftX = gamePadState.leftStickX
            finalLeftY = gamePadState.leftStickY
            if (motionEnabled) {
                if (motionMode == "priority") {
                    val virtualStickActive = binding.padOverlay.rightStickActive()
                    if (!virtualStickActive) {
                        finalRightX = RPCSX.motionRightStickX
                        finalRightY = RPCSX.motionRightStickY
                    } else {
                        finalRightX = gamePadState.rightStickX
                        finalRightY = gamePadState.rightStickY
                    }
                } else {
                    val virtualX = gamePadState.rightStickX
                    val virtualY = gamePadState.rightStickY
                    val motionX = RPCSX.motionRightStickX - 128
                    val motionY = RPCSX.motionRightStickY - 128
                    finalRightX = (virtualX + motionX).coerceIn(0, 255)
                    finalRightY = (virtualY + motionY).coerceIn(0, 255)
                }
            } else {
                finalRightX = gamePadState.rightStickX
                finalRightY = gamePadState.rightStickY
            }
        }

        RPCSX.instance.overlayPadData(
            finalDigital1,
            finalDigital2,
            finalLeftX,
            finalLeftY,
            finalRightX,
            finalRightY
        )
    }

    private fun enableFullScreenImmersive() {
        with(window) {
            WindowCompat.setDecorFitsSystemWindows(this, false)
            val insetsController = WindowInsetsControllerCompat(this, decorView)
            insetsController.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            attributes.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        applyInsetsToPadOverlay()
    }

    private fun applyInsetsToPadOverlay() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.padOverlay) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<MarginLayoutParams> {
                leftMargin = insets.left
                rightMargin = insets.right
                topMargin = insets.top
                bottomMargin = insets.bottom
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableFullScreenImmersive()
    }
}