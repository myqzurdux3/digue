package com.insta.reelsoff.service

import android.content.Context
import com.insta.detection.ParseResult
import com.insta.detection.RuleSet
import com.insta.detection.RuleSetParser
import java.io.File

enum class RuleSource {
    /** A hand-edited file in app storage, so detection can be repaired on device. */
    OVERRIDE,
    BUNDLED,
}

data class LoadedRules(
    val ruleSet: RuleSet,
    val source: RuleSource,
    /** Why the override was rejected, when it was. */
    val error: String?,
)

class RuleSetLoader(private val context: Context) {

    fun load(): LoadedRules {
        val override = File(context.filesDir, FILE_NAME)
        if (override.exists()) {
            when (val result = runCatching { RuleSetParser.parse(override.readText()) }.getOrNull()) {
                is ParseResult.Success -> return LoadedRules(result.ruleSet, RuleSource.OVERRIDE, null)
                is ParseResult.Failure -> return bundled(result.message)
                null -> return bundled("override file could not be read")
            }
        }
        return bundled(null)
    }

    private fun bundled(error: String?): LoadedRules {
        val raw = context.assets.open(FILE_NAME).bufferedReader().use { it.readText() }
        return when (val result = RuleSetParser.parse(raw)) {
            is ParseResult.Success -> LoadedRules(result.ruleSet, RuleSource.BUNDLED, error)
            // Caught by RealFixtureTest before shipping; if it happens here the build is broken.
            is ParseResult.Failure -> error("bundled rules are invalid: ${result.message}")
        }
    }

    private companion object {
        const val FILE_NAME = "rules.json"
    }
}
