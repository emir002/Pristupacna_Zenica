// LandmarkDao.kt
package com.emir.citymapapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LandmarkDao {

    /**
     * Gets all landmarks as a Flow. This is naturally asynchronous and
     * doesn't need 'suspend'. Room handles the background threading for Flows.
     */
    @Query("SELECT * FROM landmarks")
    fun getAllLandmarks(): Flow<List<LandmarkEntity>>

    /**
     * Inserts multiple landmarks.
     * REMOVED 'suspend' to fix Kapt build errors.
     * IMPORTANT: If you call this method later in your code,
     * you MUST ensure it runs on a background thread, for example:
     * withContext(Dispatchers.IO) {
     * dao.insertAll(myLandmarks)
     * }
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(landmarks: List<LandmarkEntity>)

    // --- Removed for now to simplify and fix build errors ---
    // You can add these back later if needed, but without 'suspend'.
    // @Query("SELECT * FROM landmarks WHERE name = :name LIMIT 1")
    // fun getLandmarkByName(name: String): LandmarkEntity?
    //
    // @Query("SELECT COUNT(*) FROM landmarks")
    // fun count(): Int
    // --- End of removed section ---

}