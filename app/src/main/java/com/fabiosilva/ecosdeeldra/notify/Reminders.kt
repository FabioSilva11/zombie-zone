package com.fabiosilva.ecosdeeldra.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.fabiosilva.ecosdeeldra.MainActivity
import com.fabiosilva.ecosdeeldra.R
import java.util.Calendar

/** Lembretes locais para trazer o jogador de volta (baú pronto e prêmio diário). */
object Reminders {
    const val CHANNEL = "lembretes"
    private const val EXTRA_KIND = "kind"
    private const val KIND_CHEST = 1
    private const val KIND_DAILY = 2

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Lembretes do jogo", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Baú de suprimentos pronto e prêmio diário disponível"
            },
        )
    }

    /** Agenda os lembretes ao sair do app; [chestReadyAt] em epoch ms. */
    fun schedule(context: Context, chestReadyAt: Long, enabled: Boolean) {
        cancel(context)
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (chestReadyAt > now + 60_000) set(context, KIND_CHEST, chestReadyAt)
        // Amanhã às 19h: prêmio diário e novas missões.
        val tomorrow = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 19)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        set(context, KIND_DAILY, tomorrow.timeInMillis)
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.cancel(pending(context, KIND_CHEST))
        alarm.cancel(pending(context, KIND_DAILY))
    }

    private fun set(context: Context, kind: Int, at: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.set(AlarmManager.RTC, at, pending(context, kind))
    }

    private fun pending(context: Context, kind: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_KIND, kind)
        return PendingIntent.getBroadcast(context, kind, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    internal fun show(context: Context, kind: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val (title, text) = when (kind) {
            KIND_CHEST -> "Baú de suprimentos pronto!" to "Seus diamantes grátis estão esperando na base."
            else -> "A horda voltou!" to "Seu prêmio diário e novas missões já estão disponíveis."
        }
        val open = PendingIntent.getActivity(
            context, 10, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(kind, notification)
    }

    internal fun kindOf(intent: Intent): Int = intent.getIntExtra(EXTRA_KIND, KIND_DAILY)
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.show(context, Reminders.kindOf(intent))
    }
}
