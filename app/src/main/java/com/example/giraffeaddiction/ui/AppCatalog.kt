package com.example.giraffeaddiction.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.example.giraffeaddiction.service.Notifications
import com.example.giraffeaddiction.vpn.WireGuardController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Labels and icons of installed apps, loaded once and kept for the life of the process. */
object AppCatalog {

    data class Entry(val packageName: String, val label: String, val system: Boolean)

    private val icons = ConcurrentHashMap<String, ImageBitmap>()
    private val labels = ConcurrentHashMap<String, String>()

    /** Packages the picker never offers and the engine refuses: pausing them would brick the setup. */
    val forbidden: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.phone",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.google.android.inputmethod.latin",
        Notifications.SHIZUKU_PACKAGE,
        WireGuardController.PACKAGE,
    )

    fun isForbidden(context: Context, packageName: String): Boolean =
        packageName == context.packageName || packageName in forbidden

    fun label(context: Context, packageName: String): String =
        labels[packageName] ?: run {
            val pm = context.packageManager
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                packageName
            }
            labels[packageName] = label
            label
        }

    fun icon(context: Context, packageName: String, sizePx: Int = 192): ImageBitmap? =
        icons[packageName] ?: try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            drawable.toBitmap(sizePx, sizePx).asImageBitmap().also { icons[packageName] = it }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    /** Every launchable app except this one, Shizuku, WireGuard and the core system packages. */
    suspend fun launchable(context: Context): List<Entry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        resolved
            .map { it.activityInfo.packageName }
            .distinct()
            .filterNot { isForbidden(context, it) }
            .mapNotNull { pkg ->
                val info = try {
                    pm.getApplicationInfo(pkg, 0)
                } catch (e: PackageManager.NameNotFoundException) {
                    return@mapNotNull null
                }
                val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                    info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
                Entry(pkg, label(context, pkg), system)
            }
            .sortedBy { it.label.lowercase() }
    }
}
