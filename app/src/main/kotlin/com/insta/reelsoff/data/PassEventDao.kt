package com.insta.reelsoff.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PassEventDao {

    @Insert
    suspend fun insert(event: PassEvent)

    @Query("SELECT * FROM pass_event WHERE epochMillis >= :sinceMillis ORDER BY epochMillis")
    fun observeSince(sinceMillis: Long): Flow<List<PassEvent>>

    @Query("SELECT * FROM pass_event WHERE epochMillis >= :sinceMillis ORDER BY epochMillis")
    suspend fun since(sinceMillis: Long): List<PassEvent>
}
