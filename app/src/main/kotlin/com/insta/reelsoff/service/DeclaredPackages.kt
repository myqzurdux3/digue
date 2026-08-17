package com.insta.reelsoff.service

import com.insta.detection.RuleSet
import com.insta.detection.Surface

/**
 * The packages the service should declare to Android, given the rules and what
 * the user has switched on.
 *
 * `android:packageNames` is enforced by the system, not by this app: a package
 * left out of it cannot reach the service at all. Deriving the list from the
 * settings is what makes "the permission follows the switch" true rather than
 * merely intended — switching Snapchat off makes the service incapable of
 * receiving Snapchat's screens, not just uninterested in them.
 */
fun declaredPackages(ruleSet: RuleSet, blocked: Set<Surface>): Set<String> =
    ruleSet.apps
        .filterValues { app -> app.surfaces.keys.any { it in blocked } }
        .keys

/** Matches no installed app; see [packageNamesFor]. Internal so tests can assert against it directly. */
internal const val NO_PACKAGE = "com.insta.reelsoff.none"

/**
 * The value to hand to `AccessibilityServiceInfo.packageNames`.
 *
 * A null (or empty) array means "every app" to Android, which is the opposite
 * of what an empty selection should mean here — so an empty [packages] is
 * mapped onto a single package that cannot match anything, rather than onto
 * an empty array. The result is therefore never empty and never null.
 */
fun packageNamesFor(packages: Set<String>): Array<String> =
    if (packages.isEmpty()) arrayOf(NO_PACKAGE) else packages.toTypedArray()
