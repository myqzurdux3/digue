package com.insta.reelsoff.ui

import android.content.Context
import android.provider.Settings
import com.insta.detection.Tier
import com.insta.reelsoff.data.BlockEvent
import com.insta.reelsoff.service.InstagramWatcherService

/**
 * Reads the real state from the system rather than tracking it ourselves:
 * manufacturer task killers stop accessibility services without telling the
 * app, and a blocker that is silently off is worse than no blocker at all.
 */
fun isServiceEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val name = "${context.packageName}/${InstagramWatcherService::class.java.name}"
    return enabled.split(':').any { it.equals(name, ignoreCase = true) }
}

/**
 * True when every recent block came from a fallback tier — the one honest
 * sign that Instagram changed and the rules need repair.
 */
fun isDegraded(events: List<BlockEvent>): Boolean =
    events.isNotEmpty() && events.none { it.ruleTier == Tier.HIGH.name }
