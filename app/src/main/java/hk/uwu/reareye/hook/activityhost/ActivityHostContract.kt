package hk.uwu.reareye.hook.activityhost

internal object ActivityHostContract {
    const val VERSION = 1
    const val HOST_PACKAGE = "com.xiaomi.subscreencenter"
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    const val BROKER_ACTION = "hk.uwu.reareye.activityhost.REQUEST_BROKER"
    const val REGISTRY_ACTION = "hk.uwu.reareye.activityhost.REQUEST_TASK_REGISTRY"
    const val MAX_SESSIONS = 4
    const val MIN_DPI = 120
    const val MAX_DPI = 960
    const val MAX_DIMENSION = 8192
}
