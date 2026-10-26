package io.github.audiosplit.engine

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import java.lang.reflect.InvocationTargetException

/**
 * Builds a framework Context for the identity that actually runs this UserService.
 *
 * Android 16 createPackageContext() retains its container's base/op package names. Loading the
 * shell package's resources therefore does not, by itself, produce shell attribution. The
 * independent createAppContext() factory has no container and uses the loaded shell package.
 * All non-SDK access stays here and is attempted only after checking the real process UID.
 */
internal object ShellAudioContext {
    private const val SHELL_UID = 2000
    private const val SHELL_PACKAGE = "com.android.shell"

    data class Result(
        val context: Context,
        val ready: Boolean,
        val error: String?,
        val inheritedPackage: String?,
        val inheritedOpPackage: String?,
        val inheritedAttributionUid: Int?,
        val inheritedAttributionPackage: String?,
    )

    fun create(original: Context): Result {
        var inherited: Context? = null
        var inheritedPackage: String? = null
        var inheritedOpPackage: String? = null
        var inheritedAttributionUid: Int? = null
        var inheritedAttributionPackage: String? = null

        fun result(context: Context, ready: Boolean, error: String?) = Result(
            context, ready, error, inheritedPackage, inheritedOpPackage,
            inheritedAttributionUid, inheritedAttributionPackage,
        )

        try {
            val uid = Process.myUid()
            check(uid == SHELL_UID) {
                "The UserService must run as shell UID $SHELL_UID; actual UID=$uid."
            }

            val loadedContext = original.createPackageContext(SHELL_PACKAGE, 0)
            inherited = loadedContext
            inheritedPackage = loadedContext.packageName
            inheritedOpPackage = loadedContext.opPackageName
            inheritedAttributionUid = loadedContext.attributionSource.uid
            inheritedAttributionPackage = loadedContext.attributionSource.packageName

            val shellInfo = loadedContext.packageManager.getApplicationInfo(SHELL_PACKAGE, 0)
            check(shellInfo.uid == uid) {
                "The shell package UID=${shellInfo.uid} does not own process UID=$uid."
            }
            check(loadedContext.applicationInfo.uid == uid
                && loadedContext.applicationInfo.packageName == SHELL_PACKAGE) {
                "The loaded framework package is not the shell package owned by this process."
            }

            val implementationClass = Class.forName("android.app.ContextImpl")
            var implementation: Context = loadedContext
            var unwrapCount = 0
            while (!implementationClass.isInstance(implementation)
                && implementation is ContextWrapper) {
                check(unwrapCount++ < 12) { "The framework Context wrapper chain is too deep." }
                val next = requireNotNull(implementation.baseContext) {
                    "The framework Context wrapper has no base context."
                }
                check(next !== implementation) { "The framework Context wrapper refers to itself." }
                implementation = next
            }
            check(implementationClass.isInstance(implementation)) {
                "Unsupported framework Context implementation: ${implementation.javaClass.name}."
            }

            val mainThread = requireNotNull(implementationClass.getDeclaredField("mMainThread")
                .apply { isAccessible = true }.get(implementation)) {
                "The framework Context has no ActivityThread."
            }
            val packageInfo = requireNotNull(implementationClass.getDeclaredField("mPackageInfo")
                .apply { isAccessible = true }.get(implementation)) {
                "The framework Context has no LoadedApk."
            }
            val factory = implementationClass.getDeclaredMethod(
                "createAppContext",
                Class.forName("android.app.ActivityThread"),
                Class.forName("android.app.LoadedApk"),
                String::class.java,
            ).apply { isAccessible = true }
            val fresh = factory.invoke(null, mainThread, packageInfo, SHELL_PACKAGE) as Context
            requireShellAttribution(fresh, uid)

            // AudioManager prefers getApplicationContext() when it is non-null. Reject a loaded
            // application whose context would silently switch back to the original app package.
            fresh.applicationContext?.let { requireShellAttribution(it, uid) }
            return result(fresh, ready = true, error = null)
        } catch (error: Throwable) {
            val cause = if (error is InvocationTargetException) error.targetException else error
            if (cause !is Exception && cause !is LinkageError) throw cause
            return result(inherited ?: original, ready = false,
                error = "${cause.javaClass.simpleName}: ${cause.message.orEmpty().take(500)}")
        }
    }

    private fun requireShellAttribution(context: Context, uid: Int) {
        check(context.packageName == SHELL_PACKAGE && context.opPackageName == SHELL_PACKAGE
            && context.attributionSource.uid == uid
            && context.attributionSource.packageName == SHELL_PACKAGE) {
            "The independent framework Context has incompatible shell attribution: " +
                "package=${context.packageName}, opPackage=${context.opPackageName}, " +
                "attributionUid=${context.attributionSource.uid}, " +
                "attributionPackage=${context.attributionSource.packageName}."
        }
    }
}
