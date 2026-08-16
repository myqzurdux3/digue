package com.insta.detection

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the shipped rules against view trees actually captured from Instagram.
 * These fixtures are the project's ground truth; the synthetic tests in
 * ScreenClassifierTest cover the logic, these cover reality.
 */
class RealFixtureTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { "missing resource $path" }
            .bufferedReader().readText()

    private fun fixture(name: String): ScreenSnapshot =
        json.decodeFromString(resource("/fixtures/$name.json"))

    private val ruleSet: RuleSet =
        when (val result = RuleSetParser.parse(resource("/rules.json"))) {
            is ParseResult.Success -> result.ruleSet
            is ParseResult.Failure -> error("shipped rules are invalid: ${result.message}")
        }

    private val classifier = ScreenClassifier(ruleSet)

    @Test
    fun `shipped rules contain no leftover placeholders`() {
        assertTrue(
            "rules.json still contains a REMPLACER_ placeholder",
            !resource("/rules.json").contains("REMPLACER_"),
        )
    }

    @Test
    fun `feed is not blocked`() {
        assertEquals(Surface.OTHER, classifier.classify(fixture("feed")).surface)
    }

    @Test
    fun `profile is not blocked`() {
        assertEquals(Surface.OTHER, classifier.classify(fixture("profile")).surface)
    }

    @Test
    fun `direct messages are not blocked`() {
        assertEquals(Surface.OTHER, classifier.classify(fixture("direct")).surface)
    }

    @Test
    fun `reels tab is detected at the high tier`() {
        val result = classifier.classify(fixture("reels"))

        assertEquals(Surface.REELS, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `explore tab is detected at the high tier`() {
        val result = classifier.classify(fixture("explore"))

        assertEquals(Surface.EXPLORE, result.surface)
        assertEquals(Tier.HIGH, result.tier)
    }

    /** Proves the fallback works on real trees, not just synthetic ones. */
    @Test
    fun `reels is still detected when view ids are stripped`() {
        val stripped = fixture("reels").let { snapshot ->
            snapshot.copy(nodes = snapshot.nodes.map { it.copy(viewId = null) })
        }

        val result = classifier.classify(stripped)

        assertEquals(Surface.REELS, result.surface)
        assertNotEquals(Tier.HIGH, result.tier)
    }

    @Test
    fun `reels is still detected when view ids and labels are stripped`() {
        val stripped = fixture("reels").let { snapshot ->
            snapshot.copy(
                nodes = snapshot.nodes.map { it.copy(viewId = null, contentDescription = null) },
            )
        }

        val result = classifier.classify(stripped)

        assertEquals(Surface.REELS, result.surface)
        assertEquals(Tier.LOW, result.tier)
    }

    @Test
    fun `stripped feed is still not blocked`() {
        val stripped = fixture("feed").let { snapshot ->
            snapshot.copy(
                nodes = snapshot.nodes.map { it.copy(viewId = null, contentDescription = null) },
            )
        }

        assertEquals(Surface.OTHER, classifier.classify(stripped).surface)
    }
}
