package com.wifi.toolbox.utils

import android.content.Context
import androidx.room.*
import com.wifi.toolbox.structs.PojieHistoryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@Entity(tableName = "history_metadata")
data class HistoryEntity(
    @PrimaryKey val ssid: String,
    val progress: Int,
    val successfulPassword: String?,
    val lasttime: Long
)

@Entity(
    tableName = "password_items",
    foreignKeys = [ForeignKey(
        entity = HistoryEntity::class,
        parentColumns = ["ssid"],
        childColumns = ["historyId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("historyId")]
)
data class PasswordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val historyId: String,
    val password: String
)

data class HistoryWithPasswords(
    @Embedded val history: HistoryEntity,
    @Relation(
        parentColumn = "ssid",
        entityColumn = "historyId"
    )
    val passwords: List<PasswordEntity>
)

@Dao
interface PojieDao {
    @Transaction
    @Query("SELECT * FROM history_metadata ORDER BY lasttime DESC")
    fun getAllHistoryFlow(): Flow<List<HistoryWithPasswords>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMetadata(history: HistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPasswords(passwords: List<PasswordEntity>)

    @Query("DELETE FROM password_items WHERE historyId = :ssid")
    suspend fun deleteOldPasswords(ssid: String)

    @Transaction
    suspend fun fullUpsert(item: PojieHistoryItem) {
        insertMetadata(HistoryEntity(item.ssid, item.progress, item.password, item.lasttime))
        deleteOldPasswords(item.ssid)
        val entities = item.passwords.map { PasswordEntity(historyId = item.ssid, password = it) }
        insertPasswords(entities)
    }

    @Query("UPDATE history_metadata SET progress = :progress, lasttime = :time WHERE ssid = :ssid")
    suspend fun updateProgress(ssid: String, progress: Int, time: Long)

    @Query("SELECT successfulPassword FROM history_metadata WHERE ssid = :ssid")
    suspend fun getSuccessfulPassword(ssid: String): String?

    @Query("SELECT password FROM password_items WHERE historyId = :ssid ORDER BY id")
    suspend fun getPasswords(ssid: String): List<String>

    /**
     * Writes the full password list while keeping the discovered password
     */
    @Transaction
    suspend fun snapshotUpsert(item: PojieHistoryItem) {
        val existingPassword = getSuccessfulPassword(item.ssid)
        insertMetadata(
            HistoryEntity(
                item.ssid,
                item.progress,
                item.password ?: existingPassword,
                item.lasttime
            )
        )
        deleteOldPasswords(item.ssid)
        insertPasswords(item.passwords.map { PasswordEntity(historyId = item.ssid, password = it) })
    }

    @Query("UPDATE history_metadata SET successfulPassword = :password, lasttime = :time WHERE ssid = :ssid")
    suspend fun updateSuccessfulPassword(ssid: String, password: String, time: Long): Int

    /** Records an offline success without touching the stored password list or progress. */
    @Transaction
    suspend fun recordSuccess(ssid: String, password: String, time: Long) {
        if (updateSuccessfulPassword(ssid, password, time) == 0) {
            insertMetadata(HistoryEntity(ssid, 0, password, time))
        }
    }

    @Query("DELETE FROM history_metadata WHERE ssid = :ssid")
    suspend fun deleteHistory(ssid: String)
}

// --- Database ---

@Database(entities = [HistoryEntity::class, PasswordEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pojieDao(): PojieDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pojie_history.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// --- Manager ---

class PojieHistoryManager(context: Context) {
    private val dao = AppDatabase.getInstance(context).pojieDao()
    private val scope = CoroutineScope(Dispatchers.IO)

    val historyFlow: StateFlow<List<PojieHistoryItem>> = dao.getAllHistoryFlow()
        .map { list ->
            list.map {
                PojieHistoryItem(
                    it.history.ssid,
                    it.passwords.map { p -> p.password },
                    it.history.progress,
                    it.history.successfulPassword,
                    it.history.lasttime
                )
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun addOrUpdateHistory(item: PojieHistoryItem) {
        scope.launch {
            val itemWithTime = item.copy(lasttime = System.currentTimeMillis())
            dao.fullUpsert(itemWithTime)
        }
    }

    /**
     * The full password list is written only at task start/end, so we avoid
     * rewriting it on every attempt (O(n) -> O(1)).
     */
    fun saveSnapshot(item: PojieHistoryItem) {
        scope.launch {
            val itemWithTime = item.copy(lasttime = System.currentTimeMillis())
            dao.snapshotUpsert(itemWithTime)
        }
    }

    /**
     * After an attempt only the progress is updated, never the password list
     */
    fun updateProgress(ssid: String, progress: Int) {
        scope.launch {
            dao.updateProgress(ssid, progress, System.currentTimeMillis())
        }
    }

    fun deleteHistory(ssid: String) {
        scope.launch {
            dao.deleteHistory(ssid)
        }
    }

    /** Records a password found by the offline auditor without deleting stored progress. */
    fun recordSuccess(ssid: String, password: String) {
        scope.launch {
            dao.recordSuccess(ssid, password, System.currentTimeMillis())
        }
    }

    /**
     * Loads the stored password list for an SSID (used to restore killed tasks)
     */
    suspend fun loadPasswords(ssid: String): List<String>? =
        dao.getPasswords(ssid).takeIf { it.isNotEmpty() }
}