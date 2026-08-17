package com.insta.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
              "version": 2,
              "apps": {
                "com.instagram.android": {
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
              }
            }
        """.trimIndent()

        val result = RuleSetParser.parse(raw)

        assertTrue(result is ParseResult.Success)
        val ruleSet = (result as ParseResult.Success).ruleSet
        assertEquals(2, ruleSet.version)
        val signals = ruleSet.apps.getValue("com.instagram.android").surfaces.getValue(Surface.REELS).signals
        assertEquals(3, signals.size)
        assertEquals(Tier.HIGH, signals[0].tier)
        assertEquals(SignalType.VIEW_ID, signals[0].type)
        assertEquals(listOf("Reels", "Réels"), signals[1].anyOf)
        assertEquals("2", signals[2].value)
    }

    @Test
    fun `requireSelected defaults to true`() {
        val raw = """
            {
              "version": 2,
              "apps": { "com.instagram.android": { "surfaces": { "EXPLORE": { "signals": [
                { "tier": "HIGH", "type": "VIEW_ID", "value": "x" }
              ] } } } }
            }
        """.trimIndent()

        val result = RuleSetParser.parse(raw) as ParseResult.Success

        assertTrue(
            result.ruleSet.apps.getValue("com.instagram.android").surfaces.getValue(Surface.EXPLORE)
                .signals[0].requireSelected,
        )
    }

    @Test
    fun `rejects malformed json instead of throwing`() {
        assertTrue(failureMessage("{ this is not json").contains("malformed", ignoreCase = true))
    }

    @Test
    fun `rejects an unknown surface name (legacy single-app shape)`() {
        val raw = """
            {
              "version": 2,
              "apps": { "com.instagram.android": { "surfaces": { "STORIES": { "signals": [] } } } }
            }
        """.trimIndent()

        assertTrue(failureMessage(raw).contains("STORIES"))
    }

    @Test
    fun `rejects a view id signal with no value`() {
        val raw = """
            {
              "version": 2,
              "apps": { "com.instagram.android": { "surfaces": { "REELS": { "signals": [
                { "tier": "HIGH", "type": "VIEW_ID" }
              ] } } } }
            }
        """.trimIndent()

        assertTrue(failureMessage(raw).contains("VIEW_ID"))
    }

    @Test
    fun `rejects a content description signal with an empty anyOf`() {
        val raw = """
            {
              "version": 2,
              "apps": { "com.instagram.android": { "surfaces": { "REELS": { "signals": [
                { "tier": "MEDIUM", "type": "CONTENT_DESCRIPTION", "anyOf": [] }
              ] } } } }
            }
        """.trimIndent()

        assertTrue(failureMessage(raw).contains("CONTENT_DESCRIPTION"))
    }

    @Test
    fun `rejects a nav bar index that is not a number`() {
        val raw = """
            {
              "version": 2,
              "apps": { "com.instagram.android": { "surfaces": { "REELS": { "signals": [
                { "tier": "LOW", "type": "NAV_BAR_INDEX", "value": "middle" }
              ] } } } }
            }
        """.trimIndent()

        assertTrue(failureMessage(raw).contains("NAV_BAR_INDEX"))
    }

    @Test
    fun `rejects the OTHER surface as a rule target`() {
        val raw = """
            {
              "version": 2,
              "apps": { "com.instagram.android": { "surfaces": { "OTHER": { "signals": [] } } } }
            }
        """.trimIndent()

        assertTrue(failureMessage(raw).contains("OTHER"))
    }

    @Test
    fun `reads the new signal fields`() {
        val raw = """
            {
              "version": 2,
              "apps": {
                "com.instagram.android": {
                  "surfaces": {
                    "REELS": {
                      "signals": [
                        { "tier": "HIGH", "type": "VIEW_ID", "value": "pager",
                          "requireSelected": false, "requireOnScreen": true,
                          "absentViewIds": ["reply_bar", "sender_name"] }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val result = RuleSetParser.parse(raw)

        assertTrue(result is ParseResult.Success)
        val signal = (result as ParseResult.Success).ruleSet.apps.getValue("com.instagram.android")
            .surfaces.getValue(Surface.REELS).signals.single()
        assertTrue(signal.requireOnScreen)
        assertEquals(listOf("reply_bar", "sender_name"), signal.absentViewIds)
    }

    @Test
    fun `the new fields default to the previous behaviour when omitted`() {
        val raw = """
            {
              "version": 2,
              "apps": {
                "com.instagram.android": {
                  "surfaces": {
                    "REELS": {
                      "signals": [
                        { "tier": "HIGH", "type": "VIEW_ID", "value": "clips_tab" }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val result = RuleSetParser.parse(raw)

        assertTrue(result is ParseResult.Success)
        val signal = (result as ParseResult.Success).ruleSet.apps.getValue("com.instagram.android")
            .surfaces.getValue(Surface.REELS).signals.single()
        assertFalse(signal.requireOnScreen)
        assertTrue(signal.absentViewIds.isEmpty())
    }

    @Test
    fun `a blank guard id is rejected`() {
        // A hand-edited rules file with a stray empty string would otherwise
        // silently match nothing, which reads exactly like a working rule.
        val raw = """
            {
              "version": 2,
              "apps": {
                "com.instagram.android": {
                  "surfaces": {
                    "REELS": {
                      "signals": [
                        { "tier": "HIGH", "type": "VIEW_ID", "value": "pager",
                          "absentViewIds": ["reply_bar", "  "] }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val result = RuleSetParser.parse(raw)

        assertTrue(result is ParseResult.Failure)
    }

    @Test
    fun `a mistyped new field degrades instead of throwing`() {
        // The rules file is hand-edited on the phone to repair detection without
        // recompiling, so the day it is wrong the app must degrade, not crash.
        val raw = """
            {
              "version": 2,
              "apps": {
                "com.instagram.android": {
                  "surfaces": {
                    "REELS": {
                      "signals": [
                        { "tier": "HIGH", "type": "VIEW_ID", "value": "pager",
                          "requireOnScreen": "yes" }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val result = RuleSetParser.parse(raw)

        assertTrue(result is ParseResult.Failure)
    }

    @Test
    fun `reads a surface click target`() {
        val raw = """
            {
              "version": 2,
              "apps": {
                "com.instagram.android": {
                  "surfaces": {
                    "EXPLORE": {
                      "clickViewId": "search_bar",
                      "signals": [
                        { "tier": "HIGH", "type": "VIEW_ID", "value": "search_tab" }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val result = RuleSetParser.parse(raw)

        assertTrue(result is ParseResult.Success)
        val rules = (result as ParseResult.Success).ruleSet.apps.getValue("com.instagram.android")
            .surfaces.getValue(Surface.EXPLORE)
        assertEquals("search_bar", rules.clickViewId)
    }

    @Test
    fun `a surface without a click target keeps the default exit behaviour`() {
        val raw = """
            {
              "version": 2,
              "apps": {
                "com.instagram.android": {
                  "surfaces": {
                    "REELS": {
                      "signals": [
                        { "tier": "HIGH", "type": "VIEW_ID", "value": "clips_tab" }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val result = RuleSetParser.parse(raw)

        assertTrue(result is ParseResult.Success)
        assertNull(
            (result as ParseResult.Success).ruleSet.apps.getValue("com.instagram.android")
                .surfaces.getValue(Surface.REELS).clickViewId,
        )
    }

    private val v2 = """
        {
          "version": 2,
          "apps": {
            "com.instagram.android": {
              "surfaces": {
                "REELS": {
                  "signals": [
                    { "tier": "HIGH", "type": "VIEW_ID", "value": "clips_tab" }
                  ]
                }
              }
            },
            "com.google.android.youtube": {
              "surfaces": {
                "SHORTS": {
                  "signals": [
                    { "tier": "HIGH", "type": "VIEW_ID", "value": "reel_progress_bar",
                      "requireSelected": false, "requireOnScreen": true }
                  ]
                }
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun `reads rules for several apps`() {
        val result = RuleSetParser.parse(v2)

        assertTrue(result is ParseResult.Success)
        val ruleSet = (result as ParseResult.Success).ruleSet
        assertEquals(setOf("com.instagram.android", "com.google.android.youtube"), ruleSet.apps.keys)
        assertEquals(
            setOf(Surface.SHORTS),
            ruleSet.apps.getValue("com.google.android.youtube").surfaces.keys,
        )
    }

    @Test
    fun `rejects the version 1 format instead of silently migrating it`() {
        // A v1 file left in filesDir must read as a clean failure so the loader
        // falls back to the bundled rules and the banner says why. Quietly
        // treating it as "no rules" would block nothing behind a healthy screen.
        val v1 = """
            {
              "version": 1,
              "surfaces": {
                "REELS": {
                  "signals": [
                    { "tier": "HIGH", "type": "VIEW_ID", "value": "clips_tab" }
                  ]
                }
              }
            }
        """.trimIndent()

        val result = RuleSetParser.parse(v1)

        assertTrue(result is ParseResult.Failure)
    }

    @Test
    fun `rejects an unknown surface name`() {
        val raw = """
            {
              "version": 2,
              "apps": {
                "com.instagram.android": {
                  "surfaces": {
                    "TIKTOK": {
                      "signals": [
                        { "tier": "HIGH", "type": "VIEW_ID", "value": "x" }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        assertTrue(RuleSetParser.parse(raw) is ParseResult.Failure)
    }

    @Test
    fun `an empty package name is rejected`() {
        val raw = """
            {
              "version": 2,
              "apps": {
                "": {
                  "surfaces": {
                    "REELS": {
                      "signals": [
                        { "tier": "HIGH", "type": "VIEW_ID", "value": "clips_tab" }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        assertTrue(RuleSetParser.parse(raw) is ParseResult.Failure)
    }
}
