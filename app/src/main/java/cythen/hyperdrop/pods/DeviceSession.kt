package cythen.hyperdrop.pods

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.os.SystemClock
import cythen.hyperdrop.hook.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import cythen.hyperdrop.BuildConfig
import cythen.hyperdrop.config.ConfigManager
import cythen.hyperdrop.device.DeviceRegistry
import cythen.hyperdrop.device.protocol.GaiaCommandTable
import cythen.hyperdrop.device.protocol.GaiaReport
import cythen.hyperdrop.device.protocol.GaiaTransport
import cythen.hyperdrop.utils.MediaControl
import cythen.hyperdrop.utils.SystemApisUtils
import cythen.hyperdrop.utils.SystemApisUtils.setIconVisibility
import cythen.hyperdrop.utils.miuiStrongToast.MiuiStrongToastUtil
import cythen.hyperdrop.utils.miuiStrongToast.MiuiStrongToastUtil.cancelPodsNotificationByMiuiBt
import cythen.hyperdrop.utils.miuiStrongToast.data.BatteryParams
import cythen.hyperdrop.utils.miuiStrongToast.data.OppoPodsAction
import cythen.hyperdrop.utils.miuiStrongToast.data.PodParams
import java.io.IOException
import java.io.InputStream
import android.content.SharedPreferences
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "HyperDrop-DeviceSession"
private const val AUTO_RECONNECT_DELAY_MS = 120_000L
private const val APP_UI_ACTIVE_TIMEOUT_MS = 75_000L
private const val DEFAULT_GAIN_LEVEL = 1 // medium (UI low/medium/high = 0/1/2)
/** After an app-originated noise-mode SET, query responses that disagree with the target
 *  within this window are the earbud's stale pre-SET state (Robin applies the SET
 *  asynchronously and never echoes it) and are ignored. */
private const val ANC_CONFIRM_WINDOW_MS = 3_000L
/** Delayed re-query after a SET; its response lands outside the confirm window, so the UI
 *  converges even though Robin sends no SET echo at all. */
private const val ANC_CONFIRM_QUERY_DELAY_MS = 4_000L
/** Noise-mode poll interval while an ANC surface (app page / popup / system page) is open. */
private const val ANC_UI_POLL_INTERVAL_MS = 5_000L

/** UI/system write actions that must not reach the earbuds while control is yielded. */
private val YIELD_BLOCKED_ACTIONS = setOf(
    OppoPodsAction.ACTION_ANC_SELECT,
    OppoPodsAction.ACTION_REFRESH_STATUS,
    OppoPodsAction.ACTION_GAIN_LEVEL_SET,
    OppoPodsAction.ACTION_LED_STATE_SET,
    OppoPodsAction.ACTION_CYCLE_ANC,
    OppoPodsAction.ACTION_RFCOMM_DEBUG_SEND,
    OppoPodsAction.ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET,
    OppoPodsAction.ACTION_SPATIAL_AUDIO_SET,
    OppoPodsAction.ACTION_EQ_PRESET_SET,
)

@SuppressLint("MissingPermission", "StaticFieldLeak")
class DeviceSession {

    // Basic Objects
    private var socket: BluetoothSocket? = null
    private var mContext: Context? = null
    lateinit var mDevice: BluetoothDevice
    private lateinit var mPrefs: SharedPreferences

    private var scanToken: MediaRouter2.ScanToken? = null
    var routes: List<MediaRoute2Info> = listOf()
    private lateinit var mediaRouter: MediaRouter2

    // Status
    private var mShowedConnectedToast = false
    private var isConnected = false
    private var lastTempBatt = 0
    lateinit var currentBatteryParams: BatteryParams
    private var currentAnc: Int = 1
    private var pendingAnc: Int? = null
    private var pendingAncUntilMs: Long = 0L
    private var ancPollJob: kotlinx.coroutines.Job? = null
    /** -1 = unknown / not in smart mode; otherwise a [NoiseControlMode].ordinal of the
     *  level smart mode is currently auto-applying (light/medium/deep). */
    private var currentSmartAncLevel: Int = -1
    private var currentTransparencyVocalEnhancement: Boolean = false
    private var currentSpatialAudioMode: Int = SpatialAudioMode.OFF
    /** -1 = unknown; otherwise one of [EqPreset.ALL]. */
    private var currentEqPreset: Int = -1
    private var currentGainLevel = DEFAULT_GAIN_LEVEL
    private var currentLedEnabled = true
    private var lastKnownLeftBattery: Int = 0
    private var lastKnownRightBattery: Int = 0
    private var lastKnownCaseBattery: Int = 0
    private var lastKnownLeftCharging: Boolean = false
    private var lastKnownRightCharging: Boolean = false
    private var lastKnownCaseCharging: Boolean = false
    private var cachedDeviceName: String = ""
    private var receiverRegistered = false
    private var routeScanStarted = false
    private var appUiActive = false
    private var appUiActiveUntilMs = 0L

    /** True while control has been yielded to the official MOONDROP app. */
    private var yielded = false
    private var yieldWatchJob: kotlinx.coroutines.Job? = null
    /** Monotonic timestamp of the last ACTION_OFFICIAL_APP_PRESENCE received in this
     *  (bluetooth) process. In-memory is authoritative: it avoids depending on
     *  RemotePreferences writes from another process propagating here. */
    private var officialAppLastSeenMs = 0L

    data class StatusSnapshot(
        val battery: BatteryParams?,
        val anc: Int,
        val transparencyVocalEnhancement: Boolean,
        val address: String?,
        val deviceName: String?,
        val connected: Boolean,
        val connecting: Boolean,
        val reconnectPending: Boolean,
    )

    // RFCOMM jobs
    private var connectionJob: kotlinx.coroutines.Job? = null
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var readerJob: kotlinx.coroutines.Job? = null
    private val reconnectAttempts = AtomicInteger(0)
    private var reconnectPending = false
    private val decoder = GaiaTransport.StreamDecoder()

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            handleUIEvent(p1!!)
        }
    }

    private fun changeUIAncStatus(status: Int) {
        if (status < 1 || status > 8) return
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_ANC_CHANGED) {
            if (::mDevice.isInitialized) this.putExtra("address", mDevice.address)
            this.putExtra("status", status)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_ANC_CHANGED) {
            putExtra("status", status)
        }
    }

    private fun changeUIBatteryStatus(status: BatteryParams) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            if (::mDevice.isInitialized) this.putExtra("address", mDevice.address)
            this.putExtra("status", status)
            putBatteryExtras(status)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            putExtra("status", status)
            putBatteryExtras(status)
        }
    }

    private var currentWearStatus = WearStatus()

    private fun changeUIWearStatus(status: WearStatus) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_WEAR_STATUS_CHANGED) {
            if (::mDevice.isInitialized) this.putExtra("address", mDevice.address)
            putWearStatusExtras(status)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_WEAR_STATUS_CHANGED) {
            putWearStatusExtras(status)
        }
    }

    private fun changeUITransparencyVocalEnhancementStatus(enabled: Boolean) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED) {
            this.putExtra("enabled", enabled)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED) {
            putExtra("enabled", enabled)
        }
    }

    private fun changeUISpatialAudioStatus(mode: Int) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED) {
            this.putExtra("mode", mode)
        }
    }

    private fun changeUIEqPreset(presetId: Int) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_EQ_PRESET_CHANGED) {
            this.putExtra("preset", presetId)
        }
    }

    private fun changeUISmartAncLevel(ordinal: Int) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_SMART_ANC_LEVEL_CHANGED) {
            this.putExtra("ordinal", ordinal)
        }
    }

    private fun changeUIGainLevel(level: Int) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_GAIN_LEVEL_CHANGED) {
            putExtra("level", level)
        }
    }

    private fun changeUILedState(enabled: Boolean) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_LED_STATE_CHANGED) {
            putExtra("enabled", enabled)
        }
    }

    fun handleUIEvent(intent: Intent) {
        if (yielded && YIELD_BLOCKED_ACTIONS.contains(intent.action ?: "")) {
            Log.d(TAG, "dropped UI event while yielded: ${intent.action}")
            return
        }
        when (intent.action) {
            OppoPodsAction.ACTION_GET_PODS_MAC -> {
                if (::mDevice.isInitialized) {
                    Intent(OppoPodsAction.ACTION_PODS_MAC_RECEIVED).apply {
                        setPackage("com.android.systemui")
                        putExtra("mac", mDevice.address)
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        mContext?.sendBroadcast(this)
                    }
                    Log.i(TAG, "ACTION_GET_PODS_MAC mac=${mDevice.address}")
                }
            }
            OppoPodsAction.ACTION_PODS_UI_INIT -> {
                markAppUiActive()
                startAncPoll()
                Log.i(TAG, "UI Init")
                changeUIConnectionState(currentConnectionState())
                if (::currentBatteryParams.isInitialized)
                    changeUIBatteryStatus(currentBatteryParams)
                changeUIWearStatus(currentWearStatus)
                changeUIAncStatus(currentAnc)
                changeUISmartAncLevel(currentSmartAncLevel)
                changeUITransparencyVocalEnhancementStatus(currentTransparencyVocalEnhancement)
                changeUISpatialAudioStatus(currentSpatialAudioMode)
                changeUIEqPreset(currentEqPreset)
                changeUIGainLevel(currentGainLevel)
                changeUILedState(currentLedEnabled)
                if (::mDevice.isInitialized && isConnected) {
                    // Our own UI still needs to know which device is connected, otherwise the
                    // popup title falls back to the app name. While yielded we only refresh the
                    // in-app snapshot and never touch the channel the official app now owns.
                    sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                        this.putExtra("address", mDevice.address)
                        this.putExtra("device_name", mDevice.name ?: cachedDeviceName)
                    }
                    if (!yielded) {
                        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                            putExtra("device_name", mDevice.name ?: cachedDeviceName)
                        }
                        // The standalone detail page always asks the earbuds themselves for fresh
                        // left/right/case battery instead of trusting Android's single generic battery.
                        queryStatus(immediateReconnect = false)
                    }
                }
            }
            OppoPodsAction.ACTION_PODS_UI_CLOSED -> {
                appUiActive = false
                appUiActiveUntilMs = 0L
                Log.i(TAG, "UI Closed")
            }
            OppoPodsAction.ACTION_ANC_SELECT -> {
                val status = intent.getIntExtra("status", 0)
                setANCMode(status)
            }
            OppoPodsAction.ACTION_REFRESH_STATUS -> {
                // A refresh request is itself proof that some surface is showing state: renew
                // the app-UI window so long-lived surfaces (e.g. the popup's own 15s loop)
                // keep receiving status broadcasts and the noise-mode poll keeps running.
                markAppUiActive()
                queryStatus(immediateReconnect = true)
            }
            OppoPodsAction.ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET -> {
                val enabled = intent.getBooleanExtra("enabled", false)
                setTransparencyVocalEnhancement(enabled)
            }
            OppoPodsAction.ACTION_SPATIAL_AUDIO_SET -> {
                val mode = intent.getIntExtra("mode", SpatialAudioMode.OFF)
                setSpatialAudioMode(mode)
            }
            OppoPodsAction.ACTION_EQ_PRESET_SET -> {
                val preset = intent.getIntExtra("preset", -1)
                if (preset in EqPreset.ALL) setEqPreset(preset)
            }
            OppoPodsAction.ACTION_GAIN_LEVEL_SET -> {
                setGainLevel(intent.getIntExtra("level", currentGainLevel))
            }
            OppoPodsAction.ACTION_LED_STATE_SET -> {
                setLedEnabled(intent.getBooleanExtra("enabled", currentLedEnabled))
            }
            OppoPodsAction.ACTION_CYCLE_ANC -> {
                cycleAnc()
            }
            OppoPodsAction.ACTION_CONFIG_CHANGED -> {
                ConfigManager.refreshFromPrefs(mPrefs)
                Log.d(TAG, "Config synced")
                evaluateYield()
                if (!currentCapabilities().adaptiveSupported && currentAnc == 4) {
                    setANCMode(2)
                }
            }
            OppoPodsAction.ACTION_OFFICIAL_APP_PRESENCE -> {
                val fromPackage = intent.getStringExtra(OppoPodsAction.EXTRA_OFFICIAL_APP_PACKAGE)
                val allowed = ConfigManager.officialAppPackages()
                if (fromPackage == null || !allowed.contains(fromPackage)) return
                ConfigManager.refreshFromPrefs(mPrefs)
                noteOfficialAppPresence()
            }
            OppoPodsAction.ACTION_RFCOMM_LOG_CONNECT -> {
                if (!RfcommLog.isEnabled()) {
                    RfcommLog.setEnabled(true, mContext)
                }
            }
            OppoPodsAction.ACTION_RFCOMM_LOG_DISCONNECT -> {
                RfcommLog.setEnabled(false)
            }
            OppoPodsAction.ACTION_RFCOMM_LOG_CLEAR -> {
                RfcommLog.clear()
            }
            OppoPodsAction.ACTION_RFCOMM_DEBUG_SEND -> {
                val hex = intent.getStringExtra("hex").orEmpty()
                sendDebugHex(hex)
            }
        }
    }

    fun currentStatusSnapshot(): StatusSnapshot {
        return StatusSnapshot(
            battery = if (::currentBatteryParams.isInitialized) currentBatteryParams else null,
            anc = currentAnc,
            transparencyVocalEnhancement = currentTransparencyVocalEnhancement,
            address = if (::mDevice.isInitialized) mDevice.address else null,
            deviceName = if (::mDevice.isInitialized) mDevice.name ?: cachedDeviceName else cachedDeviceName.takeIf { it.isNotEmpty() },
            connected = isConnected && socket != null,
            connecting = connectionJob?.isActive == true,
            reconnectPending = reconnectPending,
        )
    }

    private fun currentConnectionState(): String = when {
        isConnected && socket != null && ::currentBatteryParams.isInitialized -> "connected"
        connectionJob?.isActive == true || reconnectPending -> "connecting"
        isConnected && socket != null -> "connecting"
        else -> "disconnected"
    }

    private fun changeUIConnectionState(state: String) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED) {
            if (::mDevice.isInitialized) {
                putExtra("address", mDevice.address)
                putExtra("device_name", mDevice.name ?: cachedDeviceName)
            }
            putExtra("state", state)
        }
    }

    private fun sendAppStatusBroadcast(action: String, fill: Intent.() -> Unit = {}) {
        val ctx = mContext ?: return
        if (!isAppUiActive()) return
        Intent(action).apply {
            fill()
            this.`package` = BuildConfig.APPLICATION_ID
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            ctx.sendBroadcast(this)
        }
    }

    private fun isAppUiActive(): Boolean {
        if (!appUiActive) return false
        if (SystemClock.elapsedRealtime() <= appUiActiveUntilMs) return true
        appUiActive = false
        appUiActiveUntilMs = 0L
        Log.d(TAG, "app UI active timeout, stop app status broadcasts")
        return false
    }

    private fun markAppUiActive() {
        appUiActive = true
        appUiActiveUntilMs = SystemClock.elapsedRealtime() + APP_UI_ACTIVE_TIMEOUT_MS
    }


    fun miuiRefreshPayload(battery: BatteryParams?, anc: Int, transparencyVocalEnhancement: Boolean = false): String {
        val values = MutableList(16) { "" }
        values[0] = miuiBatteryValue(battery?.left)
        values[1] = miuiBatteryValue(battery?.right)
        values[2] = miuiBatteryValue(battery?.case)
        values[7] = miuiAncLevel(anc, transparencyVocalEnhancement)
        values[8] = "true"
        values[11] = "00"
        values[13] = "00"
        values[14] = "00"
        return values.joinToString(",")
    }

    private fun miuiBatteryValue(params: PodParams?): String {
        if (params?.isConnected != true) return "255"
        val value = params.battery.coerceIn(0, 100)
        return (if (params.isCharging) value or 128 else value).toString()
    }

    private fun miuiAncLevel(anc: Int, transparencyVocalEnhancement: Boolean): String {
        // Reuse HyperOS' existing ANC level UI as PUDDING's three modes.
        return when (anc) {
            2 -> "0100" // Basic ANC
            4 -> "0101" // Adaptive ANC
            5 -> "0102" // Wind-noise reduction
            3 -> "0200" // Transparency
            else -> "0000" // Off
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun handleBatteryChanged(result: BatteryParser.BatteryResult) {
        // GAIA battery notifications/responses may contain a subset of components. Preserve
        // the last value for components not present in this packet instead of turning them
        // into 0/disconnected. This keeps the three-battery UI stable.
        result.left?.let {
            lastKnownLeftBattery = it.level
            lastKnownLeftCharging = it.isCharging
        }
        result.right?.let {
            lastKnownRightBattery = it.level
            lastKnownRightCharging = it.isCharging
        }
        result.case?.let {
            lastKnownCaseBattery = it.level
            lastKnownCaseCharging = it.isCharging
        }

        val previous = if (::currentBatteryParams.isInitialized) currentBatteryParams else BatteryParams()
        val left = result.left?.let { PodParams(it.level, it.isCharging, true, 0) }
            ?: previous.left?.takeIf { it.isConnected }
            ?: PodParams(lastKnownLeftBattery, lastKnownLeftCharging, lastKnownLeftBattery > 0, 0)
        val right = result.right?.let { PodParams(it.level, it.isCharging, true, 0) }
            ?: previous.right?.takeIf { it.isConnected }
            ?: PodParams(lastKnownRightBattery, lastKnownRightCharging, lastKnownRightBattery > 0, 0)
        val case = result.case?.let { PodParams(it.level, it.isCharging, true, 0) }
            ?: previous.case?.takeIf { it.isConnected }
            ?: PodParams(lastKnownCaseBattery, lastKnownCaseCharging, lastKnownCaseBattery > 0, 0)

        Log.i(
            TAG,
            "PUDDING battery snapshot left=${left.battery}/${left.isConnected} " +
                    "right=${right.battery}/${right.isConnected} case=${case.battery}/${case.isConnected}"
        )

        val shouldShowToast = !mShowedConnectedToast
        if (shouldShowToast) {
            // Wait until at least one connected ear has valid battery data
            val hasValidData = (left.isConnected && left.battery > 0) ||
                    (right.isConnected && right.battery > 0)
            if (!hasValidData) return
        }

        val batteryParams = BatteryParams(left, right, case)
        currentBatteryParams = batteryParams

        if (shouldShowToast) {
            changeUIConnectionState("connected")
            sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                this.putExtra("address", mDevice.address)
                this.putExtra("device_name", mDevice.name ?: cachedDeviceName)
            }
            sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                putExtra("device_name", mDevice.name ?: cachedDeviceName)
            }
            // HyperOriG emits a Focus Island once for every real connection instead of
            // depending on a hidden/legacy island preference. Keep that behaviour for PUDDING.
            MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(mContext!!, batteryParams, mDevice)
            mShowedConnectedToast = true
        }
        MiuiStrongToastUtil.showPodsNotificationByMiuiBt(mContext!!, batteryParams, mDevice)
        changeUIBatteryStatus(batteryParams)

        lastTempBatt = if (left.isConnected && right.isConnected)
            minOf(left.battery, right.battery)
        else if (left.isConnected)
            left.battery
        else if (right.isConnected)
            right.battery
        else SystemApisUtils.BATTERY_LEVEL_UNKNOWN

        setRegularBatteryLevel(lastTempBatt)
    }

    private val routeCallback = object : MediaRouter2.RouteCallback() {
        override fun onRoutesUpdated(routes: List<MediaRoute2Info>) {
            Log.v(TAG, "routes updated: $routes")
            this@DeviceSession.routes = routes
        }
    }

    private fun startRoutesScan() {
        if (routeScanStarted) return
        val executor = Executor { p0 ->
            CoroutineScope(Dispatchers.IO).launch { p0?.run() }
        }
        val preferredFeature = listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO, MediaRoute2Info.FEATURE_LIVE_VIDEO)
        mediaRouter.registerRouteCallback(executor, routeCallback, RouteDiscoveryPreference.Builder(preferredFeature, true).build())
        scanToken = mediaRouter.requestScan(MediaRouter2.ScanRequest.Builder().build())
        routeScanStarted = true
    }

    private fun stopRoutesScan() {
        scanToken?.let { mediaRouter.cancelScanRequest(it) }
        if (routeScanStarted) {
            mediaRouter.unregisterRouteCallback(routeCallback)
            routeScanStarted = false
        }
    }

    private fun createRfcommSocket(device: BluetoothDevice): BluetoothSocket {
        return device.createRfcommSocketToServiceRecord(UUID.fromString(commandTable().sppUuid))
    }

    fun connectPod(context: Context, device: BluetoothDevice, prefs: SharedPreferences, appRequested: Boolean = false) {
        connectionJob?.cancel()
        reconnectJob?.cancel()
        readerJob?.cancel()
        closeSocketOnly()
        mContext = context
        mDevice = device
        mPrefs = prefs
        cachedDeviceName = device.name ?: ""
        // Never carry a partial battery snapshot from a previous RFCOMM session into a
        // fresh PUDDING connection. The official app polls GAIA battery repeatedly and
        // receives LEFT(1), RIGHT(2) and CASE(3) independently.
        currentBatteryParams = BatteryParams()
        lastKnownLeftBattery = 0
        lastKnownRightBattery = 0
        lastKnownCaseBattery = 0
        lastKnownLeftCharging = false
        lastKnownRightCharging = false
        lastKnownCaseCharging = false
        if (appRequested) {
            markAppUiActive()
        }
        ConfigManager.refreshFromPrefs(mPrefs)
        Log.d(TAG, "Adaptive support initial: ${currentCapabilities().adaptiveSupported}")
        Log.d(TAG, "RFCOMM UUID: ${commandTable().sppUuid}")

        if (!receiverRegistered) {
            context.registerReceiver(broadcastReceiver, IntentFilter().apply {
                this.addAction(OppoPodsAction.ACTION_ANC_SELECT)
                this.addAction(OppoPodsAction.ACTION_PODS_UI_INIT)
                this.addAction(OppoPodsAction.ACTION_PODS_UI_CLOSED)
                this.addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
                this.addAction(OppoPodsAction.ACTION_GET_PODS_MAC)
                this.addAction(OppoPodsAction.ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET)
                this.addAction(OppoPodsAction.ACTION_SPATIAL_AUDIO_SET)
                this.addAction(OppoPodsAction.ACTION_EQ_PRESET_SET)
                this.addAction(OppoPodsAction.ACTION_GAIN_LEVEL_SET)
                this.addAction(OppoPodsAction.ACTION_LED_STATE_SET)
                this.addAction(OppoPodsAction.ACTION_CYCLE_ANC)
                this.addAction(OppoPodsAction.ACTION_CONFIG_CHANGED)
                this.addAction(OppoPodsAction.ACTION_OFFICIAL_APP_PRESENCE)
                this.addAction(OppoPodsAction.ACTION_RFCOMM_LOG_CONNECT)
                this.addAction(OppoPodsAction.ACTION_RFCOMM_LOG_DISCONNECT)
                this.addAction(OppoPodsAction.ACTION_RFCOMM_LOG_CLEAR)
                this.addAction(OppoPodsAction.ACTION_RFCOMM_DEBUG_SEND)
            }, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
        }

        MediaControl.mContext = mContext
        mediaRouter = MediaRouter2.getInstance(mContext!!)
        startRoutesScan()

        isConnected = true
        changeUIConnectionState("connecting")

        // Decide whether the official app currently owns the channel before opening RFCOMM.
        evaluateYield()
        connectRfcomm(initialDelayMs = 500L)

    }

    private fun sendExternalPodsStatusBroadcast(action: String, fill: Intent.() -> Unit = {}) {
        val ctx = mContext ?: return
        listOf("com.milink.service", "com.xiaomi.bluetooth", "com.android.settings").forEach { targetPackage ->
            Intent(action).apply {
                if (::mDevice.isInitialized) {
                    putExtra("address", mDevice.address)
                    putExtra("device_name", mDevice.name ?: cachedDeviceName)
                }
                fill()
                setPackage(targetPackage)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                ctx.sendBroadcast(this)
            }
        }
    }

    /**
     * Re-evaluate the official-app lease and enter/leave the yielded state.
     * Idempotent: safe to call from heartbeats, config changes, connect and the watch loop.
     */
    fun evaluateYield() {
        val enabled = ConfigManager.current().yieldToOfficialApp
        val prefsLastSeen = if (::mPrefs.isInitialized)
            mPrefs.getLong(YieldPolicy.PREF_KEY_OFFICIAL_APP_LAST_SEEN_MS, 0L) else 0L
        // PRESENCE broadcasts are delivered in-process and are authoritative (no
        // dependency on RemotePreferences writes from another process propagating).
        // Fall back to the prefs lease only when no broadcast has been received yet.
        val lastSeen = if (officialAppLastSeenMs > 0L) officialAppLastSeenMs else prefsLastSeen
        val officialAlive = YieldPolicy.officialAlive(SystemClock.elapsedRealtime(), lastSeen)
        val headsetConnected = isConnected && ::mDevice.isInitialized
        val shouldYield = YieldPolicy.shouldYield(enabled, officialAlive, headsetConnected)
        if (shouldYield && !yielded) {
            yieldControl()
        } else if (!shouldYield && yielded) {
            resumeControl()
        }
    }

    /** Record that the official app is alive in this process, then re-evaluate. */
    fun noteOfficialAppPresence() {
        officialAppLastSeenMs = SystemClock.elapsedRealtime()
        evaluateYield()
    }

    private fun yieldControl() {
        yielded = true
        connectionJob?.cancel()
        reconnectJob?.cancel()
        readerJob?.cancel()
        reconnectPending = false
        closeSocketOnly()
        Log.i(TAG, "yield control: official app is alive")
        // Drop the MIUI bluetooth card: it would otherwise keep showing the last battery/ANC
        // snapshot we pushed, which is stale once the official app owns the channel.
        runCatching {
            val ctx = mContext
            if (ctx != null && ::mDevice.isInitialized) {
                cancelPodsNotificationByMiuiBt(ctx, mDevice)
            }
        }.onFailure { Log.w(TAG, "cancel pods notification on yield skipped", it) }
        sendControlStateBroadcast(OppoPodsAction.ACTION_CONTROL_YIELDED)
        yieldWatchJob?.cancel()
        yieldWatchJob = CoroutineScope(Dispatchers.IO).launch {
            while (yielded) {
                delay(YieldPolicy.YIELD_CHECK_INTERVAL_MS)
                evaluateYield()
            }
        }
    }

    private fun resumeControl() {
        yielded = false
        yieldWatchJob?.cancel()
        yieldWatchJob = null
        Log.i(TAG, "resume control: official app lease expired")
        sendControlStateBroadcast(OppoPodsAction.ACTION_CONTROL_RESUMED)
        if (isConnected && ::mDevice.isInitialized) {
            connectRfcomm(initialDelayMs = 800L)
        }
    }

    /** Notify every L2 status consumer, including the bluetooth process itself. */
    private fun sendControlStateBroadcast(action: String) {
        val ctx = mContext ?: return
        listOf(
            "com.milink.service",
            "com.xiaomi.bluetooth",
            "com.android.settings",
            "com.android.bluetooth",
        ).forEach { targetPackage ->
            Intent(action).apply {
                if (::mDevice.isInitialized) {
                    putExtra("address", mDevice.address)
                    putExtra("device_name", mDevice.name ?: cachedDeviceName)
                }
                setPackage(targetPackage)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                ctx.sendBroadcast(this)
            }
        }
    }

    private fun Intent.putBatteryExtras(status: BatteryParams) {
        putExtra("left_battery", status.left?.battery ?: 0)
        putExtra("left_charging", status.left?.isCharging == true)
        putExtra("left_connected", status.left?.isConnected == true)
        putExtra("right_battery", status.right?.battery ?: 0)
        putExtra("right_charging", status.right?.isCharging == true)
        putExtra("right_connected", status.right?.isConnected == true)
        putExtra("case_battery", status.case?.battery ?: 0)
        putExtra("case_charging", status.case?.isCharging == true)
        putExtra("case_connected", status.case?.isConnected == true)
    }

    private fun Intent.putWearStatusExtras(status: WearStatus) {
        putExtra("left_wear_status", status.left?.value ?: -1)
        putExtra("right_wear_status", status.right?.value ?: -1)
        putExtra("case_wear_status", status.case?.value ?: -1)
    }

    private fun mergeWearStatus(current: WearStatus, update: WearStatus): WearStatus {
        return WearStatus(
            left = update.left ?: current.left,
            right = update.right ?: current.right,
            case = update.case ?: current.case
        )
    }

    private fun showIslandForWearStatusChange(previous: WearStatus, current: WearStatus) {
        if (!::currentBatteryParams.isInitialized) return
        val changedTimings = setOfNotNull(
            islandShowTimingForChange(previous.left, current.left),
            islandShowTimingForChange(previous.right, current.right),
            islandShowTimingForChange(previous.case, current.case),
        )
        if (changedTimings.any { shouldShowIsland(it) }) {
            MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(mContext ?: return, currentBatteryParams)
        }
    }

    private fun shouldShowIsland(timing: Int): Boolean {
        return ConfigManager.islandMode() == ConfigManager.ISLAND_MODE_MODULE &&
                timing in ConfigManager.islandShowTimings()
    }

    private fun islandShowTimingForChange(previous: WearState?, current: WearState?): Int? {
        if (previous == current) return null
        return when (current) {
            WearState.WEARING -> ConfigManager.ISLAND_SHOW_TIMING_WEARING
            WearState.REMOVED -> ConfigManager.ISLAND_SHOW_TIMING_REMOVED
            WearState.IN_CASE -> ConfigManager.ISLAND_SHOW_TIMING_IN_CASE
            else -> null
        }
    }

    private fun connectRfcomm(initialDelayMs: Long = 0L) {
        connectionJob?.cancel()
        connectionJob = CoroutineScope(Dispatchers.IO).launch {
            if (initialDelayMs > 0) delay(initialDelayMs)
            if (!isConnected || !::mDevice.isInitialized) return@launch
            if (yielded) {
                Log.d(TAG, "RFCOMM connect skipped while yielded")
                return@launch
            }
            closeSocketOnly()
            try {
                val newSocket = createRfcommSocket(mDevice)
                newSocket.connect()
                // A yield may have landed while connect() was blocking (cancellation
                // does not interrupt the blocking call). Never keep a socket the
                // official app is trying to own, and never start a reader after
                // closeSocketOnly() ran in yieldControl().
                if (yielded) {
                    runCatching { newSocket.close() }
                    Log.d(TAG, "RFCOMM socket released: yielded during connect")
                    return@launch
                }
                socket = newSocket
                reconnectAttempts.set(0)
                reconnectPending = false
                Log.d(TAG, "RFCOMM connected! uuid=${commandTable().sppUuid}")
                RfcommLog.i(mContext, TAG, "connected uuid=${commandTable().sppUuid}")
                changeUIConnectionState("connecting")

                startPacketReader(newSocket.inputStream)

                delay(180)
                // Match the official MOONDROP app: negotiate GAIA with a v1 API-version
                // request, then use v4 for PUDDING commands.
                sendPacketSafe(GaiaTransport.v1(commandTable().queryGaiaVersion))
                delay(180)
                sendPacketSafe(GaiaTransport.v4(commandTable().queryNoiseMode))
                delay(100)
                sendPacketSafe(GaiaTransport.v4(commandTable().queryGainLevel))
                delay(100)
                sendPacketSafe(GaiaTransport.v4(commandTable().queryLedState))
                delay(100)
                sendPacketSafe(GaiaTransport.v4(commandTable().querySupportedBatteries))
                delay(80)
                sendPacketSafe(GaiaTransport.v4(commandTable().queryBatteryLevels))

                // The handshake queries above already reflect current state; restart the poll
                // here so a reconnect mid-UI keeps the open surface updating.
                if (isAppUiActive()) startAncPoll()

                changeUIConnectionState("connected")
                sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                    putExtra("address", mDevice.address)
                    putExtra("device_name", mDevice.name ?: cachedDeviceName)
                }
                sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                    putExtra("device_name", mDevice.name ?: cachedDeviceName)
                }
                mShowedConnectedToast = true
            } catch (e: IOException) {
                Log.e(TAG, "RFCOMM connect failed", e)
                changeUIConnectionState("error")
                scheduleReconnect("connect failed")
            }
        }
    }

    // No periodic RFCOMM polling. ANC changes are consumed from the earbud's GAIA
    // notifications/echoes. Fresh status is queried only on connect, when a UI/system page
    // explicitly opens/refreshes, or when a real ANC change triggers a battery refresh.

    private fun scheduleReconnect(reason: String, immediate: Boolean = false) {
        if (yielded) return
        if (!isConnected || !::mDevice.isInitialized || mContext == null) return
        RfcommLog.w(mContext, TAG, "schedule reconnect reason=$reason immediate=$immediate")
        closeSocketOnly()
        reconnectPending = true
        if (immediate) {
            if (connectionJob?.isActive == true) {
                Log.d(TAG, "immediate RFCOMM reconnect skipped: connecting reason=$reason")
                return
            }
            Log.d(TAG, "immediate RFCOMM reconnect reason=$reason")
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectPending = false
            connectRfcomm()
            return
        }
        if (reconnectJob?.isActive == true) {
            Log.d(TAG, "RFCOMM reconnect already scheduled reason=$reason")
            return
        }
        val attempt = reconnectAttempts.incrementAndGet()
        Log.d(TAG, "schedule RFCOMM reconnect reason=$reason attempt=$attempt delay=${AUTO_RECONNECT_DELAY_MS}ms")
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            delay(AUTO_RECONNECT_DELAY_MS)
            reconnectJob = null
            reconnectPending = false
            connectRfcomm()
        }
    }

    private fun reconnectNowForRequest(reason: String) {
        if (socket != null && !reconnectPending) return
        scheduleReconnect(reason, immediate = true)
    }

    private fun closeSocketOnly() {
        readerJob?.cancel()
        readerJob = null
        ancPollJob?.cancel()
        ancPollJob = null
        decoder.reset()
        try {
            socket?.close()
        } catch (_: IOException) {}
        socket = null
    }

    private fun startPacketReader(inputStream: InputStream) {
        readerJob?.cancel()
        readerJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(1024)
            try {
                while (isConnected) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        val chunk = buffer.copyOfRange(0, bytesRead)
                        RfcommLog.d(mContext, "RFCOMM/RX", chunk.toHexString(HexFormat.UpperCase))
                        decoder.feed(chunk).forEach(::handleGaiaPayload)
                    } else if (bytesRead == -1) {
                        Log.d(TAG, "RFCOMM stream ended")
                        RfcommLog.w(mContext, TAG, "stream ended")
                        scheduleReconnect("stream ended")
                        break
                    }
                }
            } catch (e: IOException) {
                if (isConnected) {
                    Log.e(TAG, "RFCOMM read error", e)
                    RfcommLog.e(mContext, TAG, "read error: ${e.message.orEmpty()}")
                    scheduleReconnect("read error")
                }
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun handleGaiaPayload(payload: ByteArray) {
        Log.v(TAG, "GAIA payload: ${payload.toHexString(HexFormat.UpperCase)}")
        when (val report = commandTable().parse(payload)) {
            is GaiaReport.AncMode -> {
                val pending = pendingAnc
                if (pending != null && !report.isNotification &&
                    report.uiStatus != pending &&
                    SystemClock.elapsedRealtime() < pendingAncUntilMs
                ) {
                    Log.w(TAG, "ignoring stale noise mode during SET confirm window: ui=${report.uiStatus} pending=$pending")
                    return
                }
                pendingAnc = null
                pendingAncUntilMs = 0L
                val previousAnc = currentAnc
                currentAnc = report.uiStatus
                changeUIAncStatus(currentAnc)
                Log.i(TAG, "noise mode: ui=${report.uiStatus} wire=${report.wire} previous=$previousAnc")
                // A mode change made outside HyperDrop (e.g. an earbud button press, surfacing
                // through the poll) refreshes battery immediately. App-originated SETs update
                // currentAnc first, so their echo does not trigger an extra battery query.
                if (previousAnc != currentAnc) queryBattery()
            }
            is GaiaReport.Battery -> {
                val left = report.left?.let { BatteryParser.BatteryInfo(it, false) }
                val right = report.right?.let { BatteryParser.BatteryInfo(it, false) }
                val case = report.case?.let { BatteryParser.BatteryInfo(it, false) }
                if (left != null || right != null || case != null) {
                    handleBatteryChanged(BatteryParser.BatteryResult(left, right, case))
                    Log.i(TAG, "battery: left=${report.left} right=${report.right} case=${report.case}")
                }
            }
            is GaiaReport.GainLevel -> {
                currentGainLevel = report.uiValue
                changeUIGainLevel(currentGainLevel)
                Log.i(TAG, "gain level: ui=${report.uiValue} wire=${report.wire}")
            }
            is GaiaReport.LedEnabled -> {
                currentLedEnabled = report.enabled
                changeUILedState(currentLedEnabled)
                Log.i(TAG, "LED enabled: $currentLedEnabled")
            }
            GaiaReport.GaiaNegotiation -> {
                Log.d(TAG, "GAIA negotiation response: ${payload.toHexString(HexFormat.UpperCase)}")
            }
            GaiaReport.Unknown -> {
                Log.d(TAG, "Unhandled GAIA payload: ${payload.toHexString(HexFormat.UpperCase)}")
            }
        }
    }

    fun disconnectedPod(context: Context, device: BluetoothDevice) {
        isConnected = false
        yielded = false
        yieldWatchJob?.cancel()
        yieldWatchJob = null
        connectionJob?.cancel()
        reconnectJob?.cancel()
        readerJob?.cancel()
        reconnectAttempts.set(0)
        reconnectPending = false

        closeSocketOnly()

        mContext?.let {
            stopRoutesScan()
            cancelPodsNotificationByMiuiBt(context, device)
            sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_DISCONNECTED) {
                putExtra("address", device.address)
            }
            if (receiverRegistered) {
                it.unregisterReceiver(broadcastReceiver)
                receiverRegistered = false
            }
        }

        mShowedConnectedToast = false
        currentWearStatus = WearStatus()
        currentAnc = 1
        pendingAnc = null
        pendingAncUntilMs = 0L
        currentSmartAncLevel = -1
        currentTransparencyVocalEnhancement = false
        currentSpatialAudioMode = SpatialAudioMode.OFF
        currentEqPreset = -1
        currentGainLevel = DEFAULT_GAIN_LEVEL
        currentLedEnabled = true
        lastKnownLeftBattery = 0
        lastKnownRightBattery = 0
        lastKnownCaseBattery = 0
        lastKnownLeftCharging = false
        lastKnownRightCharging = false
        lastKnownCaseCharging = false
        currentBatteryParams = BatteryParams()
        changeUIConnectionState("disconnected")
        cachedDeviceName = ""
        mContext = null
        MediaControl.mContext = null
    }

    private fun sendPacketSafe(packet: ByteArray, requestReason: String? = null) {
        if (yielded) {
            Log.d(TAG, "dropped GAIA write while yielded")
            return
        }
        if (requestReason != null) reconnectNowForRequest(requestReason)
        try {
            val currentSocket = socket ?: run {
                RfcommLog.w(mContext, "RFCOMM/TX", "socket null: ${packet.toHexString(HexFormat.UpperCase)}")
                scheduleReconnect("socket null before send", immediate = requestReason != null)
                return
            }
            RfcommLog.d(mContext, "RFCOMM/TX", packet.toHexString(HexFormat.UpperCase))
            currentSocket.outputStream.write(packet)
            currentSocket.outputStream.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Send packet failed", e)
            RfcommLog.e(mContext, TAG, "send failed: ${e.message.orEmpty()}")
            scheduleReconnect("send error", immediate = requestReason != null)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun sendDebugHex(hex: String) {
        val normalized = hex.filterNot { it.isWhitespace() || it == ':' || it == '-' }
        if (normalized.isEmpty() || normalized.length % 2 != 0 || !normalized.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            RfcommLog.e(mContext, "RFCOMM/DEBUG", "invalid HEX: $hex")
            return
        }
        val packet = normalized.hexToByteArray()
        RfcommLog.i(mContext, "RFCOMM/DEBUG", "send ${packet.size} bytes")
        sendPacketSafe(packet, "rfcomm debug send")
    }

    fun setTransparencyVocalEnhancement(enabled: Boolean) { Log.d(TAG, "PUDDING: vocal enhancement unsupported; ignored=$enabled") }

    fun setSpatialAudioMode(mode: Int) { Log.d(TAG, "PUDDING: spatial audio unsupported; ignored=$mode") }

    fun setEqPreset(presetId: Int) { Log.d(TAG, "PUDDING: OPPO EQ preset protocol removed; ignored=$presetId") }

    fun setGainLevel(level: Int) {
        val packet = commandTable().setGainLevel(level) ?: return
        currentGainLevel = level
        changeUIGainLevel(currentGainLevel)
        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "set gain: ui=$level wire=${commandTable().gainUiToWire(level)}")
            sendPacketSafe(GaiaTransport.v4(packet), "gain")
            delay(140)
            sendPacketSafe(GaiaTransport.v4(commandTable().queryGainLevel))
        }
    }

    fun setLedEnabled(enabled: Boolean) {
        currentLedEnabled = enabled
        changeUILedState(enabled)
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(GaiaTransport.v4(commandTable().setLedEnabled(enabled)), "LED")
            delay(140)
            sendPacketSafe(GaiaTransport.v4(commandTable().queryLedState))
        }
    }

    fun cycleAnc() {
        val cycle = commandTable().ancCycle
        if (cycle.isEmpty()) return
        val currentIndex = cycle.indexOf(currentAnc)
        val next = cycle[(currentIndex + 1).floorMod(cycle.size)]
        setANCMode(next)
    }

    private fun deviceName(): String =
        if (::mDevice.isInitialized) mDevice.name ?: cachedDeviceName else cachedDeviceName

    private fun commandTable(): GaiaCommandTable =
        DeviceRegistry.profileFor(deviceName()).commandTable

    private fun currentCapabilities(): cythen.hyperdrop.device.DeviceCapabilities {
        return cythen.hyperdrop.device.DeviceRegistry.detectDeviceCapabilities(
            deviceName = deviceName(),
            adaptiveOverride = ConfigManager.adaptiveCapabilityOverride(),
            spatialAudioOverride = ConfigManager.spatialAudioCapabilityOverride(),
            spatialSoundSwitchOverride = ConfigManager.spatialSoundSwitchCapabilityOverride(),
            ancImplementationOverride = ConfigManager.ancImplementationCapabilityOverride(),
        )
    }

    private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor

    fun setANCMode(mode: Int) {
        val packet = commandTable().setNoiseMode(mode) ?: return
        Log.d(TAG, "set noise mode: ui=$mode wire=${commandTable().ancUiToWire(mode)}")
        pendingAnc = mode
        pendingAncUntilMs = SystemClock.elapsedRealtime() + ANC_CONFIRM_WINDOW_MS
        currentAnc = mode
        changeUIAncStatus(mode)
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(GaiaTransport.v4(packet), "noise control")
            // Robin never echoes SETs and answers a quick GET with its pre-SET state, so the
            // confirmation query is delayed until after the confirm window.
            delay(ANC_CONFIRM_QUERY_DELAY_MS)
            sendPacketSafe(GaiaTransport.v4(commandTable().queryNoiseMode))
        }
    }

    fun queryBattery() {
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(
                GaiaTransport.v4(commandTable().querySupportedBatteries),
                "battery query"
            )
            delay(80)
            sendPacketSafe(GaiaTransport.v4(commandTable().queryBatteryLevels))
        }
    }

    fun queryStatus(immediateReconnect: Boolean = true) {
        CoroutineScope(Dispatchers.IO).launch {
            sendStatusQueryPackets(immediateReconnect)
        }
    }

    /**
     * Poll the noise mode while an ANC surface is open (in-app page, popup, HyperOS settings
     * page). Robin's firmware never pushes mode changes, so an earbud button press is silent
     * on the wire and passive listening is impossible — the official app polls the same way.
     * The loop ends with the app-UI window (UI closed, or its timeout), keeping idle SPP
     * traffic at zero when nothing is watching.
     */
    private fun startAncPoll() {
        ancPollJob?.cancel()
        ancPollJob = CoroutineScope(Dispatchers.IO).launch {
            while (isConnected && !yielded && isAppUiActive()) {
                delay(ANC_UI_POLL_INTERVAL_MS)
                if (!isConnected || yielded || !isAppUiActive()) break
                sendPacketSafe(GaiaTransport.v4(commandTable().queryNoiseMode))
            }
        }
    }

    private suspend fun sendStatusQueryPackets(immediateReconnect: Boolean = true) {
        val reason = if (immediateReconnect) "status query" else null
        sendPacketSafe(GaiaTransport.v4(commandTable().queryNoiseMode), reason)
        delay(100)
        sendPacketSafe(GaiaTransport.v4(commandTable().queryGainLevel))
        delay(100)
        sendPacketSafe(GaiaTransport.v4(commandTable().queryLedState))
        delay(100)
        sendPacketSafe(GaiaTransport.v4(commandTable().querySupportedBatteries))
        delay(80)
        sendPacketSafe(GaiaTransport.v4(commandTable().queryBatteryLevels))
    }

    fun disconnectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter

        MediaControl.sendPause()

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    try {
                        val method = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                    }
                }
            }
            override fun onServiceDisconnected(profile: Int) { }
        }, BluetoothProfile.HEADSET)

        CoroutineScope(Dispatchers.Default).launch {
            delay(500)
            for (route in routes) {
                if (route.type == MediaRoute2Info.TYPE_BUILTIN_SPEAKER) {
                    Log.d(TAG, "found speaker route $route")
                    mediaRouter.transferTo(route)
                }
            }
        }

        setRegularBatteryLevel(lastTempBatt)
    }

    fun connectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    try {
                        val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                    }
                }
            }
            override fun onServiceDisconnected(profile: Int) { }
        }, BluetoothProfile.HEADSET)

        for (route in routes) {
            if (route.type == MediaRoute2Info.TYPE_BLUETOOTH_A2DP && route.name == device!!.name) {
                Log.d(TAG, "found bt route $route")
                mediaRouter.transferTo(route)
            }
        }

        val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
        statusBarManager.setIconVisibility("wireless_headset", true)
        setRegularBatteryLevel(lastTempBatt)
    }

    fun setRegularBatteryLevel(level: Int) {
        try {
            val service = getObjectField(mContext, "mAdapterService")
            callMethod(service, "setBatteryLevel", mDevice, level, false)
        } catch (e: Exception) {
            Log.e(TAG, "setRegularBatteryLevel failed", e)
        }
    }

    private fun getObjectField(instance: Any?, fieldName: String): Any? {
        if (instance == null) return null
        var cls: Class<*>? = instance.javaClass
        while (cls != null) {
            runCatching {
                return cls.getDeclaredField(fieldName).apply { isAccessible = true }.get(instance)
            }
            cls = cls.superclass
        }
        throw NoSuchFieldException(fieldName)
    }

    private fun callMethod(instance: Any?, methodName: String, vararg args: Any?): Any? {
        if (instance == null) return null
        var cls: Class<*>? = instance.javaClass
        while (cls != null) {
            cls.declaredMethods.firstOrNull { it.name == methodName && it.parameterTypes.size == args.size }?.let {
                it.isAccessible = true
                return it.invoke(instance, *args)
            }
            cls = cls.superclass
        }
        throw NoSuchMethodException(methodName)
    }
}
