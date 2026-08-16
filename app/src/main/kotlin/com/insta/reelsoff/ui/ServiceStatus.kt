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
 *
 * Both the master switch (`ACCESSIBILITY_ENABLED`) and the per-service list
 * (`ENABLED_ACCESSIBILITY_SERVICES`) must agree: this project's own
 * acceptance runbook sets both to turn the service on and clears both to
 * turn it off, so an OEM battery manager that clears only the master switch
 * while leaving the component listed must not read as "Service actif".
 */
fun isServiceEnabled(context: Context): Boolean {
    val masterSwitchOn = Settings.Secure.getInt(
        context.contentResolver,
        Settings.Secure.ACCESSIBILITY_ENABLED,
        0,
    ) == 1
    if (!masterSwitchOn) return false
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val name = "${context.packageName}/${InstagramWatcherService::class.java.name}"
    return enabled.split(':').any { it.equals(name, ignoreCase = true) }
}

/**
 * How many of the most recent block events to weigh when deciding whether
 * detection has degraded. A fixed count rather than "whole chart window" (14
 * days): the rule tier is the only honest breakage signal this app has, and
 * evaluating it over the full window lets a single stale HIGH row from
 * before an Instagram update suppress the banner for up to two weeks. A
 * fixed count of the most recent events reacts within a handful of blocks
 * regardless of how bursty or sparse blocking activity is that day.
 */
internal const val DEGRADED_WINDOW_SIZE = 20

/**
 * True when every recent block (see [DEGRADED_WINDOW_SIZE]) came from a
 * fallback tier — the one honest sign that Instagram changed and the rules
 * need repair. Empty is not degraded: zero blocks may be exactly what
 * success looks like.
 *
 * [events] must be ordered oldest first (as `BlockEventDao.observeSince`
 * returns them) so `takeLast` yields the most recent ones.
 */
fun isDegraded(events: List<BlockEvent>): Boolean {
    val recent = events.takeLast(DEGRADED_WINDOW_SIZE)
    return recent.isNotEmpty() && recent.none { it.ruleTier == Tier.HIGH.name }
}
