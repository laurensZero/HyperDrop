package cythen.hyperdrop.utils

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import cythen.hyperdrop.config.PodImagePrefs
import cythen.hyperdrop.config.PodImageResource
import cythen.hyperdrop.config.imageUri
import cythen.hyperdrop.device.DeviceRegistry

object PodImageLoader {
    private const val TAG = "HyperDrop-PodImage"
    private const val MODULE_PACKAGE = "cythen.hyperdrop"

    fun loadBitmap(
        context: Context,
        prefs: SharedPreferences,
        address: String,
        resource: PodImageResource,
        fallbackResId: Int,
    ): Bitmap? {
        val earphone = runCatching { PodImagePrefs.findOrLatest(prefs, address) }.getOrNull()
        val custom = runCatching {
            earphone?.imageUri(resource)?.let { uri -> decodeUri(context, uri) }
        }.getOrNull()
        if (custom != null) {
            //android.util.Log.d("HyperDrop-PodImage", "loaded custom $resource for ${earphone?.address}")
            return custom
        }

        val moduleContext = runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null
        return BitmapFactory.decodeResource(moduleContext.resources, fallbackResId)
    }

    fun loadBitmapWithCustomFallback(
        context: Context,
        prefs: SharedPreferences,
        address: String,
        resource: PodImageResource,
        customFallbackResource: PodImageResource,
        fallbackResId: Int,
    ): Bitmap? {
        val earphone = runCatching { PodImagePrefs.findOrLatest(prefs, address) }.getOrNull()
        val custom = runCatching {
            earphone?.imageUri(resource)?.let { uri -> decodeUri(context, uri) }
                ?: earphone?.imageUri(customFallbackResource)?.let { uri -> decodeUri(context, uri) }
        }.getOrNull()
        if (custom != null) {
            //android.util.Log.d("HyperDrop-PodImage", "loaded custom $resource for ${earphone?.address}")
            return custom
        }

        val moduleContext = runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null
        return BitmapFactory.decodeResource(moduleContext.resources, fallbackResId)
    }

    fun loadBoxBitmap(context: Context, prefs: SharedPreferences, address: String): Bitmap? {
        return loadBitmap(
            context,
            prefs,
            address,
            PodImageResource.BOX,
            defaultProductImageRes(context, prefs, address),
        )
    }


    fun loadIslandLeftBitmap(context: Context, prefs: SharedPreferences, address: String): Bitmap? {
        return loadBitmapWithCustomFallback(
            context = context,
            prefs = prefs,
            address = address,
            resource = PodImageResource.LEFT,
            customFallbackResource = PodImageResource.BOX,
            fallbackResId = defaultProductImageRes(context, prefs, address) { it.defaultLeftImageRes },
        )
    }

    fun loadIslandRightBitmap(context: Context, prefs: SharedPreferences, address: String): Bitmap? {
        return loadBitmapWithCustomFallback(
            context = context,
            prefs = prefs,
            address = address,
            resource = PodImageResource.RIGHT,
            customFallbackResource = PodImageResource.BOX,
            fallbackResId = defaultProductImageRes(context, prefs, address) { it.defaultRightImageRes },
        )
    }

    /**
     * Best-effort device name for [address]: saved per-device prefs first (matches what
     * the app UI displays), then the live Bluetooth name for the same address. Never
     * falls back to another device's saved entry.
     */
    fun resolveDeviceName(
        context: Context,
        prefs: SharedPreferences,
        address: String,
    ): String? {
        return runCatching {
            PodImagePrefs.findOrLatest(prefs, address)?.name
                ?: context.getSystemService(BluetoothManager::class.java)
                    ?.adapter
                    ?.getRemoteDevice(address)
                    ?.name
        }.getOrNull()
    }

    @Suppress("MissingPermission")
    private fun defaultProductImageRes(
        context: Context,
        prefs: SharedPreferences,
        address: String,
        select: (cythen.hyperdrop.device.DeviceProfile) -> Int = { it.defaultImageRes },
    ): Int {
        val name = resolveDeviceName(context, prefs, address)
        val resId = select(DeviceRegistry.profileFor(name))
        android.util.Log.d(TAG, "default product image address=$address name=$name res=$resId")
        return resId
    }

    private fun decodeUri(context: Context, uri: android.net.Uri): Bitmap? {
        return runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                input?.let { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()
    }
}
