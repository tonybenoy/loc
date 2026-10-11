package io.github.tonybenoy.loc

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/** Raw sample. Never modified or deleted by later processing. */
@Entity(tableName = "raw_point", indices = [Index("timestamp")])
data class RawPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Float?,
    val speed: Float?,
    val altitude: Double?,
    val source: String,
    /** MOVING / STILL / UNKNOWN, as seen by the collector when the fix arrived. */
    val motion: String,
)

@Dao
interface PointDao {
    @Insert
    fun insert(p: RawPoint)

    @Query("SELECT COUNT(*) FROM raw_point")
    fun count(): Int

    @Query("SELECT COUNT(*) FROM raw_point WHERE timestamp >= :since")
    fun countSince(since: Long): Int

    @Query("SELECT * FROM raw_point ORDER BY timestamp DESC LIMIT 1")
    fun latest(): RawPoint?

    @Query("SELECT * FROM raw_point WHERE timestamp >= :since ORDER BY timestamp ASC")
    fun since(since: Long): List<RawPoint>
}

@Database(entities = [RawPoint::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun points(): PointDao

    companion object {
        @Volatile private var instance: AppDb? = null

        fun get(context: Context): AppDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "loc.db")
                    .build().also { instance = it }
            }
    }
}
