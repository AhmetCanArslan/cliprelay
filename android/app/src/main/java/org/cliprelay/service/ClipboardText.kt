package org.cliprelay.service

// Extracts relayable plain text from a clipboard clip without touching non-text content.

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context

object ClipboardText {

    // Matches ClipRelayService's payload cap; a char is at least one UTF-8 byte,
    // so anything longer would be rejected by the service anyway.
    const val MAX_TEXT_CHARS = 102_400

    /**
     * Returns the clip's text, or null when it has none worth forwarding.
     * Never calls coerceToText() on a URI clip of non-text type: for an image
     * (e.g. a screenshot) that reads the whole file as a "string", and the
     * resulting multi-megabyte Intent extra crashes the app with
     * TransactionTooLargeException.
     */
    fun extract(context: Context, clip: ClipData): String? {
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        val text = when {
            item.text != null -> item.text.toString()
            item.uri != null && !hasTextMimeType(clip.description) -> null
            else -> item.coerceToText(context)?.toString()
        }
        if (text.isNullOrBlank() || text.length > MAX_TEXT_CHARS) return null
        return text
    }

    private fun hasTextMimeType(description: ClipDescription?): Boolean =
        description?.hasMimeType("text/*") == true
}
