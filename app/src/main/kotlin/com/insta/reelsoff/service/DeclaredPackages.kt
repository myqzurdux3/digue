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
