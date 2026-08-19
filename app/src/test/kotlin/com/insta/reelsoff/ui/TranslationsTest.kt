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
        // Compared on the bare names: the quantities a plural needs are a property
        // of the language, not of the string, and French needs one English does
        // not. Those are checked on their own below.
        fun names(folder: String) = stringsIn(folder).keys.map { it.substringBefore("/") }.distinct().sorted()

        assertEquals(names("values"), names("values-fr"))
    }

    @Test
    fun `a string takes the same arguments in both languages`() {
        // A plural quantity that exists on one side only is compared against its
        // "other" form, which is what Android falls back to anyway.
        val french = stringsIn("values-fr")
        for ((key, english) in stringsIn("values")) {
            val counterpart = french[key] ?: french.getValue(key.substringBefore("/") + "/other")
            assertEquals(
                "$key binds different arguments in the two languages",
                placeholders(english),
                placeholders(counterpart),
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
            // French does not inflect "fois", so all its quantities read alike;
            // the plural exists for English, which inflects "time".
            "today_total/one", "today_total/other", "today_total/many",
        )
        val french = stringsIn("values-fr")
        val untranslated = stringsIn("values")
            .filterKeys { it !in sameInBoth }
            .filter { (key, english) -> english == french[key] }
            .keys
        assertEquals(emptySet<String>(), untranslated)
    }

    @Test
    fun `a plural offers every quantity its language needs`() {
        // The quantities are CLDR's, not ours. English inflects on one and other;
        // French adds "many", which is what it uses from a million upward — the
        // form that takes "de", as in "1000000 d'instantanés". None of these
        // counters will ever get there, but a resource missing a quantity its
        // language defines is incomplete, and lint says so.
        val needed = mapOf(
            "values" to listOf("one", "other"),
            "values-fr" to listOf("many", "one", "other"),
        )
        for ((folder, quantities) in needed) {
            val declared = stringsIn(folder).keys.filter { "/" in it }
                .groupBy({ it.substringBefore("/") }, { it.substringAfter("/") })
            assertTrue("$folder declares no plural at all", declared.isNotEmpty())
            for ((name, offered) in declared) {
                assertEquals("$folder/$name", quantities, offered.sorted())
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
