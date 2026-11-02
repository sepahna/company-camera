package ir.mbarani.companycamera

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PhotoDao {
    @Insert suspend fun insert(photo: Photo): Long
    @Query("SELECT * FROM photos ORDER BY createdAt DESC") suspend fun all(): List<Photo>
    @Query("DELETE FROM photos WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM photos") suspend fun clear()
}
