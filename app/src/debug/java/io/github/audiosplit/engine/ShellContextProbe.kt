package io.github.audiosplit.engine

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import android.os.Process
import java.lang.reflect.InvocationTargetException
import org.json.JSONObject
import kotlin.system.exitProcess

/**
 * Debug-only entry point executed by adb shell/app_process, outside an Android app process.
 * This reproduces the Context bootstrap used by Shizuku without requiring Shizuku or Bluetooth.
 * It never registers an audio policy, requests focus, or changes a device's audio routes.
 */
object ShellContextProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val report = JSONObject()
            .put("sdk", Build.VERSION.SDK_INT)
            .put("effectiveUid", Process.myUid())
            .put("effectivePid", Process.myPid())
        try {
            check(Build.VERSION.SDK_INT == 36) { "This regression must run on Android 16 / API 36." }
            check(Process.myUid() == SHELL_UID) { "Run the probe through adb shell, as UID 2000." }
            if (Looper.getMainLooper() == null) {
                @Suppress("DEPRECATION")
                Looper.prepareMainLooper()
            }

            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val current = activityThreadClass.getDeclaredMethod("currentActivityThread").apply {
                isAccessible = true
            }.invoke(null)
            val thread = current ?: activityThreadClass.getDeclaredMethod("systemMain").apply {
                isAccessible = true
            }.invoke(null)
            val systemContext = activityThreadClass.getDeclaredMethod("getSystemContext").apply {
                isAccessible = true
            }.invoke(thread) as Context
            val original = systemContext.createPackageContext(APP_PACKAGE, 0)

            // createPackageContext changes the loaded APK, while retaining caller attribution.
            // The old preflight incorrectly treated that inherited package as the process UID.
            val inherited = original.createPackageContext(SHELL_PACKAGE, 0)
            val attribution = inherited.attributionSource
            val rejectedByOldGuard = Process.myUid() != SHELL_UID ||
                inherited.packageName != SHELL_PACKAGE || attribution.uid != SHELL_UID ||
                attribution.packageName != SHELL_PACKAGE
            report.put("inherited", describeContext(inherited))
                .put("inheritedRejectedByOldGuard", rejectedByOldGuard)
            check(rejectedByOldGuard) { "The original Context attribution regression was not reproduced." }

            val result = ShellAudioContext.create(original)
            report.put("factoryReady", result.ready)
                .put("factoryError", result.error ?: JSONObject.NULL)
                .put("corrected", describeContext(result.context))
            check(result.ready) { "Shell context factory failed: ${result.error}" }
            val corrected = result.context
            check(corrected.packageName == SHELL_PACKAGE) { "The base package must be the shell package." }
            check(corrected.opPackageName == SHELL_PACKAGE) { "AppOps attribution must use the shell package." }
            check(corrected.applicationInfo.uid == SHELL_UID) { "The loaded APK must belong to the shell UID." }
            check(corrected.attributionSource.uid == Process.myUid()) { "Attribution must use the real process UID." }
            check(corrected.attributionSource.packageName == SHELL_PACKAGE) { "Audio attribution must use the shell package." }
            val routingPermission = corrected.checkPermission(ROUTING_PERMISSION, Process.myPid(), Process.myUid())
            report.put("routingPermissionGranted", routingPermission == PackageManager.PERMISSION_GRANTED)
            check(routingPermission == PackageManager.PERMISSION_GRANTED) { "Shell lacks MODIFY_AUDIO_ROUTING on this system image." }

            val audio = requireNotNull(corrected.getSystemService(AudioManager::class.java))
            report.put("outputDeviceCount", audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).size)
                .put("audioPolicyRegistered", false)
                .put("routesModified", false)
                .put("passed", true)
            println("SHELL_CONTEXT_REGRESSION_OK $report")
            exitProcess(0)
        } catch (failure: Throwable) {
            val cause = if (failure is InvocationTargetException) failure.targetException else failure
            report.put("passed", false)
                .put("error", "${cause.javaClass.simpleName}: ${cause.message.orEmpty()}")
            System.err.println("SHELL_CONTEXT_REGRESSION_FAILED $report")
            cause.printStackTrace(System.err)
            exitProcess(1)
        }
    }

    private fun describeContext(context: Context): JSONObject = JSONObject()
        .put("packageName", context.packageName)
        .put("opPackageName", context.opPackageName)
        .put("applicationUid", context.applicationInfo.uid)
        .put("attributionUid", context.attributionSource.uid)
        .put("attributionPackage", context.attributionSource.packageName)

    private const val APP_PACKAGE = "io.github.audiosplit"
    private const val SHELL_PACKAGE = "com.android.shell"
    private const val SHELL_UID = 2000
    private const val ROUTING_PERMISSION = "android.permission.MODIFY_AUDIO_ROUTING"
}
