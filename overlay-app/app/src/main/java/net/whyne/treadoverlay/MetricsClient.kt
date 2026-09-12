package net.whyne.treadoverlay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log

/**
 * Binds to Peloton's internal MetricsService (com.onepeloton.workoutservices.app)
 * and reads live tread metrics (speed, incline) via its undocumented AIDL.
 *
 * Protocol verified against local decompile of workoutservices.apk v1.4.1302:
 *  - service action = IMetricsServiceInterface descriptor, exported, permission
 *    com.onepeloton.permission.METRICS_SERVICE (normal protection level)
 *  - transact 1 = perform(Bundle) [oneway], 2 = fetch(String)->Bundle (returns empty),
 *    3 = registerCallback(IMetricsServiceCallback), 4 = unregisterCallback
 *  - callback transact 1 = onUpdate(Bundle): int hasBundle + Bundle, reply writeNoException
 *  - metrics bundle: "metrics_callback_type_name" == "METRICS",
 *    "metrics_tread_map" -> Map{ "speed"/"incline"/"targetSpeed"/"targetIncline" ->
 *        {current,average,max}, "distance","calories","output","elevation","isManualMode" }
 *  - perform bundle key "metrics_service_action_name" = "START" -> startMetrics()
 *    (only sent if no data arrives on its own; never send "END")
 * Binding pattern inspired by the open-source grupetto project (github.com/doudar/grupetto).
 */
class MetricsClient(
    private val context: Context,
    private val onMetrics: (treadMap: Map<*, *>) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        const val TAG = "TreadOverlayMetrics"

        const val SERVICE_PACKAGE = "com.onepeloton.workoutservices.app"
        const val INTERFACE_DESCRIPTOR =
            "com.onepeloton.workoutservices.metrics.IMetricsServiceInterface"
        const val CALLBACK_DESCRIPTOR =
            "com.onepeloton.workoutservices.metrics.IMetricsServiceCallback"

        const val CODE_PERFORM = 1
        const val CODE_REGISTER_CALLBACK = 3
        const val CODE_UNREGISTER_CALLBACK = 4
        const val CODE_CB_ON_UPDATE = 1

        const val KEY_ACTION_NAME = "metrics_service_action_name"
        const val KEY_CALLBACK_TYPE_NAME = "metrics_callback_type_name"
        const val KEY_TREAD_MAP = "metrics_tread_map"

        const val START_METRICS_FALLBACK_DELAY_MS = 8000L
        const val REBIND_DELAY_MS = 5000L
    }

    private var serviceBinder: IBinder? = null
    @Volatile private var gotTreadData = false
    private var sentStart = false
    private var stopped = false
    private val handler = Handler(Looper.getMainLooper())

    private val startMetricsFallback = Runnable {
        val b = serviceBinder ?: return@Runnable
        if (!gotTreadData && !sentStart) {
            sentStart = true
            try {
                Log.i(TAG, "no tread data yet; sending perform(START)")
                onStatus("sending START")
                performAction(b, "START")
            } catch (e: Exception) {
                Log.e(TAG, "perform START failed", e)
                onStatus("START failed: ${e.message}")
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "MetricsService connected: $name")
            onStatus("connected")
            serviceBinder = binder
            if (binder == null) return
            try {
                registerCallback(binder)
                onStatus("callback registered")
                handler.postDelayed(startMetricsFallback, START_METRICS_FALLBACK_DELAY_MS)
            } catch (e: Exception) {
                Log.e(TAG, "registerCallback failed", e)
                onStatus("cb reg failed: ${e.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "MetricsService disconnected")
            onStatus("disconnected")
            serviceBinder = null
        }

        override fun onNullBinding(name: ComponentName?) {
            // MetricsService.onBind returns null when its internal auth check throws
            Log.e(TAG, "MetricsService NULL binding; retry in ${REBIND_DELAY_MS}ms")
            onStatus("null binding, retrying")
            handler.postDelayed({
                if (!stopped) {
                    try { context.unbindService(this) } catch (_: Exception) {}
                    bind()
                }
            }, REBIND_DELAY_MS)
        }
    }

    private val callbackBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == CODE_CB_ON_UPDATE) {
                try {
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    if (data.readInt() != 0) {
                        val bundle = Bundle.CREATOR.createFromParcel(data)
                        // Use our classloader so stubbed Peloton parcelables resolve
                        bundle.classLoader = MetricsClient::class.java.classLoader
                        handleBundle(bundle)
                    }
                } catch (t: Throwable) {
                    // Bundles for other callback types can contain Peloton parcelables
                    // we cannot deserialize; ignore them.
                    Log.d(TAG, "skipping undecodable callback bundle: $t")
                }
                reply?.writeNoException()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    fun start(): Boolean = bind()

    private fun bind(): Boolean {
        val intent = Intent(INTERFACE_DESCRIPTOR).apply { setPackage(SERVICE_PACKAGE) }
        val ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        Log.i(TAG, "bindService returned $ok")
        if (!ok) onStatus("bind failed")
        return ok
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        serviceBinder?.let { b ->
            try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(INTERFACE_DESCRIPTOR)
                    data.writeStrongBinder(callbackBinder)
                    b.transact(CODE_UNREGISTER_CALLBACK, data, reply, 0)
                    reply.readException()
                } finally {
                    data.recycle(); reply.recycle()
                }
            } catch (e: Exception) {
                Log.w(TAG, "unregister failed", e)
            }
        }
        try {
            context.unbindService(connection)
        } catch (e: Exception) {
            Log.w(TAG, "unbind failed", e)
        }
    }

    private fun registerCallback(binder: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_DESCRIPTOR)
            data.writeStrongBinder(callbackBinder)
            val ok = binder.transact(CODE_REGISTER_CALLBACK, data, reply, 0)
            Log.i(TAG, "registerCallback transact ok=$ok")
            if (ok) reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun performAction(binder: IBinder, action: String) {
        val bundle = Bundle().apply { putString(KEY_ACTION_NAME, action) }
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_DESCRIPTOR)
            data.writeInt(1)
            bundle.writeToParcel(data, 0)
            binder.transact(CODE_PERFORM, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }
    }

    private fun handleBundle(bundle: Bundle) {
        val type = bundle.getString(KEY_CALLBACK_TYPE_NAME)
        if (type != "METRICS") {
            Log.v(TAG, "ignoring callback type=$type")
            return
        }
        val treadMap = bundle.get(KEY_TREAD_MAP) as? Map<*, *>
        if (treadMap == null) {
            Log.d(TAG, "METRICS bundle without tread map; keys=${bundle.keySet()}")
            return
        }
        gotTreadData = true
        handler.removeCallbacks(startMetricsFallback)
        onMetrics(treadMap)
    }
}
