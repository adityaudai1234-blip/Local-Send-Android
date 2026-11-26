package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PairedDeviceDao {

    @Query("SELECT * FROM paired_devices ORDER BY isFavorite DESC, lastConnectedAt DESC")
    fun getAll(): Flow<List<PairedDeviceEntity>>

    @Query("SELECT * FROM paired_devices WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PairedDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(device: PairedDeviceEntity)

    @Query("DELETE FROM paired_devices WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE paired_devices SET lastConnectedAt = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: String, timestamp: Long)

    @Query("UPDATE paired_devices SET alias = :newAlias WHERE id = :id")
    suspend fun renameDevice(id: String, newAlias: String)

    @Query("UPDATE paired_devices SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE paired_devices SET autoAccept = :autoAccept WHERE id = :id")
    suspend fun setAutoAccept(id: String, autoAccept: Boolean)

    @Query("UPDATE paired_devices SET useCount = useCount + 1, lastTransferredAt = :timestamp WHERE id = :id")
    suspend fun incrementUsage(id: String, timestamp: Long)

    @Query("UPDATE paired_devices SET ipAddress = :ipAddress, port = :port, lastConnectedAt = :timestamp WHERE id = :id")
    suspend fun updateNetworkEndpoint(id: String, ipAddress: String, port: Int, timestamp: Long)

    @Query("UPDATE paired_devices SET certFingerprint = :certFingerprint, tlsSupported = :tlsSupported WHERE id = :id")
    suspend fun updateTlsInfo(id: String, certFingerprint: String?, tlsSupported: Boolean)

    @Query("DELETE FROM paired_devices")
    suspend fun deleteAll()
}
