package ir.mbarani.companycamera

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "photos")
data class Photo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val project: String,
    val description: String,
    val relativePath: String,
    val createdAt: Long
)
