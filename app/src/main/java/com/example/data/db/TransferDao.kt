package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {

    @Query("SELECT * FROM transfer_history ORDER BY timestamp DESC")
    fun getAll(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfer_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TransferEntity?

    @Query("SELECT * FROM transfer_history WHERE fileName LIKE '%' || :query || '%' OR senderDevice LIKE '%' || :query || '%' OR receiverDevice LIKE '%' || :query || '%' OR fileExtension LIKE '%' || :query || '%' OR peerDeviceName LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun search(query: String): Flow<List<TransferEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TransferEntity)

    @Update
    suspend fun update(entity: TransferEntity)

    @Query("DELETE FROM transfer_history WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM transfer_history WHERE status = 'COMPLETED'")
    suspend fun deleteCompleted()

    @Query("DELETE FROM transfer_history")
    suspend fun deleteAll()
}
