package coredevices.util

import PlatformContext
import android.content.Intent
import kotlin.system.exitProcess

actual fun restartApp(platformContext: PlatformContext) {
    val context = platformContext.context.applicationContext
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
    }
    exitProcess(0)
}
