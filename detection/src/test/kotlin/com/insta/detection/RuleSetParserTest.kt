package com.insta.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSetParserTest {

    private fun failureMessage(raw: String): String {
        val result = RuleSetParser.parse(raw)
        assertTrue("expected a failure, got $result", result is ParseResult.Failure)
        return (result as ParseResult.Failure).message
    }

    @Test
    fun `parses a well formed rule set`() {
        val raw = """
            {
              "version": 1,
              "surfaces": {
                "REELS": {
                  "signals": [
                    { "tier": "HIGH", "type": "VIEW_ID",
                      "value": "com.instagram.android:id/clips_tab", "requireSelected": true },
                    { "tier": "MEDIUM", "type": "CONTENT_DESCRIPTION",
                      "anyOf": ["Reels", "Réels"], "requireSelected": true },
                    { "tier": "LOW", "type": "NAV_BAR_INDEX",
                      "value": "2", "requireSelected": true }
                  ]
                }
              }
            }
        """.trimIndent()

        val result = RuleSetParser.parse(raw)

        assertTrue(result is ParseResult.Success)
        val ruleSet = (result as ParseResult.Success).ruleSet
        assertEquals(1, ruleSet.version)
        val signals = ruleSet.surfaces.getValue(Surface.REELS).signals
        assertEquals(3, signals.size)
        assertEquals(Tier.HIGH, signals[0].tier)
        assertEquals(SignalType.VIEW_ID, signals[0].type)
        assertEquals(listOf("Reels", "Réels"), signals[1].anyOf)
        assertEquals("2", signals[2].value)
    }

    @Test
    fun `requireSelected defaults to true`() {
        val raw = """
            { "version": 1, "surfaces": { "EXPLORE": { "signals": [
              { "tier": "HIGH", "type": "VIEW_ID", "value": "x" }
            ] } } }
        """.trimIndent()

        val result = RuleSetParser.parse(raw) as ParseResult.Success

        assertTrue(result.ruleSet.surfaces.getValue(Surface.EXPLORE).signals[0].requireSelected)
    }

    @Test
    fun `rejects malformed json instead of throwing`() {
        assertTrue(failureMessage("{ this is not json").contains("malformed", ignoreCase = true))
    }

    @Test
    fun `rejects an unknown surface name`() {
        val raw = """
            { "version": 1, "surfaces": { "STORIES": { "signals": [] } } }
        """.trimIndent()

        assertTrue(failureMessage(raw).contains("STORIES"))
    }

    @Test
    fun `rejects a view id signal with no value`() {
        val raw = """
            { "version": 1, "surfaces": { "REELS": { "signals": [
              { "tier": "HIGH", "type": "VIEW_ID" }
            ] } } }
        """.trimIndent()

        assertTrue(failureMessage(raw).contains("VIEW_ID"))
    }

    @Test
    fun `rejects a content description signal with an empty anyOf`() {
        val raw = """
            { "version": 1, "surfaces": { "REELS": { "signals": [
              { "tier": "MEDIUM", "type": "CONTENT_DESCRIPTION", "anyOf": [] }
            ] } } }
        """.trimIndent()

        assertTrue(failureMessage(raw).contains("CONTENT_DESCRIPTION"))
    }

    @Test
    fun `rejects a nav bar index that is not a number`() {
        val raw = """
            { "version": 1, "surfaces": { "REELS": { "signals": [
              { "tier": "LOW", "type": "NAV_BAR_INDEX", "value": "middle" }
            ] } } }
        """.trimIndent()

        assertTrue(failureMessage(raw).contains("NAV_BAR_INDEX"))
    }

    @Test
    fun `rejects the OTHER surface as a rule target`() {
        val raw = """
            { "version": 1, "surfaces": { "OTHER": { "signals": [] } } }
        """.trimIndent()

        assertTrue(failureMessage(raw).contains("OTHER"))
    }
}
