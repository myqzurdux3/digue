package com.insta.reelsoff.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One blocking episode — not one back press. A burst of back presses on a
 * single attempt is one row, so the log measures the user's reflex rather than
 * the state machine's behaviour.
 *
 * [ruleTier] is stored so degraded detection is visible from the log alone.
 */
@Entity(tableName = "block_event")
data class BlockEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochMillis: Long,
    val surface: String,
    val ruleTier: String,
)
