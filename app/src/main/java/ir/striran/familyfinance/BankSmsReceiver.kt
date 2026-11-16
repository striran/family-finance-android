package ir.striran.familyfinance

import android.content.BroadcastReceiver
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage

data class BankSmsSuggestion(
    val amount: Long,
    val type: String,
    val category: String,
    val body: String,
    val sender: String
)

fun readBankSmsSuggestion(context: Context): BankSmsSuggestion? {
    val p = context.getSharedPreferences("bank_sms", Context.MODE_PRIVATE)
    val amount = p.getLong("amount", 0L)
    val body = p.getString("body", null) ?: return null
    if (amount <= 0L) return null
    return BankSmsSuggestion(amount, p.getString("type", "هزینه") ?: "هزینه",
        p.getString("category", "سایر هزینه‌ها") ?: "سایر هزینه‌ها", body,
        p.getString("sender", "") ?: "")
}

fun clearBankSmsSuggestion(context: Context) {
    context.getSharedPreferences("bank_sms", Context.MODE_PRIVATE).edit().clear().apply()
}

class BankSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return
        val messages = try {
            if (Build.VERSION.SDK_INT >= 19) {
                android.provider.Telephony.Sms.Intents.getMessagesFromIntent(intent)?.toList().orEmpty()
            } else {
                @Suppress("DEPRECATION")
                (intent.extras?.get("pdus") as? Array<*>)?.mapNotNull {
                    @Suppress("DEPRECATION")
                    SmsMessage.createFromPdu(it as ByteArray)
                }.orEmpty()
            }
        } catch (_: Exception) { emptyList() }
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        val sender = messages.firstOrNull()?.originatingAddress.orEmpty()
        val normalized = normalizeDigits(body)
        val lower = normalized.lowercase()
        val type = when {
            listOf("برداشت", "خرید", "پرداخت", "کسر از", "برداشت از").any { lower.contains(it) } -> "هزینه"
            listOf("واریز", "دریافت", "افزایش موجودی").any { lower.contains(it) } -> "درآمد"
            else -> return
        }
        val amountPattern = Regex("(?:مبلغ|برداشت|واریز|خرید)\\s*[:：]?\\s*([0-9][0-9,٬.]*)")
        val rawAmount = amountPattern.find(normalized)?.groupValues?.getOrNull(1) ?: return
        val numeric = rawAmount.replace(",", "").replace("٬", "").replace(".", "").toLongOrNull() ?: return
        val amount = if (lower.contains("ریال") || lower.contains("rial")) numeric / 10L else numeric
        if (amount <= 0L || amount > 10_000_000_000_000L) return
        val category = when {
            type == "هزینه" && listOf("سوخت","بنزین","پمپ").any { lower.contains(it) } -> "بنزین و سوخت"
            type == "هزینه" && listOf("قبض","آب","برق","گاز").any { lower.contains(it) } -> "قبوض آب، برق و گاز"
            type == "هزینه" && listOf("دارو","درمان","بیمارستان","درمانگاه").any { lower.contains(it) } -> "درمان و دارو"
            type == "هزینه" && listOf("خرید","پوز","پایانه فروش").any { lower.contains(it) } -> "خرید روزانه"
            type == "درآمد" && listOf("حقوق","دستمزد","کارفرما").any { lower.contains(it) } -> "حقوق و دستمزد"
            type == "درآمد" && lower.contains("سود") -> "سود سرمایه‌گذاری"
            else -> if (type == "هزینه") "سایر هزینه‌ها" else "سایر درآمدها"
        }
        context.getSharedPreferences("bank_sms", Context.MODE_PRIVATE).edit()
            .putLong("amount", amount).putString("type", type).putString("category", category)
            .putString("body", body.take(700)).putString("sender", sender).apply()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "bank_sms_suggestions"
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(channelId, "پیشنهاد تراکنش بانکی", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val openApp = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 8201, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val notification = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channelId) else Notification.Builder(context)
        notification.setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("پیامک بانکی شناسایی شد")
            .setContentText("برای بررسی و ثبت تراکنش، برنامه را باز کنید.")
            .setAutoCancel(true).setContentIntent(pending)
        manager.notify(8201, notification.build())
    }

    private fun normalizeDigits(value: String): String = value
        .replace('۰','0').replace('۱','1').replace('۲','2').replace('۳','3').replace('۴','4')
        .replace('۵','5').replace('۶','6').replace('۷','7').replace('۸','8').replace('۹','9')
        .replace('٠','0').replace('١','1').replace('٢','2').replace('٣','3').replace('٤','4')
        .replace('٥','5').replace('٦','6').replace('٧','7').replace('٨','8').replace('٩','9')
}
