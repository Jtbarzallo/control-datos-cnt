package ec.jat.controldatos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifier {
    const val CANAL_ESTADO = "estado"
    const val CANAL_ALERTAS = "alertas"
    const val CANAL_WIFI = "wifi"

    const val ID_ESTADO = 1
    const val ID_WIFI = 2
    const val ID_DIARIO = 3
    const val ID_PCT = 4
    const val ID_VENCE = 5

    fun crearCanales(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANAL_ESTADO, "Saldo en la barra", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Muestra siempre cuántos datos te quedan"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CANAL_WIFI, "Aviso de WiFi desconectado", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Avisa cuando se pierde el WiFi y empiezas a usar datos"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CANAL_ALERTAS, "Alertas de consumo", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Límite diario, saldo bajo y vencimiento"
            }
        )
    }

    private fun abrirApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun estado(ctx: Context, titulo: String, texto: String): Notification =
        Notification.Builder(ctx, CANAL_ESTADO)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(abrirApp(ctx))
            .build()

    fun actualizarEstado(ctx: Context, titulo: String, texto: String) {
        ctx.getSystemService(NotificationManager::class.java).notify(ID_ESTADO, estado(ctx, titulo, texto))
    }

    fun alerta(ctx: Context, canal: String, id: Int, titulo: String, texto: String) {
        val n = Notification.Builder(ctx, canal)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setAutoCancel(true)
            .setContentIntent(abrirApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
