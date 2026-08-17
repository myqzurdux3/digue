package com.insta.reelsoff.service

import android.content.Context
import java.io.File

/**
 * Where captures live and what may be deleted from there.
 *
 * Extracted so the service and the home screen agree by construction. The service
 * clears earlier sessions when a new one is armed; the screen offers to clear the
 * lot on demand. Both go through [deleteCaptures], so "which files are ours" is
 * decided once instead of in two places that could drift — and the naming rule is
 * the thing that keeps either of them from deleting something that is not a
 * capture at all.
 */

/** `capture-<stamp>-<index>.json`, the only shape anything here will delete. */
private val CAPTURE_NAME = Regex("""capture-(\d+)-\d+\.json""")

/** The session stamp in a capture's name, or null when the name is not one of ours. */
internal fun captureStampOf(fileName: String): Long? =
    CAPTURE_NAME.matchEntire(fileName)?.groupValues?.get(1)?.toLongOrNull()

/**
 * Whether [fileName] is a capture this app wrote — and, when [before] is given,
 * one belonging to an earlier session than that stamp.
 *
 * Pure, and separated from the file system on purpose: it is the whole of the
 * decision, and it is the part worth a test. Anything parked in that directory
 * that is not a capture — a scrubbed derivative, a note — is never touched.
 */
internal fun isDeletableCapture(fileName: String, before: Long? = null): Boolean {
    val stamp = captureStampOf(fileName) ?: return false
    return before == null || stamp < before
}

internal fun captureDirectory(context: Context): File =
    File(context.getExternalFilesDir(null), "captures").apply { mkdirs() }

/** The captures on disk, newest session first, then by index. */
internal fun listCaptures(context: Context): List<File> =
    captureDirectory(context)
        .listFiles()
        .orEmpty()
        .filter { isDeletableCapture(it.name) }
        .sortedWith(compareByDescending<File> { captureStampOf(it.name) ?: 0 }.thenBy { it.name })

/**
 * Deletes captures and answers how many went.
 *
 * [before] null means all of them — what the button on the home screen asks for.
 * A stamp means "everything from an earlier session", which is what arming a new
 * capture does, and which cannot race the writes it shares a scope with: a late
 * purge for session 1 still leaves session 2 alone, because it compares stamps
 * rather than skipping a single name.
 */
internal fun deleteCaptures(context: Context, before: Long? = null): Int =
    captureDirectory(context)
        .listFiles()
        .orEmpty()
        .filter { isDeletableCapture(it.name, before) }
        .count { it.delete() }
