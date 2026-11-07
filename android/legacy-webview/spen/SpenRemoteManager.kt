package app.pocketfader.legacy

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.samsung.android.sdk.penremote.AirMotionEvent
import com.samsung.android.sdk.penremote.ButtonEvent
import com.samsung.android.sdk.penremote.SpenEvent
import com.samsung.android.sdk.penremote.SpenEventListener
import com.samsung.android.sdk.penremote.SpenRemote
import com.samsung.android.sdk.penremote.SpenUnit
import com.samsung.android.sdk.penremote.SpenUnitManager
import kotlin.math.abs

/**
 * S Pen "air actions" → Cue remote:
 *   single button press (no motion) = play / pause
 *   hold + move up / down           = volume
 *   hold + move left / right        = skip prev / next
 *
 * Requires a Bluetooth S Pen (S Pen Pro / Fold / Ultra) with Air actions enabled, plus the
 * Samsung S Pen Remote SDK aar in app/libs (see spen/README.md). Everything is wrapped in
 * try/catch so it silently no-ops on devices/pens without air-action support.
 */
class SpenRemoteManager(
    private val context: Context,
    private val onPlayPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrev: () -> Unit,
    private val onVolume: (Int) -> Unit,
) {
    private val ui = Handler(Looper.getMainLooper())
    private val spen = SpenRemote.getInstance()
    private var manager: SpenUnitManager? = null

    // gesture state while the button is held
    private var held = false
    private var accumX = 0f
    private var accumY = 0f
    private var totalAbs = 0f
    private var volCarry = 0f

    // Tuning — air-motion deltas are arbitrary units; adjust to taste on the device.
    private val TAP_DEADZONE = 120f     // below this total travel = a tap (play/pause)
    private val SKIP_THRESHOLD = 300f   // horizontal travel to count as a skip flick
    private val VOLUME_STEP = 240f      // vertical travel per volume tick
    private val VOLUME_DELTA = 3        // % per tick

    fun connect() {
        if (manager != null) return
        try {
            spen.connect(context, object : SpenRemote.ConnectionResultCallback {
                override fun onSuccess(m: SpenUnitManager) {
                    manager = m
                    if (spen.isFeatureEnabled(SpenRemote.FEATURE_TYPE_BUTTON))
                        m.registerSpenEventListener(buttonListener, m.getUnit(SpenUnit.TYPE_BUTTON))
                    if (spen.isFeatureEnabled(SpenRemote.FEATURE_TYPE_AIR_MOTION))
                        m.registerSpenEventListener(airListener, m.getUnit(SpenUnit.TYPE_AIR_MOTION))
                }
                override fun onFailure(error: Int) {}
            })
        } catch (_: Throwable) {}
    }

    fun disconnect() {
        try {
            manager?.let { m ->
                m.unregisterSpenEventListener(m.getUnit(SpenUnit.TYPE_BUTTON))
                m.unregisterSpenEventListener(m.getUnit(SpenUnit.TYPE_AIR_MOTION))
            }
            spen.disconnect(context)
        } catch (_: Throwable) {}
        manager = null
    }

    private val buttonListener = SpenEventListener { event: SpenEvent ->
        val be = ButtonEvent(event)
        when (be.action) {
            ButtonEvent.ACTION_DOWN -> { held = true; accumX = 0f; accumY = 0f; totalAbs = 0f; volCarry = 0f }
            ButtonEvent.ACTION_UP -> {
                held = false
                when {
                    totalAbs < TAP_DEADZONE -> ui.post { onPlayPause() }                       // a tap
                    abs(accumX) > abs(accumY) && abs(accumX) > SKIP_THRESHOLD ->                // horizontal flick
                        ui.post { if (accumX > 0) onNext() else onPrev() }
                    // vertical was already handled live as volume
                }
            }
        }
    }

    private val airListener = SpenEventListener { event: SpenEvent ->
        if (!held) return@SpenEventListener
        val ae = AirMotionEvent(event)
        accumX += ae.deltaX
        accumY += ae.deltaY
        totalAbs += abs(ae.deltaX) + abs(ae.deltaY)
        // volume adjusts live, but only while the gesture is clearly vertical (so a
        // left/right skip flick doesn't nudge the volume on the way)
        if (abs(accumY) > abs(accumX)) {
            volCarry += ae.deltaY
            while (abs(volCarry) >= VOLUME_STEP) {
                val up = volCarry < 0        // pen up = louder; flip this if it feels inverted
                volCarry += if (up) VOLUME_STEP else -VOLUME_STEP
                ui.post { onVolume(if (up) VOLUME_DELTA else -VOLUME_DELTA) }
            }
        }
    }
}
