package com.example.financetracker

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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.financetracker.databinding.ActivityMainBinding
import com.example.financetracker.databinding.DialogAddBinding
import com.example.financetracker.databinding.ItemSubBinding
import com.example.financetracker.databinding.ItemExpBinding
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.utils.ColorTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val db by lazy { DB.get(this) }
    private val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val sdfShort = SimpleDateFormat("dd.MM", Locale.getDefault())
    
    private val subAdapter = SubAdapter(
        onEdit = { showSubDialog(it) },
        onDelete = { deleteSub(it) }
    )
    private val expAdapter = ExpAdapter(
        onDelete = { db.expDao().delete(it) }
    )

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { processImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        createNotificationChannel()

        // Навигация
        b.tabHome.setOnClickListener { switchTab(0) }
        b.tabSubs.setOnClickListener { switchTab(1) }
        b.tabExp.setOnClickListener { switchTab(2) }
        b.tabStats.setOnClickListener { switchTab(3) }

        // Dashboard
        db.subDao().totalMonthly().observe(this) { b.tvTotal.text = "${"%.0f".format(it ?: 0.0)} ₽/мес" }
        db.subDao().totalYearly().observe(this) { b.tvYearly.text = "${"%.0f".format(it ?: 0.0)} ₽/год" }
        db.subDao().activeCount().observe(this) { b.tvCount.text = "$it активных" }
        db.subDao().savingsPotential().observe(this) { 
            b.tvSavings.text = "💡 Можно сэкономить ${"%.0f".format(it ?: 0.0)} ₽/мес"
            b.tvSavings.visibility = if ((it ?: 0.0) > 0) View.VISIBLE else View.GONE
        }

        // Ближайшие
        val upcomingAdapter = SubAdapter({}, {})
        b.recyclerUpcoming.layoutManager = LinearLayoutManager(this)
        b.recyclerUpcoming.adapter = upcomingAdapter
        val now = System.currentTimeMillis()
        db.subDao().upcoming(now, now + 7L * 86400000).observe(this) { list ->
            upcomingAdapter.submitList(list)
            b.tvNoUpcoming.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }

        // Подписки
        b.recyclerSubs.layoutManager = LinearLayoutManager(this)
        b.recyclerSubs.adapter = subAdapter
        db.subDao().all().observe(this) { subAdapter.submitList(it) }

        // Фильтры
        b.chipAll.setOnClickListener { db.subDao().all().observe(this) { subAdapter.submitList(it) } }
        b.chipActive.setOnClickListener { db.subDao().byStatus("ACTIVE").observe(this) { subAdapter.submitList(it) } }
        b.chipCancelled.setOnClickListener { db.subDao().byStatus("CANCELLED").observe(this) { subAdapter.submitList(it) } }

        // Расходы
        b.recyclerExp.layoutManager = LinearLayoutManager(this)
        b.recyclerExp.adapter = expAdapter
        db.expDao().all().observe(this) { expAdapter.submitList(it) }

        // Статистика
        setupStats()

        // FAB
        b.fabAdd.setOnClickListener { showSubDialog(null) }
        b.fabScan.setOnClickListener { pickImage.launch("image/*") }
        b.fabAddExp.setOnClickListener { showExpDialog() }
        b.fabScanExp.setOnClickListener { pickImage.launch("image/*") }

        switchTab(0)
    }

    private fun switchTab(idx: Int) {
        b.screenHome.visibility = if (idx == 0) View.VISIBLE else View.GONE
        b.screenSubs.visibility = if (idx == 1) View.VISIBLE else View.GONE
        b.screenExp.visibility = if (idx == 2) View.VISIBLE else View.GONE
        b.screenStats.visibility = if (idx == 3) View.VISIBLE else View.GONE
        b.tabHome.alpha = if (idx == 0) 1f else 0.5f
        b.tabSubs.alpha = if (idx == 1) 1f else 0.5f
        b.tabExp.alpha = if (idx == 2) 1f else 0.5f
        b.tabStats.alpha = if (idx == 3) 1f else 0.5f
    }

    private fun setupStats() {
        db.subDao().byCategory().observe(this) { data ->
            val entries = data.map { PieEntry(it.total.toFloat(), Category.fromName(it.category).emoji) }
            val set = PieDataSet(entries, "Категории").apply {
                colors = data.map { Category.fromName(it.category).color }
            }
            b.chartSubCats.data = PieData(set)
            b.chartSubCats.invalidate()
        }

        db.subDao().byCurrency().observe(this) { data ->
            val entries = data.map { BarEntry(it.currency.toFloat(), it.total.toFloat()) }
            val set = BarDataSet(entries, "Валюты").apply {
                colors = ColorTemplate.MATERIAL_COLORS.toList()
            }
            b.chartSubCurr.data = BarData(set)
            b.chartSubCurr.invalidate()
        }

        db.expDao().byCard().observe(this) { data ->
            val entries = data.map { BarEntry(it.card.toFloat(), it.total.toFloat()) }
            val set = BarDataSet(entries, "Картам").apply {
                colors = ColorTemplate.MATERIAL_COLORS.toList()
            }
            b.chartExpCards.data = BarData(set)
            b.chartExpCards.invalidate()
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
        val d = DialogAddBinding.inflate(layoutInflater)
        d.etName.setText(existing?.name ?: parsed?.name ?: "")
        d.etAmount.setText(existing?.amount?.toString() ?: parsed?.amount?.toString() ?: "")
        d.etCard.setText(existing?.card ?: parsed?.card ?: "")
        d.etDate.setText(sdf.format(Date(existing?.renewal ?: parsed?.date ?: System.currentTimeMillis())))
        d.etUrl.setText(existing?.url ?: "")
        d.etNotes.setText(existing?.notes ?: "")
        d.switchAutoRenew.isChecked = existing?.autoRenew ?: true

        // Спиннеры
        val currencies = Currency.values().map { "${it.symbol} ${it.code}" }
        d.spinnerCurr.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, currencies))
        d.spinnerCurr.setText(currencies[Currency.values().indexOf(Currency.fromCode(existing?.currency ?: parsed?.currency ?: "RUB"))], false)

        val periods = Period.values().map { it.displayName }
        d.spinnerPeriod.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, periods))
        d.spinnerPeriod.setText(periods[Period.values().indexOf(Period.valueOf(existing?.period ?: parsed?.period ?: "MONTH"))], false)

        val categories = Category.values().map { "${it.emoji} ${it.displayName}" }
        d.spinnerCat.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, categories))
        d.spinnerCat.setText(categories[Category.values().indexOf(Category.fromName(existing?.category ?: parsed?.category ?: "OTHER"))], false)

        val statuses = Status.values().map { "${it.emoji} ${it.displayName}" }
        d.spinnerStatus.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, statuses))
        d.spinnerStatus.setText(statuses[Status.values().indexOf(Status.valueOf(existing?.status ?: "ACTIVE"))], false)

        // Приоритет
        when (existing?.priority ?: "NICE_TO_HAVE") {
            "MUST_HAVE" -> d.togglePrio.check(R.id.btnMust)
            "CANCELABLE" -> d.togglePrio.check(R.id.btnCancel)
            else -> d.togglePrio.check(R.id.btnNice)
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Новая подписка" else "Изменить")
            .setView(d.root)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = d.etName.text.toString().trim().ifEmpty { return@setPositiveButton }
                val amount = d.etAmount.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                val card = d.etCard.text.toString()
                val date = parseDate(d.etDate.text.toString()) ?: return@setPositiveButton
                val url = d.etUrl.text.toString()
                val notes = d.etNotes.text.toString()
                val autoRenew = d.switchAutoRenew.isChecked

                val currency = Currency.values()[currencies.indexOf(d.spinnerCurr.text.toString())].code
                val period = Period.values()[periods.indexOf(d.spinnerPeriod.text.toString())].name
                val category = Category.values()[categories.indexOf(d.spinnerCat.text.toString())].name
                val status = Status.values()[statuses.indexOf(d.spinnerStatus.text.toString())].name
                val priority = when (d.togglePrio.checkedButtonId) {
                    R.id.btnMust -> "MUST_HAVE"
                    R.id.btnCancel -> "CANCELABLE"
                    else -> "NICE_TO_HAVE"
                }

                val sub = Sub(existing?.id ?: 0, name, amount, currency, period, card, 
                    existing?.startDate ?: System.currentTimeMillis(), date, category, status, priority, url, notes, autoRenew)
                
                lifecycleScope.launch(Dispatchers.IO) {
                    if (existing == null) {
                        db.subDao().insert(sub)
                        if (autoRenew) scheduleReminder(sub)
                    } else {
                        db.subDao().update(sub)
                        if (autoRenew) scheduleReminder(sub) else cancelReminder(sub.id)
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteSub(sub: Sub) {
        AlertDialog.Builder(this)
            .setTitle("Удалить ${sub.name}?")
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.subDao().delete(sub)
                    cancelReminder(sub.id)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showExpDialog() {
        val d = DialogAddBinding.inflate(layoutInflater)
        d.etName.hint = "Магазин/сервис"
        d.etAmount.hint = "Сумма"
        d.etCard.hint = "Карта (4 цифры)"
        d.etDate.hint = "Дата"
        d.spinnerCat.visibility = View.VISIBLE
        d.spinnerCurr.visibility = View.GONE
        d.spinnerPeriod.visibility = View.GONE
        d.spinnerStatus.visibility = View.GONE
        d.togglePrio.visibility = View.GONE
        d.etUrl.visibility = View.GONE
        d.etNotes.visibility = View.GONE
        d.switchAutoRenew.visibility = View.GONE

        val categories = Category.values().map { "${it.emoji} ${it.displayName}" }
        d.spinnerCat.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, categories))
        d.spinnerCat.setText(categories[0], false)

        AlertDialog.Builder(this)
            .setTitle("Новый расход")
            .setView(d.root)
            .setPositiveButton("Сохранить") { _, _ ->
                val merchant = d.etName.text.toString().trim().ifEmpty { return@setPositiveButton }
                val amount = d.etAmount.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                val card = d.etCard.text.toString()
                val date = parseDate(d.etDate.text.toString()) ?: System.currentTimeMillis()
                val category = Category.values()[categories.indexOf(d.spinnerCat.text.toString())].name
                
                val exp = Expense(amount = amount, card = card, merchant = merchant, date = date, category = category)
                lifecycleScope.launch(Dispatchers.IO) { db.expDao().insert(exp) }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun parseDate(s: String): Long? {
        val p = s.split(".")
        if (p.size != 3) return null
        val (d, m, y) = p.map { it.toIntOrNull() ?: return null }
        return Calendar.getInstance().apply { set(y, m-1, d, 0, 0, 0) }.timeInMillis
    }

    // ===== УВЕДОМЛЕНИЯ =====

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("subs", "Напоминания", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun scheduleReminder(sub: Sub) {
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        listOf(3L, 1L, 0L).forEach { daysBefore ->
            val intent = Intent(this, ReminderReceiver::class.java).apply {
                putExtra("name", sub.name)
                putExtra("days", daysBefore)
            }
            val pending = PendingIntent.getBroadcast(this, (sub.id * 10 + daysBefore).toInt(), intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val trigger = sub.renewal - daysBefore * 86400000L
            if (trigger > System.currentTimeMillis()) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            }
        }
    }

    private fun cancelReminder(subId: Long) {
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        listOf(0L, 1L, 3L).forEach { daysBefore ->
            val intent = Intent(this, ReminderReceiver::class.java)
            val pending = PendingIntent.getBroadcast(this, (subId * 10 + daysBefore).toInt(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarm.cancel(pending)
        }
    }

    class ReminderReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val name = intent.getStringExtra("name") ?: return
            val days = intent.getLongExtra("days", 0)
            val title = when (days) {
                0L -> "💰 Сегодня списание!"
                1L -> "⚠️ Завтра списание"
                else -> "📅 Через $days дн. списание"
            }
            val notification = NotificationCompat.Builder(ctx, "subs")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText("Подписка «$name»")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify("${name}_$days".hashCode(), notification)
        }
    }

    // ===== АДАПТЕРЫ =====

    class SubAdapter(
        private val onEdit: ((Sub) -> Unit)? = null,
        private val onDelete: ((Sub) -> Unit)? = null
    ) : ListAdapter<Sub, SubAdapter.VH>(object : DiffUtil.ItemCallback<Sub>() {
        override fun areItemsTheSame(a: Sub, b: Sub) = a.id == b.id
        override fun areContentsTheSame(a: Sub, b: Sub) = a == b
    }) {
        inner class VH(val b: ItemSubBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(ItemSubBinding.inflate(LayoutInflater.from(p.context), p, false))
        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = getItem(pos)
            h.b.tvEmoji.text = s.cat().emoji
            h.b.tvName.text = s.name
            h.b.tvAmount.text = "${"%.0f".format(s.amount)} ${s.cur().symbol}/${s.per().displayName}"
            h.b.tvCard.text = "Карта *${s.card.takeLast(4)}"
            h.b.tvDate.text = "Списание: ${SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(s.renewal))}"
            val days = s.daysLeft()
            h.b.tvDays.text = when {
                s.stat() != Status.ACTIVE -> "${s.stat().emoji} ${s.stat().displayName}"
                days < 0 -> "Просрочено"
                days == 0 -> "⚠️ Сегодня"
                days == 1 -> "⚠️ Завтра"
                days <= 3 -> "Осталось $days дн."
                else -> "Осталось $days дн."
            }
            h.b.tvDays.setTextColor(when {
                days <= 1 -> 0xFFE53935.toInt()
                days <= 3 -> 0xFFFF9800.toInt()
                else -> 0xFF1976D2.toInt()
            })
            h.b.tvCategory.text = "${s.cat().displayName} • ${s.prio().emoji}"
            h.b.btnEdit.visibility = if (onEdit != null) View.VISIBLE else View.GONE
            h.b.btnDelete.visibility = if (onDelete != null) View.VISIBLE else View.GONE
            h.b.btnEdit.setOnClickListener { onEdit?.invoke(s) }
            h.b.btnDelete.setOnClickListener { onDelete?.invoke(s) }
        }
    }

    class ExpAdapter(
        private val onDelete: (Expense) -> Unit
    ) : ListAdapter<Expense, ExpAdapter.VH>(object : DiffUtil.ItemCallback<Expense>() {
        override fun areItemsTheSame(a: Expense, b: Expense) = a.id == b.id
        override fun areContentsTheSame(a: Expense, b: Expense) = a == b
    }) {
        inner class VH(val b: ItemExpBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(ItemExpBinding.inflate(LayoutInflater.from(p.context), p, false))
        override fun onBindViewHolder(h: VH, pos: Int) {
            val e = getItem(pos)
            h.b.tvMerchant.text = e.merchant
            h.b.tvAmount.text = "-${"%.0f".format(e.amount)} ₽"
            h.b.tvCard.text = "Карта *${e.card.takeLast(4)}"
            h.b.tvDate.text = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(e.date))
            h.b.btnDelete.setOnClickListener { onDelete(e) }
        }
    }
}
