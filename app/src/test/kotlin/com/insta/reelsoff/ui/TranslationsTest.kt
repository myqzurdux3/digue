package com.insta.reelsoff.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The two languages, kept in step.
 *
 * A string added to one file and forgotten in the other does not fail the build
 * and does not crash the app: it silently falls back, so a French phone shows
 * one English line in the middle of the screen. Lint would catch the missing
 * key; nothing would catch a placeholder that moved from `%1$s` to `%2$s` in one
 * file only, which is a crash on that locale and only on that locale.
 */
class TranslationsTest {

    /**
     * Every translatable value in a folder, keyed by name — a plural once per
     * quantity, as "name/one" and "name/other", so a language that defines only
     * half of one is caught like a missing string.
     */
    private fun stringsIn(folder: String): Map<String, String> {
        val file = File(resDir(), "$folder/strings.xml")
        assertTrue("$file is missing", file.isFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val values = mutableMapOf<String, String>()
        val strings = document.getElementsByTagName("string")
        for (index in 0 until strings.length) {
            val element = strings.item(index) as Element
            values[element.getAttribute("name")] = element.textContent
        }
        val plurals = document.getElementsByTagName("plurals")
        for (index in 0 until plurals.length) {
            val element = plurals.item(index) as Element
            val items = element.getElementsByTagName("item")
            for (itemIndex in 0 until items.length) {
                val item = items.item(itemIndex) as Element
                values["${element.getAttribute("name")}/${item.getAttribute("quantity")}"] = item.textContent
            }
        }
        return values
    }

    /** Every `%1$s`-style placeholder, in the order the framework will bind them. */
    private fun placeholders(value: String): List<String> =
        Regex("""%(\d+\$[a-zA-Z])""").findAll(value).map { it.groupValues[1] }.sorted().toList()

    @Test
    fun `both languages define exactly the same keys`() {
        assertEquals(stringsIn("values").keys.sorted(), stringsIn("values-fr").keys.sorted())
    }

    @Test
    fun `a string takes the same arguments in both languages`() {
        val french = stringsIn("values-fr")
        for ((name, english) in stringsIn("values")) {
            assertEquals(
                "$name binds different arguments in the two languages",
                placeholders(english),
                placeholders(french.getValue(name)),
            )
        }
    }

    @Test
    fun `no string is left untranslated by copy-paste`() {
        // Brand and product names are the same word in both languages on purpose;
        // anything else identical in both files is a translation that never
        // happened.
        val sameInBoth = setOf(
            "app_name", "reels", "explore", "shorts", "spotlight", "discover",
            "app_instagram", "app_youtube", "app_snapchat", "maintenance_title",
            // French does not inflect "fois", so both quantities read alike; the
            // plural exists for English, which inflects "time".
            "today_total/one", "today_total/other",
        )
        val french = stringsIn("values-fr")
        val untranslated = stringsIn("values")
            .filterKeys { it !in sameInBoth }
            .filter { (name, english) -> english == french.getValue(name) }
            .keys
        assertEquals(emptySet<String>(), untranslated)
    }

    @Test
    fun `a plural offers every quantity its language needs`() {
        // Both languages need exactly "one" and "other" — the CLDR rule set for
        // French and English alike. A quantity added for one and not the other
        // is already caught by the key comparison; this catches a plural that
        // was declared with neither.
        for (folder in listOf("values", "values-fr")) {
            val quantities = stringsIn(folder).keys.filter { "/" in it }
                .groupBy({ it.substringBefore("/") }, { it.substringAfter("/") })
            assertTrue("$folder declares no plural at all", quantities.isNotEmpty())
            for ((name, offered) in quantities) {
                assertEquals("$folder/$name", listOf("one", "other"), offered.sorted())
            }
        }
    }

    @Test
    fun `every language offered in the picker has a folder to draw from`() {
        val file = File(resDir(), "xml/locales_config.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("locale")
        val offered = (0 until nodes.length).map {
            (nodes.item(it) as Element).getAttribute("android:name")
        }
        assertEquals(listOf("en", "fr"), offered)
        for (language in offered) {
            // "en" is the default folder, which is what makes it the fallback for
            // every locale that has none of its own.
            val folder = if (language == "en") "values" else "values-$language"
            assertTrue("$folder/strings.xml is missing", File(resDir(), "$folder/strings.xml").isFile)
        }
    }
}

/**
 * Walks up from the working directory, which Gradle sets to the module for a
 * unit test and to the root for an IDE run.
 */
private fun resDir(): File {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
        for (candidate in listOf(File(dir, "src/main/res"), File(dir, "app/src/main/res"))) {
            if (candidate.isDirectory) return candidate
        }
        dir = dir.parentFile
    }
    error("cannot find app/src/main/res from ${File(".").absolutePath}")
}
