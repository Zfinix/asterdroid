package dev.aster.probe

import android.Manifest.permission as P
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * What the phone has to hand over, and when to ask. Asking for all of it at
 * launch was fifteen system dialogs before anything else on screen, for
 * calls, texts and sensors most people never ask the agent to touch. Now
 * only notifications are asked for at first run, and the rest when a person
 * chooses to give them.
 */
object Permissions {
    /** The verbs' permissions, asked together when someone chooses to give them; background follows. */
    const val FOREGROUND = 1
    const val BACKGROUND = 2
    /** Asked just before the scanner opens, and nowhere else. */
    const val CAMERA = 3
    /** The first-run prompt, which must not chain into the background ones the way [FOREGROUND] does. */
    const val NOTIFY = 4

    /** Every runtime permission the manifest declares, for the agent's verbs that use them. */
    val runtime = listOf(
        P.CAMERA, P.RECORD_AUDIO,
        P.ACCESS_FINE_LOCATION, P.ACCESS_COARSE_LOCATION, P.ACCESS_MEDIA_LOCATION,
        P.READ_CONTACTS, P.WRITE_CONTACTS, P.GET_ACCOUNTS,
        P.READ_CALENDAR, P.WRITE_CALENDAR,
        P.READ_PHONE_STATE, P.READ_PHONE_NUMBERS, P.CALL_PHONE, P.ANSWER_PHONE_CALLS,
        P.ACCEPT_HANDOVER, P.READ_CALL_LOG, P.WRITE_CALL_LOG, P.ADD_VOICEMAIL, P.USE_SIP,
        P.SEND_SMS, P.RECEIVE_SMS, P.READ_SMS, P.RECEIVE_MMS, P.RECEIVE_WAP_PUSH,
        P.BODY_SENSORS, P.ACTIVITY_RECOGNITION,
        P.BLUETOOTH_SCAN, P.BLUETOOTH_CONNECT, P.BLUETOOTH_ADVERTISE,
        P.NEARBY_WIFI_DEVICES, P.UWB_RANGING,
        P.POST_NOTIFICATIONS,
        P.READ_MEDIA_IMAGES, P.READ_MEDIA_VIDEO, P.READ_MEDIA_AUDIO,
        P.READ_MEDIA_VISUAL_USER_SELECTED,
        P.READ_EXTERNAL_STORAGE, P.WRITE_EXTERNAL_STORAGE,
    )

    /** The one prompt at first run: the agent's questions and replies arrive as notifications. */
    val firstRun: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(P.POST_NOTIFICATIONS) else emptyList()

    /** Android 11+ drops the whole request if these ride along with the ones above. */
    val background = listOf(P.ACCESS_BACKGROUND_LOCATION, P.BODY_SENSORS_BACKGROUND)

    fun missing(activity: Activity, wanted: List<String>): Array<String> = wanted
        .filter { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        .toTypedArray()

    fun request(activity: Activity, wanted: List<String>, code: Int) {
        val ask = missing(activity, wanted)
        if (ask.isNotEmpty()) activity.requestPermissions(ask, code)
    }

    fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** How many of the verbs' permissions are still to give, for the row that offers them all. */
    fun ungranted(context: Context): Int = runtime.count { !granted(context, it) }

    /**
     * Android 13 greys out the accessibility switch for an app installed from
     * a file, until App info's menu says "Allow restricted settings". The app
     * op Settings checks cannot be read without a privileged permission, so
     * this goes by what decides it: how the app was installed. A store or adb
     * install is never restricted. Whether the person already allowed it is
     * not visible here, which is why the help reads "if it is greyed out".
     */
    fun restricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val source = runCatching {
            context.packageManager.getInstallSourceInfo(context.packageName).packageSource
        }.getOrDefault(PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED)
        return source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE ||
            source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
    }

    /** App info for Droid, where the menu with "Allow restricted settings" is. */
    fun appInfo(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    /** Doze stops a long-running agent, its relay link and its chat bridge unless the phone exempts it. */
    fun unrestrictedBattery(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    /** The one-tap system prompt for the exemption, rather than a list to search. */
    fun batteryPrompt(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))
}
