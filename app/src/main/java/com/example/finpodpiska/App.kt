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

enum class Category(val emoji: String, val displayName: String) {
    VIDEO("🎬", "Видео"), MUSIC("🎵", "Музыка"), GAMES("🎮", "Игры"),
    SOFTWARE("💻", "Софт"), EDUCATION("📚", "Образование"), NEWS("📰", "Новости"),
    CLOUD("☁️", "Облако"), FITNESS("💪", "Спорт"), OTHER("📦", "Другое");
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
    @Query("SELECT SUM(amount / CASE period WHEN 'WEEK' THEN 0.23 WHEN 'MONTH' THEN 1.0 WHEN 'QUARTER' THEN 3.0 WHEN 'YEAR' THEN 12.0 END) FROM subscriptions WHERE status = 'ACTIVE' AND priority = 'CANCELABLE'") fun savingsPotential(): LiveData<Double?>
    data class CatTotal(val category: String, val total: Double)
}

@Dao
interface ExpenseDao {
    @Insert suspend fun insert(e: Expense)
    @Delete suspend fun delete(e: Expense)
    @Query("SELECT * FROM expenses ORDER BY date DESC") fun all(): LiveData<List<Expense>>
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

// ===== OCR =====

object OcrHelper {
    private val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    suspend fun scan(ctx: Context, uri: Uri): String = suspendCancellableCoroutine { cont ->
        try {
            val image = InputImage.fromFilePath(ctx, uri)
            rec.process(image)
                .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result.text) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        } catch (e: Exception) { if (cont.isActive) cont.resumeWithException(e) }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(0xFFF5F5F5.toInt())
        }

        val title = TextView(this).apply {
            text = "💰 ФинПодписка"
            textSize = 24f
            setPadding(48, 48, 48, 32)
        }
        root.addView(title)

        val summaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1976D2.toInt())
            setPadding(48, 48, 48, 48)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(32, 0, 32, 32)
            }
        }
        val tvTotal = TextView(this).apply { text = "0 ₽/мес"; textSize = 32f; setTextColor(0xFFFFFFFF.toInt()) }
        val tvYearly = TextView(this).apply { text = "0 ₽/год"; textSize = 16f; setTextColor(0xFFBBDEFB.toInt()); setPadding(0, 16, 0, 0) }
        val tvCount = TextView(this).apply { text = "0 активных"; textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setPadding(0, 24, 0, 0) }
        summaryCard.addView(tvTotal)
        summaryCard.addView(tvYearly)
        summaryCard.addView(tvCount)
        root.addView(summaryCard)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(32, 0, 32, 32)
            }
        }
        val btnAdd = Button(this).apply { text = "➕ Добавить"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 16, 0) } }
        val btnScan = Button(this).apply { text = "📷 Сканировать"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        btnRow.addView(btnAdd)
        btnRow.addView(btnScan)
        root.addView(btnRow)

        val tvLabel = TextView(this).apply { text = "Подписки:"; textSize = 18f; setPadding(48, 16, 48, 16) }
        root.addView(tvLabel)

        val recyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                setMargins(16, 0, 16, 0)
            }
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
        root.addView(recyclerView)

        setContentView(root)

        createNotificationChannel()

        db.subDao().totalMonthly().observe(this) { total -> tvTotal.text = "${"%.0f".format(total ?: 0.0)} ₽/мес" }
        db.subDao().totalYearly().observe(this) { total -> tvYearly.text = "${"%.0f".format(total ?: 0.0)} ₽/год" }
        db.subDao().activeCount().observe(this) { count -> tvCount.text = "$count активных" }

        val adapter = SubAdapter(
            onEdit = { sub: Sub -> showSubDialog(sub) },
            onDelete = { sub: Sub -> deleteSub(sub) }
        )
        recyclerView.adapter = adapter
        db.subDao().all().observe(this) { list -> adapter.submitList(list) }

        btnAdd.setOnClickListener { showSubDialog(null) }
        btnScan.setOnClickListener { pickImage.launch("image/*") }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processImage(it) }
    }

    private fun processImage(uri: Uri) {
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { OcrHelper.scan(this@MainActivity, uri) }
                val r = OcrHelper.parse(text)
                showSubDialog(null, r)
                Toast.makeText(this@MainActivity, "Распознано: ${r.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            }
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
        addField(TextView(context).apply { text = "Валюта"; textSize = 12f })
        addField(spinnerCurr, 4)
        addField(TextView(context).apply { text = "Период"; textSize = 12f })
        addField(spinnerPeriod, 4)
        addField(TextView(context).apply { text = "Категория"; textSize = 12f })
        addField(spinnerCat, 4)
        addField(TextView(context).apply { text = "Статус"; textSize = 12f })
        addField(spinnerStatus, 4)
        addField(TextView(context).apply { text = "Приоритет"; textSize = 12f })
        addField(radioGroup, 4)
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

                val sub = Sub(existing?.id ?: 0, name, amount, currency, period, card, existing?.startDate ?: System.currentTimeMillis(), date, category, status, priority, "", "", autoRenew)
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
            val tvName: TextView = view.findViewById(android.R.id.text1)
            val tvDetails: TextView = view.findViewById(android.R.id.text2)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val view = LayoutInflater.from(p.context).inflate(android.R.layout.simple_list_item_2, p, false)
            return VH(view)
        }
        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = getItem(pos)
            h.tvName.text = "${s.cat().emoji} ${s.name}"
            h.tvDetails.text = "${"%.0f".format(s.amount)} ${s.cur().symbol}/${s.per().displayName} • ${s.stat().emoji} ${s.prio().emoji}"
            h.itemView.setOnClickListener { onEdit?.invoke(s) }
            h.itemView.setOnLongClickListener { 
                onDelete?.invoke(s)
                true
            }
        }
    }
}
