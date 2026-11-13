package ec.jat.musica

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

/**
 * "Pasar Música" — se instala en la RADIO Android del carro.
 * Muestra una dirección; en el celular se abre esa dirección en Chrome
 * y se eligen las canciones a enviar. Se guardan en Música/Del celular.
 */
class MainActivity : Activity(), Servidor.Avisos {

    private val MORADO = Color.parseColor("#6A1B9A")
    private val VERDE = Color.parseColor("#66BB6A")
    private val ROJO = Color.parseColor("#EF5350")
    private val AMARILLO = Color.parseColor("#FFD54F")
    private val GRIS = Color.parseColor("#B0BEC5")
    private val FONDO = Color.parseColor("#121212")
    private val TARJETA = Color.parseColor("#1E1E1E")

    private val main = Handler(Looper.getMainLooper())
    private var servidor: Servidor? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private lateinit var txtDireccion: TextView
    private lateinit var txtEstado: TextView
    private lateinit var barra: ProgressBar
    private lateinit var txtActual: TextView
    private lateinit var listaRecibidas: LinearLayout
    private lateinit var txtResumen: TextView
    private lateinit var btnPermiso: Button

    private var recibidas = 0
    private var bytesRecibidos = 0L

    private val refrescoIp = object : Runnable {
        override fun run() {
            mostrarDireccion()
            main.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = FONDO
        construirPantalla()
        pedirPermisos()
        iniciarServidor()
    }

    override fun onResume() {
        super.onResume()
        main.post(refrescoIp)
        btnPermiso.visibility = if (faltaPermiso()) View.VISIBLE else View.GONE
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(refrescoIp)
    }

    override fun onDestroy() {
        servidor?.detener()
        try { wifiLock?.release() } catch (e: Exception) { }
        super.onDestroy()
    }

    // ---------- Servidor ----------

    private fun iniciarServidor() {
        try {
            val s = Servidor(applicationContext, this)
            s.iniciar()
            servidor = s
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pasar-musica").also { it.acquire() }
            txtEstado.text = "✅ Listo para recibir canciones"
            txtEstado.setTextColor(VERDE)
        } catch (e: Exception) {
            txtEstado.text = "❌ No se pudo iniciar: ${e.message}"
            txtEstado.setTextColor(ROJO)
        }
        mostrarDireccion()
    }

    private fun direcciones(): List<String> {
        val lista = ArrayList<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses.toList()) {
                    if (a is Inet4Address && !a.isLoopbackAddress) a.hostAddress?.let { lista.add(it) }
                }
            }
        } catch (e: Exception) { }
        // Primero las de WiFi típicas (192.168.x.x / 10.x.x.x)
        return lista.sortedBy { if (it.startsWith("192.168.")) 0 else if (it.startsWith("10.")) 1 else 2 }
    }

    private fun mostrarDireccion() {
        val s = servidor ?: return
        val ips = direcciones()
        if (ips.isEmpty()) {
            txtDireccion.text = "Conecta la radio al WiFi\n(o al punto de acceso de tu celular)"
            txtDireccion.textSize = 22f
            txtDireccion.setTextColor(AMARILLO)
        } else {
            txtDireccion.text = ips.joinToString("\n") { "$it:${s.puerto}" }
            txtDireccion.textSize = 40f
            txtDireccion.setTextColor(Color.WHITE)
        }
    }

    // ---------- Avisos desde el servidor (otro hilo) ----------

    override fun recibiendo(nombre: String, hecho: Long, total: Long) {
        main.post {
            txtActual.text = "⬇️ Recibiendo: $nombre  (${mb(hecho)} de ${mb(total)})"
            barra.visibility = View.VISIBLE
            barra.max = 1000
            barra.progress = if (total > 0) (hecho * 1000 / total).toInt() else 0
        }
    }

    override fun recibida(nombre: String, ruta: String, bytes: Long) {
        main.post {
            recibidas++
            bytesRecibidos += bytes
            barra.visibility = View.GONE
            txtActual.text = ""
            agregarFila("✅ $nombre", VERDE)
            txtResumen.text = "$recibidas canciones recibidas (${mb(bytesRecibidos)}) · Guardadas en Música/${Guardado.SUBCARPETA}"
        }
    }

    override fun fallo(nombre: String, motivo: String) {
        main.post {
            barra.visibility = View.GONE
            txtActual.text = ""
            agregarFila("❌ $nombre — $motivo", ROJO)
        }
    }

    // ---------- Permisos ----------

    private fun permisoNecesario(): String? = when {
        Build.VERSION.SDK_INT >= 33 -> Manifest.permission.READ_MEDIA_AUDIO
        Build.VERSION.SDK_INT >= 29 -> Manifest.permission.READ_EXTERNAL_STORAGE
        Build.VERSION.SDK_INT >= 23 -> Manifest.permission.WRITE_EXTERNAL_STORAGE
        else -> null
    }

    private fun faltaPermiso(): Boolean {
        val p = permisoNecesario() ?: return false
        return Build.VERSION.SDK_INT >= 23 && checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED
    }

    private fun pedirPermisos() {
        val p = permisoNecesario() ?: return
        if (Build.VERSION.SDK_INT >= 23 && faltaPermiso()) requestPermissions(arrayOf(p), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        btnPermiso.visibility = if (faltaPermiso()) View.VISIBLE else View.GONE
    }

    // ---------- Pantalla ----------

    private fun construirPantalla() {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(FONDO)
        scroll.fitsSystemWindows = true
        val cont = LinearLayout(this)
        cont.orientation = LinearLayout.VERTICAL
        cont.setPadding(dp(20), dp(16), dp(20), dp(40))
        scroll.addView(cont)

        cont.addView(texto("🎵 Pasar Música", 26f, Color.WHITE, true))
        cont.addView(texto("Recibe canciones de tu celular y las guarda en la memoria de esta radio.", 15f, GRIS, false))

        txtEstado = texto("Iniciando…", 18f, AMARILLO, true)
        txtEstado.setPadding(0, dp(12), 0, dp(6))
        cont.addView(txtEstado)

        val tarjeta = LinearLayout(this)
        tarjeta.orientation = LinearLayout.VERTICAL
        tarjeta.gravity = Gravity.CENTER_HORIZONTAL
        tarjeta.setPadding(dp(16), dp(16), dp(16), dp(16))
        tarjeta.background = GradientDrawable().apply { setColor(MORADO); cornerRadius = dp(14).toFloat() }
        tarjeta.addView(texto("En tu celular abre Chrome y escribe arriba:", 17f, Color.WHITE, false).apply { gravity = Gravity.CENTER })
        txtDireccion = texto("…", 40f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(8))
        }
        tarjeta.addView(txtDireccion)
        tarjeta.addView(texto("El celular y la radio deben estar en el mismo WiFi.\nLo más fácil: prende el punto de acceso (hotspot) del celular y conecta la radio a él.", 14f, Color.parseColor("#E1BEE7"), false).apply { gravity = Gravity.CENTER })
        cont.addView(tarjeta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        btnPermiso = Button(this).apply {
            text = "Dar permiso para ver la música de la radio"
            setOnClickListener { pedirPermisos() }
            visibility = View.GONE
        }
        cont.addView(btnPermiso, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        txtActual = texto("", 16f, AMARILLO, false)
        txtActual.setPadding(0, dp(14), 0, dp(4))
        cont.addView(txtActual)
        barra = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        barra.visibility = View.GONE
        cont.addView(barra, LinearLayout.LayoutParams(-1, dp(18)))

        txtResumen = texto("Todavía no has enviado canciones.", 15f, GRIS, false)
        txtResumen.setPadding(0, dp(12), 0, dp(6))
        cont.addView(txtResumen)

        listaRecibidas = LinearLayout(this)
        listaRecibidas.orientation = LinearLayout.VERTICAL
        listaRecibidas.setPadding(dp(12), dp(8), dp(12), dp(8))
        listaRecibidas.background = GradientDrawable().apply { setColor(TARJETA); cornerRadius = dp(10).toFloat() }
        cont.addView(listaRecibidas)

        setContentView(scroll)
    }

    private fun agregarFila(t: String, color: Int) {
        listaRecibidas.addView(texto(t, 15f, color, false).apply { setPadding(0, dp(4), 0, dp(4)) }, 0)
    }

    private fun texto(t: String, tam: Float, color: Int, negrita: Boolean) = TextView(this).apply {
        text = t
        textSize = tam
        setTextColor(color)
        if (negrita) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun mb(b: Long) = String.format(Locale.US, "%.1f MB", b / 1048576.0)
}
