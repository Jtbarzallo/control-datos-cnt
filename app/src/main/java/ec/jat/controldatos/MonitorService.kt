package ec.jat.controldatos

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Executors

class MonitorService : Service() {

    companion object {
        private const val INTERVALO = 15L * 60 * 1000
        private const val PAUSA_AVISO_WIFI = 2L * 60 * 1000

        fun iniciar(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, MonitorService::class.java))
            } catch (e: Exception) {
                // el sistema no permitió iniciarlo ahora (se reintenta al abrir la app)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val trabajo = Executors.newSingleThreadExecutor()
    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var ultimaConexion: String? = null
    private var ultimoAvisoWifi = 0L

    private val tic = object : Runnable {
        override fun run() {
            actualizar()
            handler.postDelayed(this, INTERVALO)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.crearCanales(this)
        val n = Notifier.estado(this, "Control de Datos", "Calculando tu saldo…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.ID_ESTADO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.ID_ESTADO, n)
        }

        ultimaConexion = DataUsage.conexionActual(this)
        val manager = getSystemService(ConnectivityManager::class.java)
        cm = manager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val nueva = DataUsage.tipo(caps)
                handler.post { cambioDeRed(nueva) }
            }
        }
        try {
            manager.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (e: Exception) {
            callback = null
        }
        handler.post(tic)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        actualizar()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        callback?.let { try { cm?.unregisterNetworkCallback(it) } catch (e: Exception) { } }
        trabajo.shutdown()
        super.onDestroy()
    }

    private fun cambioDeRed(nueva: String?) {
        if (nueva == null) return  // transición: se mantiene la última conocida
        val anterior = ultimaConexion
        ultimaConexion = nueva
        if (anterior == nueva) return
        val p = Prefs(this)
        if (anterior == "wifi" && nueva == "datos" && p.avisoWifi) {
            val ahora = System.currentTimeMillis()
            if (ahora - ultimoAvisoWifi > PAUSA_AVISO_WIFI) {
                ultimoAvisoWifi = ahora
                avisarWifi()
            }
        }
        actualizar()
    }

    private fun avisarWifi() {
        val ctx = applicationContext
        trabajo.execute {
            val p = Prefs(ctx)
            val texto = if (p.configurado && DataUsage.tienePermiso(ctx)) {
                val e = DataUsage.estado(ctx, p)
                "Ahora estás usando datos móviles. Te quedan ${DataUsage.fmt(e.restanteMb)}. " +
                    "Hoy llevas ${DataUsage.fmt(e.usadoHoyMb)} de ${DataUsage.fmt(e.permitidoHoyMb)}."
            } else {
                "Ahora estás usando datos móviles."
            }
            Notifier.alerta(ctx, Notifier.CANAL_WIFI, Notifier.ID_WIFI, "📶 Se desconectó el WiFi", texto)
        }
    }

    private fun actualizar() {
        val ctx = applicationContext
        val conexion = ultimaConexion
        try {
            trabajo.execute { revisar(ctx, conexion) }
        } catch (e: Exception) {
            // servicio cerrándose
        }
    }

    private fun revisar(ctx: Context, conexion: String?) {
        val p = Prefs(ctx)
        val red = when (conexion) {
            "wifi" -> "Conectado por WiFi"
            "datos" -> "Usando DATOS MÓVILES"
            else -> "Sin conexión"
        }
        if (!DataUsage.tienePermiso(ctx)) {
            Notifier.actualizarEstado(ctx, "Control de Datos", "Abre la app y da el permiso de uso de datos. · $red")
            return
        }
        val e = DataUsage.estado(ctx, p)
        if (!e.configurado) {
            Notifier.actualizarEstado(
                ctx, "Hoy: ${DataUsage.fmt(e.usadoHoyMb)} en datos móviles",
                "Abre la app y registra tu saldo para ver cuánto te queda. · $red"
            )
            return
        }
        val titulo = "Te quedan ${DataUsage.fmt(e.restanteMb)} · ${e.diasRestantes} días"
        val texto = "Hoy: ${DataUsage.fmt(e.usadoHoyMb)} de ${DataUsage.fmt(e.permitidoHoyMb)} permitidos · $red"
        Notifier.actualizarEstado(ctx, titulo, texto)

        val hoy = DataUsage.hoyClave()

        // 1) límite diario
        if (e.permitidoHoyMb > 0 && e.usadoHoyMb > e.permitidoHoyMb && p.ultimoAvisoDiario != hoy) {
            p.ultimoAvisoDiario = hoy
            Notifier.alerta(
                ctx, Notifier.CANAL_ALERTAS, Notifier.ID_DIARIO, "⚠️ Superaste tu consumo de hoy",
                "Llevas ${DataUsage.fmt(e.usadoHoyMb)} y lo recomendado era ${DataUsage.fmt(e.permitidoHoyMb)} " +
                    "para que el plan te alcance hasta el ${DataUsage.fecha(e.venceTime)}."
            )
        }

        // 2) saldo bajo
        for (umbral in listOf(5, 10, 25, 50)) {
            if (e.pct <= umbral && p.ultimoAvisoPct > umbral) {
                p.ultimoAvisoPct = umbral
                Notifier.alerta(
                    ctx, Notifier.CANAL_ALERTAS, Notifier.ID_PCT, "🔋 Te queda el $umbral% de tus datos",
                    "Quedan ${DataUsage.fmt(e.restanteMb)} de ${DataUsage.fmt(e.paqueteMb)}. Vence el ${DataUsage.fecha(e.venceTime)}."
                )
                break
            }
        }

        // 3) vencimiento cercano
        if (e.diasRestantes in 1..2 && p.ultimoAvisoVence != hoy) {
            p.ultimoAvisoVence = hoy
            val cuando = if (e.diasRestantes == 1) "hoy" else "mañana"
            Notifier.alerta(
                ctx, Notifier.CANAL_ALERTAS, Notifier.ID_VENCE, "📅 Tu paquete vence $cuando",
                "Te quedan ${DataUsage.fmt(e.restanteMb)} sin usar. Si no los usas, se pierden."
            )
        }
    }
}
