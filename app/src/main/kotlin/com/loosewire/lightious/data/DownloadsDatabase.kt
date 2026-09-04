package com.loosewire.lightious.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "downloads",
    primaryKeys = ["ownerDeviceId", "videoId"],
)
internal data class DownloadEntity(
    val ownerDeviceId: String,
    val videoId: String,
    val title: String,
    val author: String,
    val authorId: String?,
    val lengthSeconds: Long,
    val kind: String,
    val state: String,
    val fileName: String?,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val streamFingerprint: String?,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val errorMessage: String?,
    val updatedAt: Long,
)

@Dao
internal interface DownloadsDao {
    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC")
    suspend fun listAll(): List<DownloadEntity>

    @Query(
        "SELECT * FROM downloads WHERE ownerDeviceId = :ownerDeviceId " +
            "AND videoId = :videoId LIMIT 1",
    )
    suspend fun find(ownerDeviceId: String, videoId: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE ownerDeviceId = :ownerDeviceId AND videoId = :videoId")
    suspend fun delete(ownerDeviceId: String, videoId: String)

    @Query("DELETE FROM downloads WHERE ownerDeviceId = :ownerDeviceId")
    suspend fun deleteForOwner(ownerDeviceId: String)
}

@Database(
    entities = [DownloadEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class DownloadsDatabase : RoomDatabase() {
    internal abstract fun downloadsDao(): DownloadsDao

    companion object {
        const val NAME = "lightious-downloads.db"
    }
}
