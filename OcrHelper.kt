package com.example.financetracker

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.util.*

object OcrHelper {
    private val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun scan(ctx: Context, uri: Uri): String = try {
        rec.process(InputImage.fromFilePath(ctx, uri)).await().text
    } catch (e: Exception) { "" }

    data class Parsed(
        val name: String,
        val amount: Double?,
        val card: String?,
        val date: Long?,
        val category: String,
        val currency: String,
        val period: String
    )

    fun parse(text: String): Parsed {
        val lower = text.lowercase()
        
        // Сумма
        val amount = Regex("""(\d+[.,]?\d*)\s*(?:₽|руб|RUB)""")
            .find(text)?.groupValues?.get(1)?.replace(",",".")?.toDoubleOrNull()
            ?: Regex("""\$\s*(\d+[.,]?\d*)""").find(text)?.groupValues?.get(1)?.replace(",",".")?.toDoubleOrNull()
            ?: Regex("""€\s*(\d+[.,]?\d*)""").find(text)?.groupValues?.get(1)?.replace(",",".")?.toDoubleOrNull()
        
        // Карта
        val card = Regex("""\*{0,4}(\d{4})""").find(text)?.groupValues?.get(1)
        
        // Дата
        val date = Regex("""(\d{2})[./-](\d{2})[./-](\d{4})""").find(text)?.let {
            Calendar.getInstance().apply {
                set(it.groupValues[3].toInt(), it.groupValues[2].toInt()-1, it.groupValues[1].toInt())
            }.timeInMillis
        }
        
        // Валюта
        val currency = when {
            Regex("""\$\s*\d|\d+\s*\$|usd""").containsMatchIn(lower) -> "USD"
            Regex("""€\s*\d|\d+\s*€|eur""").containsMatchIn(lower) -> "EUR"
            else -> "RUB"
        }
        
        // Период
        val period = when {
            Regex("""год|year|12\s*мес""").containsMatchIn(lower) -> "YEAR"
            Regex("""квартал|quarter|3\s*мес""").containsMatchIn(lower) -> "QUARTER"
            Regex("""недел|week|7\s*дн""").containsMatchIn(lower) -> "WEEK"
            else -> "MONTH"
        }
        
        // Категория и название
        val cat = Category.detect(text)
        val name = listOf("Netflix","Spotify","YouTube Premium","Яндекс Музыка","Кинопоиск","Figma","Adobe","Office 365","Game Pass","iCloud","Google One")
            .firstOrNull { text.lowercase().contains(it.lowercase()) }
            ?: "${cat.emoji} Подписка"
        
        return Parsed(name, amount, card, date, cat.name, currency, period)
    }
}
