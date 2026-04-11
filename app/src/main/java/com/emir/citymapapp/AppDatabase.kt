// AppDatabase.kt
package com.emir.citymapapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

@Database(entities = [LandmarkEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun landmarkDao(): LandmarkDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "citymap_database"
                )
                    .addCallback(PrepopulateCallback(context)) // Add the callback here
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// Callback to pre-populate the database from an SQL asset file
private class PrepopulateCallback(private val context: Context) : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        prepopulateIfEmpty(db)
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        prepopulateIfEmpty(db)
    }

    private fun prepopulateIfEmpty(db: SupportSQLiteDatabase) {
        Executors.newSingleThreadExecutor().execute {
            try {
                val cursor = db.query("SELECT COUNT(*) FROM landmarks")
                val hasLandmarks = cursor.use {
                    it.moveToFirst() && it.getInt(0) > 0
                }
                if (hasLandmarks) return@execute

                val inputStream = context.assets.open("landmarks.sql")
                val reader = BufferedReader(InputStreamReader(inputStream))
                var line: String?

                db.beginTransaction()
                try {
                    while (reader.readLine().also { line = it } != null) {
                        val sqlStatement = line?.trim()
                        if (!sqlStatement.isNullOrEmpty() && !sqlStatement.startsWith("--")) {
                            db.execSQL(sqlStatement)
                        }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                    reader.close()
                    inputStream.close()
                }
            } catch (e: Exception) {
                println("Error pre-populating database: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}
