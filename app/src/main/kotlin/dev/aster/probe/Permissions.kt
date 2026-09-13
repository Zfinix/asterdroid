package dev.aster.probe

import android.Manifest.permission as P
import android.app.Activity
import android.content.pm.PackageManager

/** Every runtime permission the manifest declares, asked for in one go at launch. */
object Permissions {
    const val FOREGROUND = 1
    const val BACKGROUND = 2

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

    /** Android 11+ drops the whole request if these ride along with the ones above. */
    val background = listOf(P.ACCESS_BACKGROUND_LOCATION, P.BODY_SENSORS_BACKGROUND)

    fun missing(activity: Activity, wanted: List<String>): Array<String> = wanted
        .filter { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        .toTypedArray()

    fun request(activity: Activity, wanted: List<String>, code: Int) {
        val ask = missing(activity, wanted)
        if (ask.isNotEmpty()) activity.requestPermissions(ask, code)
    }
}
