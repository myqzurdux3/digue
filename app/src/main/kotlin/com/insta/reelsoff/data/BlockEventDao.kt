package com.insta.reelsoff.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockEventDao {

    @Insert
    suspend fun insert(event: BlockEvent)

    @Query("SELECT * FROM block_event WHERE epochMillis >= :sinceMillis ORDER BY epochMillis")
    fun observeSince(sinceMillis: Long): Flow<List<BlockEvent>>

    @Query("SELECT * FROM block_event WHERE epochMillis >= :sinceMillis ORDER BY epochMillis")
    suspend fun since(sinceMillis: Long): List<BlockEvent>
}
