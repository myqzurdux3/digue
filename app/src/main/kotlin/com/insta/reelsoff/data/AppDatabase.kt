package com.insta.reelsoff.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The statement [AppDatabase.MIGRATION_1_2] runs, kept out here so a JVM test can
 * compare it against `app/schemas/…/2.json` — Room's own record of the schema it
 * will demand. Without that comparison the migration would only be falsifiable on
 * a device, by an upgrade that throws.
 */
internal const val CREATE_PASS_EVENT: String =
    "CREATE TABLE IF NOT EXISTS `pass_event` " +
        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`epochMillis` INTEGER NOT NULL, " +
        "`durationMillis` INTEGER NOT NULL)"

@Database(entities = [BlockEvent::class, PassEvent::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun blockEventDao(): BlockEventDao

    abstract fun passEventDao(): PassEventDao

    companion object {

        /**
         * Adds `pass_event`, and touches nothing else.
         *
         * Explicitly **not** `fallbackToDestructiveMigration()`: that would drop
         * `block_event` on upgrade, and the block history is the only record of
         * whether the app has ever worked. A migration that fails loudly beats one
         * that quietly empties the chart.
         *
         * The statement must match, character for character, what Room expects for
         * [PassEvent] — otherwise Room's identity check throws the first time it
         * opens an upgraded database. The authority is `app/schemas/…/2.json`,
         * generated at build time by the exportSchema above; diff against it rather
         * than trusting this by eye.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_PASS_EVENT)
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "reelsoff.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
