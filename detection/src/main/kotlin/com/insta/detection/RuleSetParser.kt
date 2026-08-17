package com.insta.detection

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

sealed interface ParseResult {
    data class Success(val ruleSet: RuleSet) : ParseResult
    data class Failure(val message: String) : ParseResult
}

@Serializable
private data class RawAppRules(val surfaces: Map<String, SurfaceRules>)

@Serializable
private data class RawRuleSet(
    val version: Int,
    val apps: Map<String, RawAppRules>,
)

/**
 * Reads the rules file. Never throws: a hand-edited rules file will be broken
 * one day, and that day the app must degrade, not crash.
 */
object RuleSetParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): ParseResult {
        val decoded = try {
            json.decodeFromString<RawRuleSet>(raw)
        } catch (e: Exception) {
            return ParseResult.Failure("malformed rules file: ${e.message}")
        }

        if (decoded.version != RULES_VERSION) {
            return ParseResult.Failure(
                "unsupported rules version ${decoded.version}, expected $RULES_VERSION",
            )
        }

        val apps = mutableMapOf<String, AppRules>()
        for ((packageName, rawApp) in decoded.apps) {
            if (packageName.isBlank()) {
                return ParseResult.Failure("a package name must not be blank")
            }
            val surfaces = mutableMapOf<Surface, SurfaceRules>()
            for ((name, rules) in rawApp.surfaces) {
                val surface = Surface.entries.firstOrNull { it.name == name }
                    ?: return ParseResult.Failure("unknown surface \"$name\"")
                if (surface == Surface.OTHER) {
                    return ParseResult.Failure("surface \"OTHER\" cannot carry rules")
                }
                // A present-but-blank click target would reach the service, find
                // nothing, and fall back to leaving the screen — a rule that looks
                // configured and does nothing of what it says. Absent is fine and
                // means "exit the ordinary way"; blank is a mistake.
                if (rules.clickViewId != null && rules.clickViewId.isBlank()) {
                    return ParseResult.Failure("a clickViewId must not be blank")
                }
                rules.signals.forEach { signal ->
                    validate(signal)?.let { return ParseResult.Failure(it) }
                }
                surfaces[surface] = rules
            }
            apps[packageName] = AppRules(surfaces)
        }

        return ParseResult.Success(RuleSet(decoded.version, apps))
    }

    /** Returns an error message, or null when the signal is usable. */
    private fun validate(signal: Signal): String? {
        if (signal.absentViewIds.any { it.isBlank() }) {
            return "absentViewIds must not contain a blank id"
        }
        return when (signal.type) {
            SignalType.VIEW_ID ->
                if (signal.value.isNullOrBlank()) "VIEW_ID signal needs a non-empty value" else null

            SignalType.CONTENT_DESCRIPTION ->
                if (signal.anyOf.isEmpty()) "CONTENT_DESCRIPTION signal needs a non-empty anyOf" else null

            SignalType.NAV_BAR_INDEX -> {
                val index = signal.value?.toIntOrNull()
                if (index == null || index < 0) "NAV_BAR_INDEX signal needs a non-negative integer value" else null
            }
        }
    }
}
