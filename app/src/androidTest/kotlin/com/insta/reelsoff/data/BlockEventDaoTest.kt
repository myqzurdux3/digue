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

    @Test
    fun insertsAndReadsBackInChronologicalOrder() = runBlocking {
        dao.insert(BlockEvent(epochMillis = 300, surface = "REELS", ruleTier = "HIGH"))
        dao.insert(BlockEvent(epochMillis = 100, surface = "EXPLORE", ruleTier = "LOW"))

        val events = dao.since(0)

        assertEquals(listOf(100L, 300L), events.map { it.epochMillis })
        assertEquals("EXPLORE", events[0].surface)
        assertEquals("LOW", events[0].ruleTier)
    }

    @Test
    fun filtersOutEventsBeforeTheCutoff() = runBlocking {
        dao.insert(BlockEvent(epochMillis = 100, surface = "REELS", ruleTier = "HIGH"))
        dao.insert(BlockEvent(epochMillis = 500, surface = "REELS", ruleTier = "HIGH"))

        assertEquals(1, dao.since(200).size)
    }

    @Test
    fun flowEmitsTheCurrentContents() = runBlocking {
        dao.insert(BlockEvent(epochMillis = 100, surface = "REELS", ruleTier = "HIGH"))

        assertEquals(1, dao.observeSince(0).first().size)
    }
}
