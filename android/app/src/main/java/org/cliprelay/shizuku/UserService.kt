package org.cliprelay.shizuku

// Runs inside a Shizuku-spawned process with shell (or root) privileges.
// Its only job is reading the primary clip: a shell-uid caller is exempt from
// the Android 10+ "only the focused app may read the clipboard" rule, which is
// what lets auto-copy work without the accessibility service and without the
// ghost activity stealing focus.

import android.os.IBinder

class UserService : IUserService.Stub() {

    override fun destroy() {
        System.exit(0)
    }

    override fun isClipboardReadable(): Boolean =
        runCatching { readClip(); true }
            .onFailure { android.util.Log.w(TAG, "clipboard unreadable", it) }
            .getOrDefault(false)

    override fun getPrimaryClipText(): String? = runCatching { readClip() }
        .onFailure { android.util.Log.w(TAG, "getPrimaryClipText failed", it) }
        .getOrNull()

    /**
     * Talks to the clipboard system service directly over its AIDL interface.
     * Reflection is unavoidable: android.content.IClipboard is hidden API, and
     * getPrimaryClip's signature keeps gaining parameters (package, attribution
     * tag, user id, device id) across releases — so pick the shortest overload
     * and fill arguments by type.
     */
    private fun readClip(): String? {
        val clip = primaryClip() ?: return null
        val itemCount = clip.javaClass.getMethod("getItemCount").invoke(clip) as Int
        if (itemCount <= 0) return null
        val item = clip.javaClass.getMethod("getItemAt", Int::class.javaPrimitiveType)
            .invoke(clip, 0) ?: return null
        val text = item.javaClass.getMethod("getText").invoke(item) as? CharSequence
        return text?.toString()?.takeIf { it.isNotEmpty() }
    }

    private fun primaryClip(): Any? {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager
            .getMethod("getService", String::class.java)
            .invoke(null, "clipboard") as IBinder? ?: return null

        val stub = Class.forName("android.content.IClipboard\$Stub")
        val clipboard = stub
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder) ?: return null

        val method = clipboard.javaClass.methods
            .filter { it.name == "getPrimaryClip" }
            .minByOrNull { it.parameterTypes.size } ?: return null

        val callingPackage = callingPackage()
        var stringArg = 0
        val args: Array<Any?> = arrayOfNulls(method.parameterTypes.size)
        method.parameterTypes.forEachIndexed { index, type ->
            args[index] = when {
                // First String is the calling package; later ones (attribution
                // tag) must stay null.
                type == String::class.java -> if (stringArg++ == 0) callingPackage else null
                type == Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }

        return method.invoke(clipboard, *args)
    }

    /** The package name the clipboard service will accept for our uid. */
    private fun callingPackage(): String = when (android.os.Process.myUid()) {
        SHELL_UID -> "com.android.shell"
        SYSTEM_UID, ROOT_UID -> "android"
        else -> "com.android.shell"
    }

    private companion object {
        const val SHELL_UID = 2000
        const val SYSTEM_UID = 1000
        const val ROOT_UID = 0
        const val TAG = "ClipRelayUserService"
    }
}
