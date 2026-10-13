package com.ai.android.storage

import android.content.Context
import android.util.Log
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "kv")
data class KvEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface KvDao {
    @Query("SELECT * FROM kv WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): KvEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: KvEntity)

    @Query("SELECT * FROM kv ORDER BY updatedAt DESC")
    suspend fun all(): List<KvEntity>

    @Query("DELETE FROM kv WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("SELECT * FROM kv WHERE `key` LIKE :prefix || '%'")
    suspend fun byPrefix(prefix: String): List<KvEntity>
}

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val payload: String,
    val projectId: String = "",
)

data class ConversationMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val projectId: String,
)

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConversationEntity)

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun all(): List<ConversationEntity>

    @Query("SELECT id, title, createdAt, updatedAt, projectId FROM conversations ORDER BY updatedAt DESC")
    suspend fun allMeta(): List<ConversationMeta>

    @Query("SELECT length(payload) FROM conversations WHERE id = :id")
    suspend fun payloadLength(id: String): Int?

    @Query("SELECT substr(payload, :start, :len) FROM conversations WHERE id = :id")
    suspend fun payloadChunk(id: String, start: Int, len: Int): String?

    @Query("SELECT payload FROM conversations WHERE id = :id")
    suspend fun payload(id: String): String?

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun get(id: String): ConversationEntity?

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)
}

/**
 * 终端会话元信息（PTY 内容不落盘，只在内存）。
 *
 * ⚠️ 不声明 indices —— 与 MIGRATION_3_4 保持"无索引"一致，避免 Room schema 校验失败。
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val convId: String,
    val title: String,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastActiveAt: Long = System.currentTimeMillis(),
)

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: SessionEntity)

    @Query("SELECT * FROM sessions WHERE convId = :convId ORDER BY pinned DESC, createdAt ASC")
    suspend fun listByConv(convId: String): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun get(id: String): SessionEntity?

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM sessions WHERE convId = :convId")
    suspend fun deleteByConv(convId: String)

    @Query("UPDATE sessions SET lastActiveAt = :ts WHERE id = :id")
    suspend fun touch(id: String, ts: Long)

    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("UPDATE sessions SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("DELETE FROM sessions WHERE convId NOT IN (SELECT id FROM conversations)")
    suspend fun purgeOrphans()
}

/** 内存中会话的元信息镜像（供 UI 快速读） */
data class SessionMeta(
    val id: String,
    val convId: String,
    val title: String,
    val pinned: Boolean,
    val createdAt: Long,
    val lastActiveAt: Long,
)

@Database(
    entities = [KvEntity::class, ConversationEntity::class, SessionEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun kvDao(): KvDao
    abstract fun conversationDao(): ConversationDao
    abstract fun sessionDao(): SessionDao

    companion object {
        private const val TAG = "AppDatabase"
        private const val BACKUP_MIN_SIZE = 1_000_000L
        private const val BACKUP_KEEP = 3

        /** v1 → v2：新增 conversations 表 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversations` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `payload` TEXT NOT NULL,
                        `projectId` TEXT NOT NULL DEFAULT '',
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** v2 → v3：仅 Kotlin 字段顺序变化，无 SQL 变更 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // no-op
            }
        }

        /**
         * v3 → v4：新增 sessions 表。
         *
         * ⭐ 关键修复：删除原先的 `CREATE INDEX idx_sessions_convId`。
         *    原因：SessionEntity 未声明 `indices`，Room 期望 sessions 表**没有任何索引**，
         *    多出的索引会导致 schema 校验失败（Migration didn't properly handle: sessions），
         *    进而 Room 抛异常被 App 层吞掉 → UI 显示空白。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sessions` (
                        `id` TEXT NOT NULL,
                        `convId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `pinned` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL,
                        `lastActiveAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v4 → v5：清理因旧 MIGRATION_3_4 遗留的多余索引。
         *
         * 场景：用户已经打开过旧版本 App，Room 执行了 v3→v4 迁移并提交事务（version 已是 4），
         *      但 schema 校验失败抛异常 → 索引留在了 db 里。
         * 该 migration 把它 DROP 掉，让 schema 与 SessionEntity 期望一致。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                runCatching {
                    db.execSQL("DROP INDEX IF EXISTS `idx_sessions_convId`")
                }.onFailure { Log.w(TAG, "drop legacy index failed", it) }
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: run {
                    // ⭐ 启动前自动备份（若 db > 1MB）
                    runCatching { backupIfNeeded(context.applicationContext) }

                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "ai_android.db"
                    )
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                        .build()
                        .also { instance = it }
                }
            }

        /**
         * 启动时自动备份数据库到 /sdcard/AiAndroid/backup/。
         * 只保留最近 3 个备份。
         */
        private fun backupIfNeeded(context: Context) {
            val dbFile = context.getDatabasePath("ai_android.db")
            if (!dbFile.exists() || dbFile.length() < BACKUP_MIN_SIZE) return

            val backupDir = File("/sdcard/AiAndroid/backup").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val backup = File(backupDir, "ai_android_$stamp.db")

            val ok = runCatching {
                dbFile.copyTo(backup, overwrite = false)
                true
            }.getOrDefault(false)
            if (ok) Log.i(TAG, "db backed up: ${backup.absolutePath}（${dbFile.length()} 字节）")

            // 保留最近 3 个
            backupDir.listFiles { f -> f.name.startsWith("ai_android_") && f.name.endsWith(".db") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(BACKUP_KEEP)
                ?.forEach { runCatching { it.delete() } }
        }
    }
}