package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PairedDeviceEntity::class, TransferEntity::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun pairedDeviceDao(): PairedDeviceDao
    abstract fun transferDao(): TransferDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transfer_history` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `sessionId` TEXT NOT NULL DEFAULT '',
                        `fileName` TEXT NOT NULL,
                        `fileExtension` TEXT NOT NULL DEFAULT '',
                        `mimeType` TEXT,
                        `fileSize` INTEGER NOT NULL DEFAULT 0,
                        `senderDevice` TEXT NOT NULL DEFAULT '',
                        `receiverDevice` TEXT NOT NULL DEFAULT '',
                        `direction` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `startTime` INTEGER NOT NULL DEFAULT 0,
                        `completionTime` INTEGER NOT NULL DEFAULT 0,
                        `durationMs` INTEGER NOT NULL DEFAULT 0,
                        `speed` TEXT,
                        `checksum` TEXT,
                        `filePath` TEXT,
                        `peerDeviceName` TEXT NOT NULL DEFAULT '',
                        `peerAddress` TEXT NOT NULL DEFAULT '',
                        `errorMessage` TEXT,
                        `timestamp` INTEGER NOT NULL DEFAULT 0,
                        `relativePath` TEXT,
                        `avgSpeedBytesPerSec` INTEGER NOT NULL DEFAULT 0,
                        `peakSpeedBytesPerSec` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "paired_devices", "model", "TEXT")
                addColumnIfMissing(db, "transfer_history", "sessionId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "transfer_history", "fileExtension", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "transfer_history", "mimeType", "TEXT")
                addColumnIfMissing(db, "transfer_history", "senderDevice", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "transfer_history", "receiverDevice", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "transfer_history", "startTime", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "transfer_history", "completionTime", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "transfer_history", "durationMs", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "transfer_history", "checksum", "TEXT")
                addColumnIfMissing(db, "transfer_history", "filePath", "TEXT")
                addColumnIfMissing(db, "transfer_history", "peerAddress", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "transfer_history", "errorMessage", "TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "paired_devices", "model", "TEXT")
                addColumnIfMissing(db, "paired_devices", "isFavorite", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "paired_devices", "useCount", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "paired_devices", "lastTransferredAt", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "paired_devices", "autoAccept", "INTEGER NOT NULL DEFAULT 0")

                addColumnIfMissing(db, "transfer_history", "relativePath", "TEXT")
                addColumnIfMissing(db, "transfer_history", "avgSpeedBytesPerSec", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "transfer_history", "peakSpeedBytesPerSec", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "paired_devices", "model", "TEXT")
                addColumnIfMissing(db, "transfer_history", "priority", "TEXT NOT NULL DEFAULT 'NORMAL'")
                addColumnIfMissing(db, "transfer_history", "queueOrder", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "transfer_history", "bytesTransferred", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "transfer_history", "isPaused", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "transfer_history", "retryCount", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "transfer_history", "isDirectory", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "transfer_history", "partialChecksum", "TEXT")
                addColumnIfMissing(db, "transfer_history", "sourceUri", "TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "paired_devices", "certFingerprint", "TEXT")
                addColumnIfMissing(db, "paired_devices", "tlsSupported", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6
        )

        private fun addColumnIfMissing(
            db: SupportSQLiteDatabase,
            tableName: String,
            columnName: String,
            columnDefinition: String
        ) {
            val existingColumns = mutableSetOf<String>()
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0) {
                        existingColumns.add(cursor.getString(nameIndex))
                    }
                }
            }
            if (columnName !in existingColumns) {
                db.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $columnDefinition")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "localdrop.db"
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
