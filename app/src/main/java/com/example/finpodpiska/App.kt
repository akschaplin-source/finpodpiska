package com.example.finpodpiska

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.room.*
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.utils.ColorTemplate
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ===== ENUMS =====

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

// ===== MODELS =====

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

// ===== DAO =====

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

// ===== DATABASE =====

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

// ===== OCR (без .await()) =====

object OcrHelper {
    private val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    suspend fun scan(ctx: Context, uri: Uri): String = suspendCancellableCoroutine { cont ->
        try {
            val image = InputImage.fromFilePath(ctx, uri)
            rec.process(image)
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result.text)
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    data class Parsed(val name: String, val amount: Double?, val card: String?, val date: Long?, val category: String, val currency: String, val period: String)

    fun parse(text: String): Parsed {
        val lower = text.lowercase()
        val amount = Regex("""(\d+[.,]?\d*)\s*(?:₽|руб|RUB)""").find(text)?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull()
            ?: Regex("""\$\s*(\d+[.,]?\d*)""").find(text)?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull()
        val card = Regex("""\*{0,4}(\d{4})""").find(text)?.groupValues?.get(1)
        val date = Regex("""(\d{2})[./-](\d{2})[./-](\d{4})""").find(text)?.let {
            Calendar.getInstance().apply { set(it.groupValues[3].toInt(), it.groupValues[2].toInt() - 1, it.groupValues[1].toInt()) }.timeInMillis
        }
        val currency = when {
            Regex("""\$\s*\d|usd""").containsMatchIn(lower) -> "USD"
            Regex("""€\s*\d|eur""").containsMatchIn(lower) -> "EUR"
            else -> "RUB"
        }
        val period = when {
            Regex("""год|year|12\s*мес""").containsMatchIn(lower) -> "YEAR"
            Regex("""квартал|quarter""").containsMatchIn(lower) -> "QUARTER"
            Regex("""недел|week""").containsMatchIn(lower) -> "WEEK"
            else -> "MONTH"
        }
        val cat = Category.detect(text)
        val name = listOf("Netflix", "Spotify", "YouTube", "Яндекс Музыка", "Кинопоиск", "Figma", "Adobe", "Office", "Game Pass", "iCloud", "Google One")
            .firstOrNull { text.lowercase().contains(it.lowercase()) } ?: "${cat.emoji} Подписка"
        return Parsed(name, amount, card, date, cat.name, currency, period)
    }
}

// ===== MAIN ACTIVITY =====

class MainActivity : AppCompatActivity() {
    private val db by lazy { DB.get(this) }
    private val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    private lateinit var screenHome: LinearLayout
    private lateinit var screenSubs: LinearLayout
    private lateinit var screenExp: LinearLayout
    private lateinit var screenStats: LinearLayout
    private lateinit var tabHome: TextView
    private lateinit var tabSubs: TextView
    private lateinit var tabExp: TextView
    private lateinit var tabStats: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvYearly: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvSavings: TextView
    private lateinit var recyclerUpcoming: RecyclerView
    private lateinit var tvNoUpcoming: TextView
    private lateinit var recyclerSubs: RecyclerView
    private lateinit var recyclerExp: RecyclerView
    private lateinit var chartSubCats: PieChart
    private lateinit var chartSubCurr: BarChart
    private lateinit var chartExpCards: BarChart
    private lateinit var fabAdd: FloatingActionButton
    private lateinit var fabScan: FloatingActionButton

    private val subAdapter = SubAdapter(onEdit = { sub: Sub -> showSubDialog(sub) }, onDelete = { sub: Sub -> deleteSub(sub) })
    private val expAdapter = ExpAdapter(onDelete = { exp: Expense -> deleteExpense(exp) })

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> uri?.let { processImage(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        screenHome = findViewById(R.id.screen_home)
        screenSubs = findViewById(R.id.screen_subs)
        screenExp = findViewById(R.id.screen_exp)
        screenStats = findViewById(R.id.screen_stats)
        tabHome = findViewById(R.id.tab_home)
        tabSubs = findViewById(R.id.tab_subs)
        tabExp = findViewById(R.id.tab_exp)
        tabStats = findViewById(R.id.tab_stats)
        tvTotal = findViewById(R.id.tv_total)
        tvYearly = findViewById(R.id.tv_yearly)
        tvCount = findViewById(R.id.tv_count)
        tvSavings = findViewById(R.id.tv_savings)
        recyclerUpcoming = findViewById(R.id.recycler_upcoming)
        tvNoUpcoming = findViewById(R.id.tv_no_upcoming)
        recyclerSubs = findViewById(R.id.recycler_subs)
        recyclerExp = findViewById(R.id.recycler_exp)
        chartSubCats = findViewById(R.id.chart_sub_cats)
        chartSubCurr = findViewById(R.id.chart_sub_curr)
        chartExpCards = findViewById(R.id.chart_exp_cards)
        fabAdd = findViewById(R.id.fab_add)
        fabScan = findViewById(R.id.fab_scan)

        createNotificationChannel()

        tabHome.setOnClickListener { switchTab(0) }
        tabSubs.setOnClickListener { switchTab(1) }
        tabExp.setOnClickListener { switchTab(2) }
        tabStats.setOnClickListener { switchTab(3) }

        db.subDao().totalMonthly().observe(this) { total -> tvTotal.text = "${"%.0f".format(total ?: 0.0)} ₽/мес" }
        db.subDao().totalYearly().observe(this) { total -> tvYearly.text = "${"%.0f".format(total ?: 0.0)} ₽/год" }
        db.subDao().activeCount().observe(this) { count -> tvCount.text = "$count активных" }
        db.subDao().savingsPotential().observe(this) { savings ->
            val s = savings ?: 0.0
            tvSavings.text = "💡 Можно сэкономить ${"%.0f".format(s)} ₽/мес"
            tvSavings.visibility = if (s > 0) View.VISIBLE else View.GONE
        }

        val upcomingAdapter = SubAdapter(null, null)
        recyclerUpcoming.layoutManager = LinearLayoutManager(this)
        recyclerUpcoming.adapter = upcomingAdapter
        val now = System.currentTimeMillis()
        db.subDao().upcoming(now, now + 7L * 86400000L).observe(this) { list ->
            upcomingAdapter.submitList(list)
            tvNoUpcoming.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }

        recyclerSubs.layoutManager = LinearLayoutManager(this)
        recyclerSubs.adapter = subAdapter
        db.subDao().all().observe(this) { list -> subAdapter.submitList(list) }

        findViewById<Button>(R.id.chip_all).setOnClickListener { db.subDao().all().observe(this) { list -> subAdapter.submitList(list) } }
        findViewById<Button>(R.id.chip_active).setOnClickListener { db.subDao().byStatus("ACTIVE").observe(this) { list -> subAdapter.submitList(list) } }
        findViewById<Button>(R.id.chip_cancelled).setOnClickListener { db.subDao().byStatus("CANCELLED").observe(this) { list -> subAdapter.submitList(list) } }

        recyclerExp.layoutManager = LinearLayoutManager(this)
        recyclerExp.adapter = expAdapter
        db.expDao().all().observe(this) { list -> expAdapter.submitList(list) }

        setupStats()

        fabAdd.setOnClickListener { showSubDialog(null) }
        fabScan.setOnClickListener { pickImage.launch("image/*") }

        switchTab(0)
    }

    private fun deleteExpense(exp: Expense) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.expDao().delete(exp)
        }
    }

    private fun switchTab(idx: Int) {
        screenHome.visibility = if (idx == 0) View.VISIBLE else View.GONE
        screenSubs.visibility = if (idx == 1) View.VISIBLE else View.GONE
        screenExp.visibility = if (idx == 2) View.VISIBLE else View.GONE
        screenStats.visibility = if (idx == 3) View.VISIBLE else View.GONE
        tabHome.alpha = if (idx == 0) 1f else 0.5f
        tabSubs.alpha = if (idx == 1) 1f else 0.5f
        tabExp.alpha = if (idx == 2) 1f else 0.5f
        tabStats.alpha = if (idx == 3) 1f else 0.5f
    }

    private fun setupStats() {
        db.subDao().byCategory().observe(this) { data ->
            val entries = data.map { PieEntry(it.total.toFloat(), Category.fromName(it.category).emoji) }
            val set = PieDataSet(entries, "Категории").apply { colors = data.map { Category.fromName(it.category).color } }
            chartSubCats.data = PieData(set)
            chartSubCats.invalidate()
        }
        db.subDao().byCurrency().observe(this) { data ->
            val entries = data.map { BarEntry(0f, it.total.toFloat()) }
            val set = BarDataSet(entries, "Валюты").apply { colors = ColorTemplate.MATERIAL_COLORS.toList() }
            chartSubCurr.data = BarData(set)
            chartSubCurr.invalidate()
        }
        db.expDao().byCard().observe(this) { data ->
            val entries = data.map { BarEntry(0f, it.total.toFloat()) }
            val set = BarDataSet(entries, "Картам").apply { colors = ColorTemplate.MATERIAL_COLORS.toList() }
            chartExpCards.data = BarData(set)
            chartExpCards.invalidate()
        }
    }

    private fun processImage(uri: Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { OcrHelper.scan(this@MainActivity, uri) }
            val r = OcrHelper.parse(text)
            showSubDialog(null, r)
            Toast.makeText(this@MainActivity, "Распознано: ${r.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSubDialog(existing: Sub?, parsed: OcrHelper.Parsed? = null) {
        val context = this
        val scroll = ScrollView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }

        val etName = EditText(context).apply { hint = "Название"; setText(existing?.name ?: parsed?.name ?: "") }
        val etAmount = EditText(context).apply { hint = "Сумма"; setText(existing?.amount?.toString() ?: parsed?.amount?.toString() ?: ""); inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val etCard = EditText(context).apply { hint = "Карта (4 цифры)"; setText(existing?.card ?: parsed?.card ?: ""); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val etDate = EditText(context).apply { hint = "Дата продления (дд.мм.гггг)"; setText(sdf.format(Date(existing?.renewal ?: parsed?.date ?: System.currentTimeMillis()))) }
        val etUrl = EditText(context).apply { hint = "URL"; setText(existing?.url ?: ""); inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI }
        val etNotes = EditText(context).apply { hint = "Заметки"; setText(existing?.notes ?: ""); minLines = 2 }
        val switchAutoRenew = Switch(context).apply { text = "Автопродление"; isChecked = existing?.autoRenew ?: true }

        val spinnerCurr = Spinner(context)
        val currencies = Currency.values().map { "${it.symbol} ${it.code}" }
        spinnerCurr.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, currencies)
        val curIdx = Currency.values().indexOf(Currency.fromCode(existing?.currency ?: parsed?.currency ?: "RUB"))
        spinnerCurr.setSelection(if (curIdx >= 0) curIdx else 0)

        val spinnerPeriod = Spinner(context)
        val periods = Period.values().map { it.displayName }
        spinnerPeriod.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, periods)
        val perIdx = Period.values().indexOf(Period.valueOf(existing?.period ?: parsed?.period ?: "MONTH"))
        spinnerPeriod.setSelection(if (perIdx >= 0) perIdx else 1)

        val spinnerCat = Spinner(context)
        val categories = Category.values().map { "${it.emoji} ${it.displayName}" }
        spinnerCat.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, categories)
        val catIdx = Category.values().indexOf(Category.fromName(existing?.category ?: parsed?.category ?: "OTHER"))
        spinnerCat.setSelection(if (catIdx >= 0) catIdx else 0)

        val spinnerStatus = Spinner(context)
        val statuses = Status.values().map { "${it.emoji} ${it.displayName}" }
        spinnerStatus.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, statuses)
        val statIdx = Status.values().indexOf(Status.valueOf(existing?.status ?: "ACTIVE"))
        spinnerStatus.setSelection(if (statIdx >= 0) statIdx else 0)

        val radioGroup = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        val rbMust = RadioButton(context).apply { text = "🔴"; id = View.generateViewId() }
        val rbNice = RadioButton(context).apply { text = "🟡"; id = View.generateViewId(); isChecked = true }
        val rbCancel = RadioButton(context).apply { text = "🟢"; id = View.generateViewId() }
        radioGroup.addView(rbMust, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        radioGroup.addView(rbNice, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        radioGroup.addView(rbCancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val prio = existing?.priority ?: "NICE_TO_HAVE"
        if (prio == "MUST_HAVE") rbMust.isChecked = true
        else if (prio == "CANCELABLE") rbCancel.isChecked = true
        else rbNice.isChecked = true

        fun addField(view: View, margin: Int = 16) {
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.topMargin = margin
            layout.addView(view, params)
        }

        addField(etName, 0)
        addField(etAmount)
        addField(etCard)
        addField(etDate)

        val currLabel = TextView(context).apply { text = "Валюта"; textSize = 12f }
        addField(currLabel)
        addField(spinnerCurr, 4)

        val perLabel = TextView(context).apply { text = "Период"; textSize = 12f }
        addField(perLabel)
        addField(spinnerPeriod, 4)

        val catLabel = TextView(context).apply { text = "Категория"; textSize = 12f }
        addField(catLabel)
        addField(spinnerCat, 4)

        val statLabel = TextView(context).apply { text = "Статус"; textSize = 12f }
        addField(statLabel)
        addField(spinnerStatus, 4)

        val prioLabel = TextView(context).apply { text = "Приоритет"; textSize = 12f }
        addField(prioLabel)
        addField(radioGroup, 4)

        addField(etUrl)
        addField(etNotes)
        addField(switchAutoRenew)

        scroll.addView(layout)

        AlertDialog.Builder(context)
            .setTitle(if (existing == null) "Новая подписка" else "Изменить")
            .setView(scroll)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val amount = etAmount.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                val card = etCard.text.toString()
                val date = parseDate(etDate.text.toString()) ?: return@setPositiveButton
                val url = etUrl.text.toString()
                val notes = etNotes.text.toString()
                val autoRenew = switchAutoRenew.isChecked

                val currency = Currency.values()[spinnerCurr.selectedItemPosition].code
                val period = Period.values()[spinnerPeriod.selectedItemPosition].name
                val category = Category.values()[spinnerCat.selectedItemPosition].name
                val status = Status.values()[spinnerStatus.selectedItemPosition].name

                val priority = when {
                    rbMust.isChecked -> "MUST_HAVE"
                    rbCancel.isChecked -> "CANCELABLE"
                    else -> "NICE_TO_HAVE"
                }

                val sub = Sub(existing?.id ?: 0, name, amount, currency, period, card, existing?.startDate ?: System.currentTimeMillis(), date, category, status, priority, url, notes, autoRenew)
                lifecycleScope.launch(Dispatchers.IO) {
                    if (existing == null) { db.subDao().insert(sub); if (autoRenew) scheduleReminder(sub) }
                    else { db.subDao().update(sub); if (autoRenew) scheduleReminder(sub) else cancelReminder(sub.id) }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteSub(sub: Sub) {
        AlertDialog.Builder(this)
            .setTitle("Удалить ${sub.name}?")
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) { db.subDao().delete(sub); cancelReminder(sub.id) }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun parseDate(s: String): Long? {
        val p = s.split(".")
        if (p.size != 3) return null
        val d = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val y = p[2].toIntOrNull() ?: return null
        return Calendar.getInstance().apply { set(y, m - 1, d, 0, 0, 0) }.timeInMillis
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("subs", "Напоминания", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun scheduleReminder(sub: Sub) {
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        listOf(3L, 1L, 0L).forEach { daysBefore ->
            val intent = Intent(this, ReminderReceiver::class.java).apply { putExtra("name", sub.name); putExtra("days", daysBefore) }
            val requestCode = (sub.id * 10 + daysBefore).toInt()
            val pending = PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val trigger = sub.renewal - daysBefore * 86400000L
            if (trigger > System.currentTimeMillis()) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        }
    }

    private fun cancelReminder(subId: Long) {
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        listOf(0L, 1L, 3L).forEach { daysBefore ->
            val intent = Intent(this, ReminderReceiver::class.java)
            val requestCode = (subId * 10 + daysBefore).toInt()
            val pending = PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarm.cancel(pending)
        }
    }

    class ReminderReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val name = intent.getStringExtra("name") ?: return
            val days = intent.getLongExtra("days", 0L)
            val title = when (days) { 0L -> "💰 Сегодня списание!"; 1L -> "⚠️ Завтра списание"; else -> "📅 Через $days дн. списание" }
            val notification = NotificationCompat.Builder(ctx, "subs").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText("Подписка «$name»").setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
            ctx.getSystemService(NotificationManager::class.java).notify("${name}_$days".hashCode(), notification)
        }
    }

    class SubAdapter(private val onEdit: ((Sub) -> Unit)?, private val onDelete: ((Sub) -> Unit)?) : ListAdapter<Sub, SubAdapter.VH>(object : DiffUtil.ItemCallback<Sub>() {
        override fun areItemsTheSame(a: Sub, b: Sub) = a.id == b.id
        override fun areContentsTheSame(a: Sub, b: Sub) = a == b
    }) {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvEmoji: TextView = view.findViewById(R.id.tv_emoji)
            val tvName: TextView = view.findViewById(R.id.tv_name)
            val tvAmount: TextView = view.findViewById(R.id.tv_amount)
            val tvCategory: TextView = view.findViewById(R.id.tv_category)
            val tvCard: TextView = view.findViewById(R.id.tv_card)
            val tvDate: TextView = view.findViewById(R.id.tv_date)
            val tvDays: TextView = view.findViewById(R.id.tv_days)
            val btnEdit: Button = view.findViewById(R.id.btn_edit)
            val btnDelete: Button = view.findViewById(R.id.btn_delete)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val view = LayoutInflater.from(p.context).inflate(R.layout.item_sub, p, false)
            return VH(view)
        }
        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = getItem(pos)
            h.tvEmoji.text = s.cat().emoji
            h.tvName.text = s.name
            h.tvAmount.text = "${"%.0f".format(s.amount)} ${s.cur().symbol}/${s.per().displayName}"
            h.tvCard.text = "Карта *${s.card.takeLast(4)}"
            h.tvDate.text = "Списание: ${SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(s.renewal))}"
            val days = s.daysLeft()
            h.tvDays.text = when {
                s.stat() != Status.ACTIVE -> "${s.stat().emoji} ${s.stat().displayName}"
                days < 0 -> "Просрочено"
                days == 0L -> "⚠️ Сегодня"
                days == 1L -> "⚠️ Завтра"
                days <= 3 -> "Осталось $days дн."
                else -> "Осталось $days дн."
            }
            h.tvDays.setTextColor(when { days <= 1 -> 0xFFE53935.toInt(); days <= 3 -> 0xFFFF9800.toInt(); else -> 0xFF1976D2.toInt() })
            h.tvCategory.text = "${s.cat().displayName} • ${s.prio().emoji}"
            h.btnEdit.visibility = if (onEdit != null) View.VISIBLE else View.GONE
            h.btnDelete.visibility = if (onDelete != null) View.VISIBLE else View.GONE
            h.btnEdit.setOnClickListener { onEdit?.invoke(s) }
            h.btnDelete.setOnClickListener { onDelete?.invoke(s) }
        }
    }

    class ExpAdapter(private val onDelete: (Expense) -> Unit) : ListAdapter<Expense, ExpAdapter.VH>(object : DiffUtil.ItemCallback<Expense>() {
        override fun areItemsTheSame(a: Expense, b: Expense) = a.id == b.id
        override fun areContentsTheSame(a: Expense, b: Expense) = a == b
    }) {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvMerchant: TextView = view.findViewById(R.id.tv_merchant)
            val tvAmount: TextView = view.findViewById(R.id.tv_amount)
            val tvCard: TextView = view.findViewById(R.id.tv_card)
            val tvDate: TextView = view.findViewById(R.id.tv_date)
            val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val view = LayoutInflater.from(p.context).inflate(R.layout.item_exp, p, false)
            return VH(view)
        }
        override fun onBindViewHolder(h: VH, pos: Int) {
            val e = getItem(pos)
            h.tvMerchant.text = e.merchant
            h.tvAmount.text = "-${"%.0f".format(e.amount)} ₽"
            h.tvCard.text = "Карта *${e.card.takeLast(4)}"
            h.tvDate.text = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(e.date))
            h.btnDelete.setOnClickListener { onDelete(e) }
        }
    }
}
