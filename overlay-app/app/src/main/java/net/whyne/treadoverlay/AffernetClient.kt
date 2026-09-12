package net.whyne.treadoverlay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log

/**
 * Binds Peloton's AffernetService (hardware bridge, talks to the tread's MCB over serial)
 * using the ITreadInterface AIDL and polls current speed / incline.
 *
 * Protocol verified against local decompile of affernetservice.apk:
 *  - service exported, intent action com.onepeloton.affernetservice.ITreadInterface
 *  - transact 17 = getCurrentSpeed() -> int, 18 = getCurrentIncline() -> int
 *  - raw values are tenths: display = abs(raw) / 10.0 (per TreadSensorDataUtils)
 * Read-only getters; works even while the tread is locked (this is what the stock
 * status bar uses indirectly). Binding pattern from grupetto (github.com/doudar/grupetto).
 */
class AffernetClient(
    private val context: Context,
    private val onMetrics: (speedMph: Double, inclinePct: Double) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        const val TAG = "TreadOverlayAffernet"
        const val SERVICE_PACKAGE = "com.onepeloton.affernetservice"
        const val INTERFACE_DESCRIPTOR = "com.onepeloton.affernetservice.ITreadInterface"
        const val CODE_GET_CURRENT_SPEED = 17
        const val CODE_GET_CURRENT_INCLINE = 18
        const val POLL_INTERVAL_MS = 500L
        const val REBIND_DELAY_MS = 5000L
    }

    private var serviceBinder: IBinder? = null
    private var stopped = false
    private var loggedFirst = false
    private val handler = Handler(Looper.getMainLooper())

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (stopped) return
            val b = serviceBinder
            if (b != null && b.isBinderAlive) {
                try {
                    val rawSpeed = callIntGetter(b, CODE_GET_CURRENT_SPEED)
                    val rawIncline = callIntGetter(b, CODE_GET_CURRENT_INCLINE)
                    if (!loggedFirst) {
                        loggedFirst = true
                        Log.i(TAG, "first poll ok: rawSpeed=$rawSpeed rawIncline=$rawIncline")
                        onStatus("polling ok raw=($rawSpeed,$rawIncline)")
                    }
                    onMetrics(
                        Math.abs(rawSpeed) / 10.0,
                        Math.abs(rawIncline) / 10.0
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "poll failed", e)
                    onStatus("poll failed: ${e.message}")
                }
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "AffernetService connected: $name")
            onStatus("affernet connected")
            serviceBinder = binder
            loggedFirst = false
            handler.removeCallbacks(pollRunnable)
            handler.post(pollRunnable)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "AffernetService disconnected")
            onStatus("affernet disconnected")
            serviceBinder = null
        }

        override fun onNullBinding(name: ComponentName?) {
            Log.e(TAG, "AffernetService NULL binding; retry in ${REBIND_DELAY_MS}ms")
            onStatus("affernet null binding")
            handler.postDelayed({
                if (!stopped) {
                    try { context.unbindService(this) } catch (_: Exception) {}
                    bind()
                }
            }, REBIND_DELAY_MS)
        }
    }

    fun start(): Boolean = bind()

    private fun bind(): Boolean {
        val intent = Intent(INTERFACE_DESCRIPTOR).apply { setPackage(SERVICE_PACKAGE) }
        val ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        Log.i(TAG, "bindService returned $ok")
        if (!ok) onStatus("affernet bind failed")
        return ok
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        try {
            context.unbindService(connection)
        } catch (e: Exception) {
            Log.w(TAG, "unbind failed", e)
        }
    }

    private fun callIntGetter(binder: IBinder, code: Int): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_DESCRIPTOR)
            if (!binder.transact(code, data, reply, 0)) {
                throw IllegalStateException("transact $code returned false")
            }
            reply.readException()
            return reply.readInt()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
