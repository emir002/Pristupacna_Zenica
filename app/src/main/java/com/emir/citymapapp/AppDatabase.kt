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

@Database(entities = [LandmarkEntity::class], version = 1, exportSchema = false)
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
        // Use a background thread for pre-population
        Executors.newSingleThreadExecutor().execute {
            try {
                // Open the SQL file from assets
                val inputStream = context.assets.open("landmarks.sql")
                val reader = BufferedReader(InputStreamReader(inputStream))
                var line: String?

                db.beginTransaction()
                try {
                    while (reader.readLine().also { line = it } != null) {
                        val sqlStatement = line?.trim()
                        if (!sqlStatement.isNullOrEmpty() && !sqlStatement.startsWith("--")) {
                            // Only execute if it's not empty and not a comment
                            db.execSQL(sqlStatement)
                        }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }

                reader.close()
                inputStream.close()
                println("Database pre-populated successfully!")
            } catch (e: Exception) {
                println("Error pre-populating database: ${e.message}")
                e.printStackTrace()
                // Handle error: Maybe log it or throw a custom exception
            }
        }
    }
}
