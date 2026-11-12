package ec.jat.bateria

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Process
import java.util.Calendar
import java.util.Locale

/**
 * Calcula qué apps gastan más batería.
 *
 * Android no deja que una app normal lea el gasto real de batería por app,
 * así que se ESTIMA con lo que sí se puede leer:
 *  - tiempo con la app en pantalla
 *  - tiempo trabajando en segundo plano (servicio activo: música, GPS, sincronizar…)
 *  - datos que la app usa en segundo plano (móviles y WiFi)
 */
object Consumo {

    val LOCALE: Locale = Locale("es", "EC")
    const val MB = 1024.0 * 1024.0
    const val HORA = 60.0 * 60 * 1000

    // Factores aproximados para un celular de gama media con batería de 5000 mAh
    const val PCT_POR_HORA_PANTALLA = 10.0      // pantalla encendida usando la app
    const val PCT_POR_HORA_SEGUNDO_PLANO = 3.0  // servicio en segundo plano
    const val PCT_POR_MB_FONDO_MOVIL = 0.08     // datos móviles en segundo plano
    const val PCT_POR_MB_FONDO_WIFI = 0.03      // WiFi en segundo plano

    data class AppGasto(
        val paquete: String,
        val nombre: String,
        val pantallaMs: Long,
        val segundoPlanoMs: Long,
        val fondoMovilBytes: Long,
        val fondoWifiBytes: Long,
        val totalDatosBytes: Long,
        val esSistema: Boolean
    ) {
        val pctEstimado: Double
            get() = pantallaMs / HORA * PCT_POR_HORA_PANTALLA +
                segundoPlanoMs / HORA * PCT_POR_HORA_SEGUNDO_PLANO +
                fondoMovilBytes / MB * PCT_POR_MB_FONDO_MOVIL +
                fondoWifiBytes / MB * PCT_POR_MB_FONDO_WIFI

        /** Trabaja mucho sin que la estés usando. */
        val sospechosa: Boolean
            get() {
                val fondoMb = (fondoMovilBytes + fondoWifiBytes) / MB
                val fondoPct = segundoPlanoMs / HORA * PCT_POR_HORA_SEGUNDO_PLANO +
                    fondoMovilBytes / MB * PCT_POR_MB_FONDO_MOVIL +
                    fondoWifiBytes / MB * PCT_POR_MB_FONDO_WIFI
                return (segundoPlanoMs > 30 * 60 * 1000L || fondoMb > 50) && fondoPct > pctEstimado * 0.4
            }
    }

    // ---------------------------------------------------------------- permiso

    fun tienePermiso(ctx: Context): Boolean {
        val aom = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val modo = aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return modo == AppOpsManager.MODE_ALLOWED
    }

    // ---------------------------------------------------------------- periodos

    fun inicioDeHoy(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    // ---------------------------------------------------------------- cálculo

    private class Acum {
        var pantalla = 0L
        var fondo = 0L
        var fondoMovil = 0L
        var fondoWifi = 0L
        var datos = 0L
    }

    fun ranking(ctx: Context, desde: Long, hasta: Long): List<AppGasto> {
        val acum = HashMap<String, Acum>()
        fun de(p: String): Acum = acum.getOrPut(p) { Acum() }

        // 1) Tiempo en pantalla y en segundo plano
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val stats = usm.queryAndAggregateUsageStats(desde, hasta)
            for ((pkg, s) in stats) {
                val a = de(pkg)
                a.pantalla += s.totalTimeInForeground
                a.fondo += s.totalTimeForegroundServiceUsed
            }
        } catch (e: Exception) { }

        // 2) Datos por app (separando primer plano y segundo plano)
        val pm = ctx.packageManager
        val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        for (tipo in intArrayOf(ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_WIFI)) {
            try {
                val st = nsm.querySummary(tipo, null, desde, hasta)
                val b = NetworkStats.Bucket()
                while (st.hasNextBucket()) {
                    st.getNextBucket(b)
                    val bytes = b.rxBytes + b.txBytes
                    if (bytes <= 0) continue
                    val pkg = paqueteDeUid(pm, b.uid) ?: continue
                    val a = de(pkg)
                    a.datos += bytes
                    if (b.state != NetworkStats.Bucket.STATE_FOREGROUND) {
                        if (tipo == ConnectivityManager.TYPE_MOBILE) a.fondoMovil += bytes else a.fondoWifi += bytes
                    }
                }
                st.close()
            } catch (e: Exception) { }
        }

        // 3) Armar la lista
        val propio = ctx.packageName
        val lista = ArrayList<AppGasto>()
        for ((pkg, a) in acum) {
            if (pkg == propio) continue
            var nombre = pkg
            var sistema = false
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                nombre = pm.getApplicationLabel(info).toString()
                sistema = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            } catch (e: PackageManager.NameNotFoundException) {
                if (pkg.startsWith("uid:")) nombre = pkg.removePrefix("uid:")
                sistema = true
            }
            val g = AppGasto(pkg, nombre, a.pantalla, a.fondo, a.fondoMovil, a.fondoWifi, a.datos, sistema)
            if (g.pctEstimado >= 0.05) lista.add(g)
        }
        lista.sortByDescending { it.pctEstimado }
        return lista
    }

    private fun paqueteDeUid(pm: PackageManager, uid: Int): String? {
        return when (uid) {
            NetworkStats.Bucket.UID_REMOVED -> "uid:Apps desinstaladas"
            NetworkStats.Bucket.UID_TETHERING -> "uid:Compartir internet (hotspot)"
            Process.SYSTEM_UID, 0 -> "uid:Sistema Android"
            else -> {
                val pkgs = try { pm.getPackagesForUid(uid) } catch (e: Exception) { null }
                if (pkgs == null || pkgs.isEmpty()) "uid:Sistema Android"
                else if (pkgs.size > 1) "uid:Servicios del sistema"
                else pkgs[0]
            }
        }
    }

    // ---------------------------------------------------------------- formato

    fun horas(ms: Long): String {
        val min = ms / 60000
        return when {
            min < 1 -> "0 min"
            min < 60 -> "$min min"
            else -> "${min / 60} h ${min % 60} min"
        }
    }

    fun mb(bytes: Long): String {
        val m = bytes / MB
        return if (m >= 1024) String.format(LOCALE, "%.2f GB", m / 1024) else String.format(LOCALE, "%.0f MB", m)
    }

    fun pct(v: Double): String = String.format(LOCALE, "%.1f %%", v)
}
