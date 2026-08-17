package org.cliprelay.shizuku;

interface IUserService {
    void destroy() = 16777114;

    /** Current clipboard text read with shell privileges, or null when unavailable. */
    String getPrimaryClipText() = 1;

    /** True when privileged clipboard reading actually works on this device. */
    boolean isClipboardReadable() = 2;
}
