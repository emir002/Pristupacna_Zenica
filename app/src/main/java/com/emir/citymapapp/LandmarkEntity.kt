// LandmarkEntity.kt
package com.emir.citymapapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "landmarks")
data class LandmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0, // Auto-generated primary key
    val category: String, // "red", "yellow", "blue"
    val name: String,
    val detailLine1: String,
    val detailLine2: String?,
    val longitude: Double,
    val latitude: Double,
    val imageName: String? // Name of the image (e.g., "landmark_a_image")
)