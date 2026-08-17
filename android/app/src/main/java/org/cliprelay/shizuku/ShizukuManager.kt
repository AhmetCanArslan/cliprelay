package org.cliprelay.shizuku

// Binds the privileged [UserService] through Shizuku and exposes a blocking
// clipboard read. Used as an alternative auto-copy backend: with Shizuku the
// clipboard can be read from the background, so no accessibility service and no
// focus-stealing ghost activity are needed.

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

enum class ShizukuState {
    /** No Shizuku app on the device. */
    NOT_INSTALLED,
    /** Installed, but the Shizuku service isn't running (needs ADB/root start). */
    NOT_RUNNING,
    /** Running, but the user hasn't granted ClipRelay access. */
    NO_PERMISSION,
    /** Running and granted — clipboard reads will work. */
    READY,
}

object ShizukuManager {

    private const val TAG = "ShizukuManager"
    private const val PERMISSION_REQUEST_CODE = 5710
    private const val BIND_TIMEOUT_MS = 8_000L
    const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"

    private var appContext: Context? = null

    @Volatile
    private var service: IUserService? = null
    private val bindLock = Any()
    private var binding = false
    private var bindLatch: CountDownLatch? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(appContext!!.packageName, UserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("clip")
            .debuggable(false)
            .version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.takeIf { it.pingBinder() }?.let { IUserService.Stub.asInterface(it) }
            synchronized(bindLock) {
                binding = false
                bindLatch?.countDown()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            synchronized(bindLock) {
                binding = false
                bindLatch?.countDown()
            }
        }
    }

    private val binderDead = Shizuku.OnBinderDeadListener { service = null }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        runCatching { Shizuku.addBinderDeadListener(binderDead) }
            .onFailure { Log.w(TAG, "Could not register binder-dead listener", it) }
    }

    fun state(): ShizukuState {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        return when {
            binderAlive && hasPermission() -> ShizukuState.READY
            binderAlive -> ShizukuState.NO_PERMISSION
            appContext?.let { managerPackage(it) } != null -> ShizukuState.NOT_RUNNING
            else -> ShizukuState.NOT_INSTALLED
        }
    }

    val isReady: Boolean get() = state() == ShizukuState.READY

    /** Package name of the installed Shizuku manager app, or null when absent. */
    fun managerPackage(context: Context): String? = runCatching {
        context.packageManager.getPermissionInfo(SHIZUKU_PERMISSION, 0).packageName
    }.getOrNull()

    fun hasPermission(): Boolean = runCatching {
        if (!Shizuku.pingBinder()) return false
        // Pre-v11 Shizuku used a different permission model we don't support.
        if (Shizuku.isPreV11()) return false
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Shows Shizuku's grant dialog. Safe to call from any activity/context. */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            .onFailure { Log.w(TAG, "requestPermission failed", it) }
    }

    /**
     * Reads the clipboard through the privileged service.
     * Blocking — never call on the main thread. Returns null when Shizuku is
     * unavailable, not granted, or the clip holds no text.
     */
    fun readClipboardText(): String? {
        val svc = service() ?: return null
        return runCatching { svc.primaryClipText }
            .onFailure {
                Log.w(TAG, "Clipboard read failed", it)
                // A dead binder must not stick around as a live-looking service.
                service = null
            }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    /** True when the privileged clipboard read actually works on this device. */
    fun verifyClipboardAccess(): Boolean {
        val svc = service() ?: return false
        return runCatching { svc.isClipboardReadable }.getOrDefault(false)
    }

    /** Binds the user service if needed. Blocking — never call on the main thread. */
    private fun service(): IUserService? {
        if (appContext == null || !isReady) return null

        service?.let {
            if (it.asBinder().pingBinder()) return it
            service = null
        }

        // One binder attempt at a time; concurrent callers wait on the same latch.
        var startedByUs = false
        val latch = synchronized(bindLock) {
            val existing = bindLatch
            if (binding && existing != null) {
                existing
            } else {
                binding = true
                startedByUs = true
                CountDownLatch(1).also { bindLatch = it }
            }
        }

        if (startedByUs) {
            val started = runCatching { Shizuku.bindUserService(userServiceArgs, connection) }
                .onFailure { Log.w(TAG, "bindUserService failed", it) }
                .isSuccess
            if (!started) {
                synchronized(bindLock) {
                    binding = false
                    bindLatch = null
                }
                latch.countDown()
                return null
            }
        }

        if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Timed out binding Shizuku user service")
            synchronized(bindLock) { binding = false }
            return null
        }
        return service
    }
}
