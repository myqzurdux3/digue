package com.insta.reelsoff.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the hand-written migration against Room's own record of the schema.
 *
 * Room compares a database it opens with the schema it generated at build time,
 * and throws if they disagree by so much as a keyword. Normally that is only
 * discovered on a device, by an upgrade that fails on a user's phone with their
 * history in it. `app/schemas/<db>/2.json` holds exactly what Room will demand,
 * so the same failure is reachable here, on the JVM, in milliseconds.
 *
 * If this test fails after a schema change, the fix is to copy the statement out
 * of the JSON — the JSON is the authority, not the migration.
 */
class MigrationSchemaTest {

    private fun schemaFile(): File {
        // Gradle runs unit tests with the module directory as the working
        // directory, but that is a convention rather than a guarantee, so the
        // parent is tried too rather than failing with a confusing "no such file".
        val candidates = listOf(
            File("schemas/com.insta.reelsoff.data.AppDatabase/2.json"),
            File("app/schemas/com.insta.reelsoff.data.AppDatabase/2.json"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("schéma Room introuvable, cherché dans ${candidates.map { it.absolutePath }}")
    }

    @Test
    fun `the migration creates exactly the table Room expects`() {
        val json = Json.parseToJsonElement(schemaFile().readText()).jsonObject
        val entities = json["database"]!!.jsonObject["entities"]!!.jsonArray
        val passEvent = entities.single {
            it.jsonObject["tableName"]!!.jsonPrimitive.content == "pass_event"
        }
        val expected = passEvent.jsonObject["createSql"]!!.jsonPrimitive.content
            .replace("\${TABLE_NAME}", "pass_event")

        assertEquals(expected, CREATE_PASS_EVENT)
    }

    @Test
    fun `the exported schema is the version the migration upgrades to`() {
        val json = Json.parseToJsonElement(schemaFile().readText()).jsonObject
        val version = json["database"]!!.jsonObject["version"]!!.jsonPrimitive.content

        assertEquals("2", version)
    }

    @Test
    fun `the upgrade keeps the block history`() {
        // The whole reason this migration is written by hand rather than left to
        // fallbackToDestructiveMigration: block_event is the only record of whether
        // the app has ever worked, and dropping it would empty the chart silently.
        val json = Json.parseToJsonElement(schemaFile().readText()).jsonObject
        val tables = json["database"]!!.jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject["tableName"]!!.jsonPrimitive.content }

        assertTrue("block_event" in tables)
        assertTrue("pass_event" in tables)
        // And the migration must not touch it.
        assertTrue("block_event" !in CREATE_PASS_EVENT)
        assertTrue("DROP" !in CREATE_PASS_EVENT.uppercase())
    }
}
