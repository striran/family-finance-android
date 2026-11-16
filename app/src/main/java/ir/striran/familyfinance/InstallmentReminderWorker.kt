package ir.striran.familyfinance

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class InstallmentReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
        val db = FinanceDatabase(applicationContext)
        val overdue = db.overdueInstallments()
        val upcoming = db.upcomingInstallments(3)
        val dueBills = db.dueRecurringBills()
        val dueCheques = db.dueCheques(3)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "یادآوری اقساط", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "یادآوری اقساط عقب‌افتاده و نزدیک سررسید"
                }
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            applicationContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            db.close()
            return Result.success()
        }
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (overdue.isNotEmpty()) {
            val amount = overdue.sumOf { it.amount }
            manager.notify(
                OVERDUE_NOTIFICATION_ID,
                NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("اقساط عقب‌افتاده")
                    .setContentText("${overdue.size} قسط به مبلغ ${amount} تومان پرداخت‌نشده دارید.")
                    .setStyle(NotificationCompat.BigTextStyle().bigText("تعداد اقساط عقب‌افتاده: ${overdue.size} · مجموع مبلغ: ${amount} تومان"))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build()
            )
        }
        if (upcoming.isNotEmpty()) {
            val amount = upcoming.sumOf { it.amount }
            manager.notify(
                UPCOMING_NOTIFICATION_ID,
                NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("یادآوری سررسید اقساط")
                    .setContentText("${upcoming.size} قسط طی ۳ روز آینده سررسید می‌شود.")
                    .setStyle(NotificationCompat.BigTextStyle().bigText("تعداد اقساط نزدیک سررسید: ${upcoming.size} · مجموع مبلغ: ${amount} تومان"))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build()
            )
        }
        if (dueBills.isNotEmpty()) {
            val amount = dueBills.sumOf { it.amount }
            manager.notify(
                RECURRING_BILLS_NOTIFICATION_ID,
                NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("قبض‌ها و هزینه‌های دوره‌ای")
                    .setContentText("${dueBills.size} پرداخت دوره‌ای سررسید شده است.")
                    .setStyle(NotificationCompat.BigTextStyle().bigText("موارد سررسیدشده: ${dueBills.size} · مجموع مبلغ: ${amount} تومان · برای ثبت پرداخت وارد برنامه شوید."))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build()
            )
        }
        if (dueCheques.isNotEmpty()) {
            val incoming = dueCheques.filter { it.type == "دریافتی" }.sumOf { it.amount }
            val outgoing = dueCheques.filter { it.type == "پرداختی" }.sumOf { it.amount }
            manager.notify(
                CHEQUES_NOTIFICATION_ID,
                NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("یادآوری سررسید چک‌ها")
                    .setContentText("${dueCheques.size} چک در سه روز آینده یا قبل از آن سررسید دارد.")
                    .setStyle(NotificationCompat.BigTextStyle().bigText("تعداد چک‌های سررسیدشده یا نزدیک سررسید: ${dueCheques.size} · دریافتی: ${incoming} تومان · پرداختی: ${outgoing} تومان"))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build()
            )
        }
        db.close()
        Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val CHANNEL_ID = "family_installment_reminders"
        const val OVERDUE_NOTIFICATION_ID = 5101
        const val UPCOMING_NOTIFICATION_ID = 5102
        const val RECURRING_BILLS_NOTIFICATION_ID = 5103
        const val CHEQUES_NOTIFICATION_ID = 5104
    }
}
