package com.insta.reelsoff.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.insta.detection.Surface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RuleSetLoaderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val override = File(context.filesDir, "rules.json")

    @After
    fun tearDown() {
        override.delete()
    }

    // Method names avoid spaces: D8 rejects space characters in simple names
    // below DEX version 040, which requires minSdk 28. This project targets
    // minSdk 26 (see task-4-brief.md), so backtick-with-spaces names as given
    // in the task-8 brief fail dexing on this toolchain. Assertions below are
    // unchanged from the brief; only identifiers were made dex-safe.
    @Test
    fun fallsBackToBundledRulesWhenNoOverrideExists() {
        override.delete()

        val loaded = RuleSetLoader(context).load()

        assertEquals(RuleSource.BUNDLED, loaded.source)
        assertNull(loaded.error)
        val instagramSurfaces = loaded.ruleSet.apps[INSTAGRAM_PACKAGE]?.surfaces
        assertTrue(instagramSurfaces?.containsKey(Surface.REELS) == true)
        assertTrue(instagramSurfaces?.containsKey(Surface.EXPLORE) == true)
    }

    @Test
    fun prefersAValidOverrideFile() {
        override.writeText(
            """
            { "version": 99, "apps": { "$INSTAGRAM_PACKAGE": { "surfaces": { "REELS": { "signals": [
              { "tier": "HIGH", "type": "VIEW_ID", "value": "override-marker" }
            ] } } } } }
            """.trimIndent(),
        )

        val loaded = RuleSetLoader(context).load()

        assertEquals(RuleSource.OVERRIDE, loaded.source)
        assertEquals(99, loaded.ruleSet.version)
    }

    @Test
    fun fallsBackAndReportsTheErrorWhenTheOverrideIsBroken() {
        override.writeText("{ not json at all")

        val loaded = RuleSetLoader(context).load()

        assertEquals(RuleSource.BUNDLED, loaded.source)
        assertNotNull(loaded.error)
        assertTrue(loaded.ruleSet.apps[INSTAGRAM_PACKAGE]?.surfaces?.containsKey(Surface.REELS) == true)
    }

    // Regression test for the Task 8 review finding: a rules.json that exists but
    // cannot be read as a file (here, because it is actually a directory) must not
    // throw out of load() — it must degrade to the bundled rules with an error,
    // the same as any other unreadable override.
    @Test
    fun fallsBackWithoutThrowingWhenTheOverridePathIsUnreadable() {
        override.mkdirs()

        val loaded = RuleSetLoader(context).load()

        assertEquals(RuleSource.BUNDLED, loaded.source)
        assertNotNull(loaded.error)
    }

    private companion object {
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
    }
}
