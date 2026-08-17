package com.insta.reelsoff.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockEventDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: BlockEventDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.blockEventDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // These two used to call a one-shot `dao.since(...)`. That query was removed
    // from BlockEventDao when the screen moved to observing a Flow, and the tests
    // were never updated — so this whole source set stopped compiling, silently,
    // and stayed that way. A `@Test` that cannot be compiled looks exactly like a
    // passing one in a count. Reading the first emission of the Flow asks the same
    // question of the same query without putting a test-only method back into the
    // production DAO.

    @Test
    fun insertsAndReadsBackInChronologicalOrder() = runBlocking {
        dao.insert(BlockEvent(epochMillis = 300, surface = "REELS", ruleTier = "HIGH"))
        dao.insert(BlockEvent(epochMillis = 100, surface = "EXPLORE", ruleTier = "LOW"))

        val events = dao.observeSince(0).first()

        assertEquals(listOf(100L, 300L), events.map { it.epochMillis })
        assertEquals("EXPLORE", events[0].surface)
        assertEquals("LOW", events[0].ruleTier)
    }

    @Test
    fun filtersOutEventsBeforeTheCutoff() = runBlocking {
        dao.insert(BlockEvent(epochMillis = 100, surface = "REELS", ruleTier = "HIGH"))
        dao.insert(BlockEvent(epochMillis = 500, surface = "REELS", ruleTier = "HIGH"))

        assertEquals(1, dao.observeSince(200).first().size)
    }

    @Test
    fun flowEmitsTheCurrentContents() = runBlocking {
        dao.insert(BlockEvent(epochMillis = 100, surface = "REELS", ruleTier = "HIGH"))

        assertEquals(1, dao.observeSince(0).first().size)
    }
}
