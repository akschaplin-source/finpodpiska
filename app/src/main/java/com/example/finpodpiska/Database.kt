package com.example.finpodpiska

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.*

enum class Category(val emoji: String, val displayName: String, val color: Int) {
    VIDEO("🎬", "Видео", 0xFFE53935.toInt()),
    MUSIC("🎵", "Музыка", 0xFF8E24AA.toInt()),
    GAMES("🎮", "Игры", 0xFF43A047.toInt()),
    SOFTWARE("💻", "Софт", 0xFF1E88E5.toInt()),
    EDUCATION("📚", "Образование", 0xFFFB8C00.toInt()),
    NEWS("📰", "Новости", 0xFF6D4C41.toInt()),
    CLOUD("☁️", "Облако", 0xFF00ACC1.toInt()),
    FITNESS("💪", "Спорт", 0xFFD81B60.toInt()),
    OTHER("📦", "Другое", 0xFF757575.toInt());
    companion object {
        fun fromName(n: String) = values().firstOrNull { it.name == n } ?: OTHER
        fun detect(text: String): Category {
            val t = text.lowercase()
            return when {
                listOf("youtube","netflix","кинопоиск","ivi","okko").any { t.contains(it) } -> VIDEO
                listOf("spotify","яндекс музыка","apple music","zvuk").any { t.contains(it) } -> MUSIC
                listOf("game pass","playstation","steam","xbox").any { t.contains(it) } -> GAMES
                listOf("adobe","office","figma","notion").any { t.contains(it) } -> SOFTWARE
                else -> OTHER
            }
        }
    }
}

enum class Currency(val symbol: String, val code: String) {
    RUB("₽", "RUB"), USD("$", "USD"), EUR("€", "EUR");
    companion object { fun fromCode(c: String) = values().firstOrNull { it.code == c } ?: RUB }
}

enum class Period(val displayName: String, val months: Double) {
    WEEK("нед", 0.23), MONTH("мес", 1.0), QUARTER("кварт", 3.0), YEAR("год", 12.0)
}

enum class Status(val displayName: String, val emoji: String) {
    ACTIVE("Активна", "✅"), CANCELLED("Отменена", "❌"), PAUSED("Пауза", "⏸️")
}

enum class Priority(val displayName: String, val emoji: String) {
    MUST_HAVE("Обязательно", "🔴"), NICE_TO_HAVE("Желательно", "🟡"), CANCELABLE("Можно отменить", "🟢")
}

@Entity(tableName = "subscriptions")
data class Sub(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amount: Double,
    val currency: String = "RUB",
    val period: String = "MONTH",
    val card: String,
    val startDate: Long,
    val renewal: Long,
    val category: String = "OTHER",
    val status: String = "ACTIVE",
    val priority: String = "NICE_TO_HAVE",
    val url: String = "",
    val notes: String = "",
    val autoRenew: Boolean = true
) {
    fun monthlyCost(): Double = amount / Period.valueOf(period).months
    fun yearlyCost(): Double = monthlyCost() * 12.0
    fun daysLeft(): Long = (renewal - System.currentTimeMillis()) / 86400000L
    fun cat(): Category = Category.fromName(category)
    fun cur(): Currency = Currency.fromCode(currency)
    fun per(): Period = Period.valueOf(period)
    fun stat(): Status = Status.valueOf(status)
    fun prio(): Priority = Priority.valueOf(priority)
}

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val card: String,
    val merchant: String,
    val date: Long,
    val category: String = "OTHER"
)

@Dao
interface SubDao {
    @Insert suspend fun insert(s: Sub)
    @Update suspend fun update(s: Sub)
    @Delete suspend fun delete(s: Sub)
    @Query("SELECT * FROM subscriptions ORDER BY renewal ASC") fun all(): LiveData<List<Sub>>
    @Query("SELECT * FROM subscriptions WHERE status = :status ORDER BY renewal DESC") fun byStatus(status: String): LiveData<List<Sub>>
    @Query("SELECT SUM(amount / CASE period WHEN 'WEEK' THEN 0.23 WHEN 'MONTH' THEN 1.0 WHEN 'QUARTER' THEN 3.0 WHEN 'YEAR' THEN 12.0 END) FROM subscriptions WHERE status = 'ACTIVE'") fun totalMonthly(): LiveData<Double?>
    @Query("SELECT SUM(amount / CASE period WHEN 'WEEK' THEN 0.23 WHEN 'MONTH' THEN 1.0 WHEN 'QUARTER' THEN 3.0 WHEN 'YEAR' THEN 12.0 END) * 12 FROM subscriptions WHERE status = 'ACTIVE'") fun totalYearly(): LiveData<Double?>
    @Query("SELECT COUNT(*) FROM subscriptions WHERE status = 'ACTIVE'") fun activeCount(): LiveData<Int>
    @Query("SELECT * FROM subscriptions WHERE status = 'ACTIVE' AND renewal BETWEEN :from AND :to ORDER BY renewal") fun upcoming(from: Long, to: Long): LiveData<List<Sub>>
    @Query("SELECT category, SUM(amount / CASE period WHEN 'WEEK' THEN 0.23 WHEN 'MONTH' THEN 1.0 WHEN 'QUARTER' THEN 3.0 WHEN 'YEAR' THEN 12.0 END) as total FROM subscriptions WHERE status = 'ACTIVE' GROUP BY category") fun byCategory(): LiveData<List<CatTotal>>
    @Query("SELECT currency, SUM(amount / CASE period WHEN 'WEEK' THEN 0.23 WHEN 'MONTH' THEN 1.0 WHEN 'QUARTER' THEN 3.0 WHEN 'YEAR' THEN 12.0 END) as total FROM subscriptions WHERE status = 'ACTIVE' GROUP BY currency") fun byCurrency(): LiveData<List<CurrTotal>>
    @Query("SELECT SUM(amount / CASE period WHEN 'WEEK' THEN 0.23 WHEN 'MONTH' THEN 1.0 WHEN 'QUARTER' THEN 3.0 WHEN 'YEAR' THEN 12.0 END) FROM subscriptions WHERE status = 'ACTIVE' AND priority = 'CANCELABLE'") fun savingsPotential(): LiveData<Double?>
    data class CatTotal(val category: String, val total: Double)
    data class CurrTotal(val currency: String, val total: Double)
}

@Dao
interface ExpenseDao {
    @Insert suspend fun insert(e: Expense)
    @Delete suspend fun delete(e: Expense)
    @Query("SELECT * FROM expenses ORDER BY date DESC") fun all(): LiveData<List<Expense>>
    @Query("SELECT card, SUM(amount) as total FROM expenses GROUP BY card") fun byCard(): LiveData<List<CardTotal>>
    data class CardTotal(val card: String, val total: Double)
}

@Database(entities = [Sub::class, Expense::class], version = 1, exportSchema = false)
abstract class DB : RoomDatabase() {
    abstract fun subDao(): SubDao
    abstract fun expDao(): ExpenseDao
    companion object {
        @Volatile private var I: DB? = null
        fun get(c: Context) = I ?: synchronized(this) {
            Room.databaseBuilder(c, DB::class.java, "fin_db").build().also { I = it }
        }
    }
}
