package com.tanzaniaprogrammers.qwisha

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity
data class Message(
    @PrimaryKey val id: String,
    val threadId: String,
    val content: String,
    val outgoing: Boolean,
    val replyTo: String?,
    val status: String,
    val timestamp: Long,
    val hasOverlayHeader: Boolean = false  // True if message was sent/received with overlay protocol header
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM Message WHERE threadId = :threadId ORDER BY timestamp ASC")
    fun getMessagesFlow(threadId: String): Flow<List<Message>>

    @Query("SELECT * FROM Message ORDER BY timestamp DESC")
    fun getAllMessagesFlow(): Flow<List<Message>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(msg: Message)

    @Query("UPDATE Message SET content = :content WHERE id = :id")
    suspend fun updateContent(id: String, content: String)

    @Query("UPDATE Message SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE Message SET status = 'read' WHERE threadId = :threadId AND outgoing = 0 AND status != 'read'")
    suspend fun markThreadAsRead(threadId: String)

    @Query("DELETE FROM Message WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("""
        SELECT * FROM Message 
        WHERE content LIKE '%' || :query || '%' OR threadId LIKE '%' || :query || '%'
        ORDER BY timestamp DESC
    """)
    fun searchMessagesFlow(query: String): Flow<List<Message>>
}

@Database(entities = [Message::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Migration from version 1 to 2: add hasOverlayHeader column
        val MIGRATION_1_2 = androidx.room.migration.Migration(1, 2) {
            it.execSQL("ALTER TABLE Message ADD COLUMN hasOverlayHeader INTEGER NOT NULL DEFAULT 0")
        }

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sms_overlay_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

