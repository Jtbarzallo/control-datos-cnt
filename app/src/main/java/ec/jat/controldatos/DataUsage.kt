package ec.jat.controldatos

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

object DataUsage {

    const val MB = 1024.0 * 1024.0
    const val DIA = 24L * 60 * 60 * 1000
    val LOCALE: Locale = Locale("es", "EC")

    data class AppUso(
        val uid: Int,
        val nombre: String,
        val paquete: String?,
        val bytes: Long,
        val gratis: Boolean
    )

    data class Estado(
        val configurado: Boolean,
        val restanteMb: Double,
        val paqueteMb: Double,
        val diasRestantes: Int,
        val venceTime: Long,
        val permitidoHoyMb: Double,
        val usadoHoyMb: Double,
        val usadoDesdeSaldoMb: Double,
        val conexion: String?
    ) {
        val pct: Double get() = if (paqueteMb > 0) restanteMb / paqueteMb * 100.0 else 0.0
    }

    // ---------- permisos ----------

    fun tienePermiso(ctx: Context): Boolean {
        val aom = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val modo = if (Build.VERSION.SDK_INT >= 29) {
            aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            modoAntiguo(aom, ctx)
        }
        return modo == AppOpsManager.MODE_ALLOWED
    }

    @Suppress("DEPRECATION")
    private fun modoAntiguo(aom: AppOpsManager, ctx: Context): Int =
        aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)

    // ---------- consumo ----------

    /** Bytes de datos móviles por UID entre dos momentos. */
    fun porUid(ctx: Context, desde: Long, hasta: Long): Map<Int, Long> {
        val res = HashMap<Int, Long>()
        if (hasta <= desde) return res
        try {
            val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val stats = nsm.querySummary(ConnectivityManager.TYPE_MOBILE, null, desde, hasta)
            val b = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(b)
                res[b.uid] = (res[b.uid] ?: 0L) + b.rxBytes + b.txBytes
            }
            stats.close()
        } catch (e: Exception) {
            // sin permiso o error del sistema: se devuelve vacío
        }
        return res
    }

    private fun paquetesDe(ctx: Context, uid: Int): Array<String> =
        try { ctx.packageManager.getPackagesForUid(uid) ?: emptyArray() } catch (e: Exception) { emptyArray() }

    fun esGratis(ctx: Context, uid: Int, gratis: Set<String>): Boolean =
        paquetesDe(ctx, uid).any { it in gratis }

    /** Bytes que sí descuentan del plan (excluye apps gratis). */
    fun usoQueCuenta(ctx: Context, gratis: Set<String>, desde: Long, hasta: Long): Long {
        var total = 0L
        for ((uid, bytes) in porUid(ctx, desde, hasta)) {
            if (!esGratis(ctx, uid, gratis)) total += bytes
        }
        return total
    }

    @Suppress("DEPRECATION")
    fun nombreDe(ctx: Context, uid: Int): Pair<String, String?> {
        when (uid) {
            NetworkStats.Bucket.UID_REMOVED -> return "Apps desinstaladas" to null
            NetworkStats.Bucket.UID_TETHERING -> return "Compartir internet (hotspot)" to null
            Process.SYSTEM_UID -> return "Sistema Android" to null
        }
        val pkgs = paquetesDe(ctx, uid)
        if (pkgs.isEmpty()) return "App del sistema ($uid)" to null
        val pm = ctx.packageManager
        for (pkg in pkgs) {
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                val nombre = pm.getApplicationLabel(info).toString()
                val extra = if (pkgs.size > 1) " (+${pkgs.size - 1})" else ""
                return (nombre + extra) to pkg
            } catch (e: PackageManager.NameNotFoundException) {
                // probar el siguiente
            }
        }
        return pkgs[0] to pkgs[0]
    }

    fun listaApps(ctx: Context, gratis: Set<String>, desde: Long, hasta: Long): List<AppUso> {
        val lista = ArrayList<AppUso>()
        for ((uid, bytes) in porUid(ctx, desde, hasta)) {
            if (bytes <= 0) continue
            val (nombre, pkg) = nombreDe(ctx, uid)
            lista.add(AppUso(uid, nombre, pkg, bytes, esGratis(ctx, uid, gratis)))
        }
        lista.sortByDescending { it.bytes }
        return lista
    }

    // ---------- estado general ----------

    fun inicioDelDia(t: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = t
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun finDelDia(t: Long): Long = inicioDelDia(t) + DIA - 1

    fun hoyClave(): String = SimpleDateFormat("yyyy-MM-dd", LOCALE).format(Date())

    fun conexionActual(ctx: Context): String? {
        return try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
            tipo(caps)
        } catch (e: Exception) { null }
    }

    fun tipo(caps: NetworkCapabilities): String? = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "datos"
        else -> null
    }

    fun estado(ctx: Context, p: Prefs): Estado {
        val ahora = System.currentTimeMillis()
        val conexion = conexionActual(ctx)
        val inicioHoy = inicioDelDia(ahora)
        val gratis = p.gratis
        val usadoHoy = usoQueCuenta(ctx, gratis, inicioHoy, ahora) / MB
        if (!p.configurado) {
            return Estado(false, 0.0, 0.0, 0, 0L, 0.0, usadoHoy, 0.0, conexion)
        }
        val calib = p.calibTime
        val usadoDesde = usoQueCuenta(ctx, gratis, calib, ahora) / MB
        val restante = max(0.0, p.saldoMb - usadoDesde)
        // lo usado hoy después de registrar el saldo (para saber cuánto había al empezar el día)
        val usadoHoyTrasSaldo = if (calib > inicioHoy) usoQueCuenta(ctx, gratis, calib, ahora) / MB else usadoHoy
        val restanteInicioHoy = restante + usadoHoyTrasSaldo
        val dias = max(0, ceil((p.venceTime - inicioHoy).toDouble() / DIA).toInt())
        val permitido = if (dias > 0) restanteInicioHoy / dias else 0.0
        return Estado(true, restante, p.paqueteMb, dias, p.venceTime, permitido, usadoHoy, usadoDesde, conexion)
    }

    // ---------- formato ----------

    fun fmt(mb: Double): String =
        if (mb >= 1024) String.format(LOCALE, "%.2f GB", mb / 1024.0)
        else String.format(LOCALE, "%.0f MB", mb)

    fun fecha(t: Long): String = SimpleDateFormat("EEE d 'de' MMM", LOCALE).format(Date(t))
}
