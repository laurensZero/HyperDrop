package cythen.hyperdrop.utils

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import com.xzakota.hyper.notification.focus.FocusNotification
import cythen.hyperdrop.BuildConfig
import cythen.hyperdrop.R
import cythen.hyperdrop.hook.Log
import cythen.hyperdrop.utils.miuiStrongToast.data.BatteryParams

/**
 * HyperOS Focus Island sender.
 *
 * Keep this deliberately close to the working HyperOriG/OppoPods implementation:
 * - notification is created from the com.xiaomi.bluetooth process;
 * - images are decoded directly from the module APK, not through a content URI;
 * - a fresh channel id avoids a stale/disabled channel left by older test builds;
 * - the island notification is short lived and has no normal notification card.
 */
@SuppressLint("WrongConstant")
object FocusIslandUtil {
    private const val TAG = "HyperDrop-FocusIsland"
    private const val CHANNEL_ID = "hyperdrop_focus_island_v2"
    private const val CHANNEL_NAME = "HyperDrop Focus Island"
    private const val NOTIFICATION_ID = 10086
    private const val DISMISS_DELAY_MS = 4_000L

    @Volatile
    private var lastShownAt = 0L

    fun showBatteryIsland(
        context: Context,
        @Suppress("UNUSED_PARAMETER") prefs: SharedPreferences,
        batteryParams: BatteryParams,
        @Suppress("UNUSED_PARAMETER") address: String,
    ): Boolean {
        try {
            val leftConnected = batteryParams.left?.isConnected == true
            val rightConnected = batteryParams.right?.isConnected == true
            if (!leftConnected && !rightConnected) {
                android.util.Log.d(TAG, "skip island: no connected ear")
                return false
            }

            // Avoid duplicate islands when both the connection event and notification refresh
            // deliver the same battery snapshot almost at the same time.
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastShownAt < 700L) return true

            val leftBitmap = PodImageLoader.loadIslandLeftBitmap(context, prefs, address)
            val rightBitmap = PodImageLoader.loadIslandRightBitmap(context, prefs, address)
            if (leftBitmap == null || rightBitmap == null) {
                android.util.Log.e(TAG, "decode island icons failed left=$leftBitmap right=$rightBitmap")
                return false
            }

            // Brand the island with the connected model instead of a hardcoded name.
            val deviceLabel = PodImageLoader.resolveDeviceName(context, prefs, address)
                ?: "MOONDROP Pudding"

            val leftText = if (leftConnected) "${batteryParams.left!!.battery}" else "-"
            val rightText = if (rightConnected) "${batteryParams.right!!.battery}" else "-"
            val leftIcon = Icon.createWithBitmap(leftBitmap)
            val rightIcon = Icon.createWithBitmap(rightBitmap)

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setAllowBubbles(true)
                }
            )

            val contentParts = mutableListOf<String>()
            if (leftConnected) contentParts.add("L: ${batteryParams.left!!.battery}%")
            if (rightConnected) contentParts.add("R: ${batteryParams.right!!.battery}%")
            val contentText = contentParts.joinToString("  ")

            val extras = FocusNotification.buildV3 {
                val picLeft = createPicture("key_pic_left", leftIcon)
                val picRight = createPicture("key_pic_right", rightIcon)

                enableFloat = true
                ticker = deviceLabel
                tickerPic = picLeft
                isShowNotification = false

                island {
                    islandProperty = 1
                    bigIslandArea {
                        imageTextInfoLeft {
                            type = 1
                            picInfo {
                                type = 1
                                pic = picLeft
                            }
                            textInfo {
                                title = leftText
                                content = "%"
                            }
                        }
                        imageTextInfoRight {
                            type = 2
                            picInfo {
                                type = 1
                                pic = picRight
                            }
                            textInfo {
                                title = rightText
                                content = "%"
                            }
                        }
                    }
                    shareData {
                        title = deviceLabel
                        content = contentText
                        shareContent = contentText
                    }
                }
            } ?: return false

            val popupIntent = PendingIntent.getActivity(
                context,
                35001,
                Intent("cythen.hyperdrop.action.show_pods_ui").apply {
                    setClassName(BuildConfig.APPLICATION_ID, "cythen.hyperdrop.PopupActivity")
                    putExtra("bluetoothaddress", address)
                    setIdentifier("HyperDropFocus:$address")
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(deviceLabel)
                .setContentText(contentText)
                .setTicker(deviceLabel)
                .setContentIntent(popupIntent)
                .setOnlyAlertOnce(true)
                .addExtras(extras)
                .build()

            nm.notify(NOTIFICATION_ID, notification)
            lastShownAt = now
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { nm.cancel(NOTIFICATION_ID) }
            }, DISMISS_DELAY_MS)

            android.util.Log.d(TAG, "Focus Island shown L=$leftText R=$rightText package=${context.packageName}")
            Log.d(TAG, "Focus Island shown: L=$leftText% R=$rightText%")
            return true
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "Failed to show Focus Island", e)
            Log.e(TAG, "Failed to show Focus Island", e)
            return false
        }
    }
}
