package io.github.saeeddev94.xray.database

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "links")
data class Link(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    var id: Long = 0L,
    @ColumnInfo(name = "name")
    var name: String = "",
    @ColumnInfo(name = "address")
    var address: String = "",
    @ColumnInfo(name = "type")
    var type: Type = Type.Json,
    @ColumnInfo(name = "is_active")
    var isActive: Boolean = false,
    @ColumnInfo(name = "user_agent")
    var userAgent: String? = null,
    @ColumnInfo(name = "upload", defaultValue = "0")
    var upload: Long = 0L,
    @ColumnInfo(name = "download", defaultValue = "0")
    var download: Long = 0L,
    @ColumnInfo(name = "total", defaultValue = "0")
    var total: Long = 0L,
    @ColumnInfo(name = "expire", defaultValue = "0")
    var expire: Long = 0L,
    @ColumnInfo(name = "announcement")
    var announcement: String? = null,
    @ColumnInfo(name = "site_url")
    var siteUrl: String? = null,
    @ColumnInfo(name = "support_url")
    var supportUrl: String? = null,
) : Parcelable {
    enum class Type(val value: Int) {
        Json(0),
        Subscription(1);

        class Convertor {
            @TypeConverter
            fun fromType(type: Type): Int = type.value

            @TypeConverter
            fun toType(value: Int): Type = entries[value]
        }
    }
}
