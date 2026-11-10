package ec.jat.controldatos

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : Activity() {

    private val AZUL = Color.parseColor("#1565C0")
    private val VERDE = Color.parseColor("#2E7D32")
    private val NARANJA = Color.parseColor("#EF6C00")
    private val ROJO = Color.parseColor("#C62828")
    private val GRIS = Color.parseColor("#757575")
    private val TEXTO = Color.parseColor("#212121")
    private val FONDO = Color.parseColor("#EEF2F7")

    private lateinit var p: Prefs
    private lateinit var cont: LinearLayout
    private val trabajo = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Prefs(this)
        Notifier.crearCanales(this)
        window.statusBarColor = AZUL

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(FONDO)
        scroll.fitsSystemWindows = true
        cont = LinearLayout(this)
        cont.orientation = LinearLayout.VERTICAL
        cont.setPadding(dp(14), dp(14), dp(14), dp(40))
        scroll.addView(cont)
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refrescar()
        if (DataUsage.tienePermiso(this)) MonitorService.iniciar(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            consultarSaldoCnt()
        }
    }

    // ------------------------------------------------------------------ datos

    private fun refrescar() {
        val ctx = applicationContext
        trabajo.execute {
            val permiso = DataUsage.tienePermiso(ctx)
            val estado = if (permiso) DataUsage.estado(ctx, p) else null
            val desde = if (p.configurado) p.calibTime else DataUsage.inicioDelDia(System.currentTimeMillis())
            val apps = if (permiso) DataUsage.listaApps(ctx, p.gratis, desde, System.currentTimeMillis()) else emptyList()
            main.post { if (!isFinishing) dibujar(permiso, estado, apps) }
        }
        MonitorService.iniciar(this)
    }

    // ------------------------------------------------------------------ pantalla

    private fun dibujar(permiso: Boolean, e: DataUsage.Estado?, apps: List<DataUsage.AppUso>) {
        cont.removeAllViews()
        cont.addView(texto("Control de Datos", 24f, AZUL, true))
        cont.addView(texto("Tu plan CNT bajo control", 14f, GRIS).apply { setPadding(0, 0, 0, dp(10)) })

        if (!permiso) {
            val c = tarjeta()
            c.addView(texto("Paso 1: dar permiso", 18f, TEXTO, true))
            c.addView(texto(
                "Para medir cuántos datos usa cada app, Android pide activar \"Acceso a datos de uso\" " +
                    "para Control de Datos. Pulsa el botón, busca \"Control de Datos\" y actívalo. Luego vuelve aquí.",
                15f, TEXTO
            ))
            c.addView(boton("Dar permiso") {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            })
            return
        }
        if (e == null) return

        // --- saldo ---
        val c1 = tarjeta()
        if (!e.configurado) {
            c1.addView(texto("Paso 2: registra tu saldo", 18f, TEXTO, true))
            c1.addView(texto(
                "Consulta tu saldo con el código de CNT (o pon el número que te salió) y la fecha en que vence tu paquete.",
                15f, TEXTO
            ))
            c1.addView(boton("Consultar saldo CNT automáticamente") { consultarSaldoCnt() })
            c1.addView(boton("Escribir saldo a mano", secundario = true) { dialogoSaldo(null) })
            c1.addView(texto("Hoy llevas ${DataUsage.fmt(e.usadoHoyMb)} en datos móviles.", 14f, GRIS).apply {
                setPadding(0, dp(8), 0, 0)
            })
        } else {
            val color = when {
                e.pct <= 10 -> ROJO
                e.pct <= 25 -> NARANJA
                else -> VERDE
            }
            c1.addView(texto("Te quedan", 14f, GRIS))
            c1.addView(texto(DataUsage.fmt(e.restanteMb), 40f, color, true))
            c1.addView(barra(e.pct / 100.0, color))
            c1.addView(texto(
                String.format(DataUsage.LOCALE, "%.0f%% de %s", e.pct, DataUsage.fmt(e.paqueteMb)),
                13f, GRIS
            ))
            val venceTxt = when {
                e.diasRestantes <= 0 -> "⚠️ Tu paquete ya venció (${DataUsage.fecha(e.venceTime)})"
                e.diasRestantes == 1 -> "📅 Vence HOY"
                else -> "📅 Vence en ${e.diasRestantes} días (${DataUsage.fecha(e.venceTime)})"
            }
            c1.addView(texto(venceTxt, 16f, TEXTO, true).apply { setPadding(0, dp(10), 0, 0) })

            // --- hoy ---
            val c2 = tarjeta()
            c2.addView(texto("Consumo de hoy", 18f, TEXTO, true))
            val frac = if (e.permitidoHoyMb > 0) e.usadoHoyMb / e.permitidoHoyMb else 1.0
            val colorHoy = when {
                frac >= 1.0 -> ROJO
                frac >= 0.8 -> NARANJA
                else -> VERDE
            }
            c2.addView(texto(
                "${DataUsage.fmt(e.usadoHoyMb)} de ${DataUsage.fmt(e.permitidoHoyMb)} permitidos",
                20f, colorHoy, true
            ))
            c2.addView(barra(frac, colorHoy))
            val consejo = if (frac >= 1.0)
                "Ya pasaste lo recomendado para hoy. Si sigues así, el plan no te alcanzará hasta el corte."
            else
                "Puedes usar hasta ${DataUsage.fmt(e.permitidoHoyMb)} por día para que te alcance hasta el corte. " +
                    "Te quedan ${DataUsage.fmt(e.permitidoHoyMb - e.usadoHoyMb)} para hoy."
            c2.addView(texto(consejo, 14f, GRIS))
        }

        // --- conexión y aviso wifi ---
        val c3 = tarjeta()
        val conTxt = when (e.conexion) {
            "wifi" -> "🛜 Conectado por WiFi (no gastas datos)"
            "datos" -> "📶 Usando DATOS MÓVILES"
            else -> "Sin conexión"
        }
        c3.addView(texto(conTxt, 17f, if (e.conexion == "datos") NARANJA else VERDE, true))
        val sw = Switch(this)
        sw.text = "Avisarme cuando se desconecte el WiFi"
        sw.textSize = 15f
        sw.setTextColor(TEXTO)
        sw.isChecked = p.avisoWifi
        sw.setPadding(0, dp(10), 0, 0)
        sw.setOnCheckedChangeListener { _, v -> p.avisoWifi = v }
        c3.addView(sw)

        // --- acciones ---
        if (e.configurado) {
            val c4 = tarjeta()
            c4.addView(texto("Actualizar saldo", 18f, TEXTO, true))
            c4.addView(texto(
                "Hazlo cuando compres un paquete nuevo o si ves diferencia con lo que dice CNT.",
                14f, GRIS
            ))
            c4.addView(boton("Consultar saldo CNT automáticamente") { consultarSaldoCnt() })
            c4.addView(boton("Escribir saldo a mano", secundario = true) { dialogoSaldo(null) })
        }

        // --- apps ---
        val c5 = tarjeta()
        val titulo = if (e.configurado) "Consumo por app (desde ${DataUsage.fecha(p.calibTime)})" else "Consumo por app (hoy)"
        c5.addView(texto(titulo, 18f, TEXTO, true))
        c5.addView(texto("Toca una app para marcarla como gratis (no descuenta de tu plan) o quitarla.", 13f, GRIS))
        val visibles = apps.filter { it.bytes >= 100 * 1024 }.take(25)
        if (visibles.isEmpty()) {
            c5.addView(texto("Todavía no hay consumo de datos móviles registrado.", 14f, GRIS).apply {
                setPadding(0, dp(8), 0, 0)
            })
        }
        val maxBytes = visibles.firstOrNull()?.bytes ?: 1L
        for (a in visibles) c5.addView(filaApp(a, maxBytes))

        // --- ajustes ---
        val c6 = tarjeta()
        c6.addView(texto("Ajustes", 18f, TEXTO, true))
        c6.addView(boton("Código de consulta CNT: ${if (p.ussdCode.isBlank()) "(sin configurar)" else p.ussdCode}", secundario = true) {
            dialogoCodigo(false)
        })
        c6.addView(boton("Evitar que el celular cierre la app", secundario = true) { pedirSinRestriccionBateria() })
        c6.addView(texto(
            "En celulares Xiaomi/Redmi activa también \"Inicio automático\" para Control de Datos en Ajustes → Apps.",
            13f, GRIS
        ))
    }

    private fun filaApp(a: DataUsage.AppUso, maxBytes: Long): View {
        val fila = LinearLayout(this)
        fila.orientation = LinearLayout.VERTICAL
        fila.setPadding(0, dp(10), 0, dp(4))
        val arriba = LinearLayout(this)
        arriba.orientation = LinearLayout.HORIZONTAL
        val nombre = texto(a.nombre + if (a.gratis) "  · gratis" else "", 15f, if (a.gratis) GRIS else TEXTO)
        nombre.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val mb = texto(DataUsage.fmt(a.bytes / DataUsage.MB), 15f, if (a.gratis) GRIS else TEXTO, true)
        arriba.addView(nombre)
        arriba.addView(mb)
        fila.addView(arriba)
        fila.addView(barra(a.bytes.toDouble() / maxBytes, if (a.gratis) Color.parseColor("#BDBDBD") else AZUL, alto = 5))
        if (a.paquete != null) {
            fila.isClickable = true
            fila.setOnClickListener { alternarGratis(a) }
        }
        return fila
    }

    // ------------------------------------------------------------------ acciones

    private fun alternarGratis(a: DataUsage.AppUso) {
        val pkg = a.paquete ?: return
        val pm = packageManager
        val paquetes = try { pm.getPackagesForUid(a.uid)?.toList() ?: listOf(pkg) } catch (e: Exception) { listOf(pkg) }
        val msg = if (a.gratis)
            "¿Quitar \"${a.nombre}\" de las apps gratis? Su consumo volverá a descontarse de tu plan."
        else
            "¿Marcar \"${a.nombre}\" como gratis? Su consumo ya no se descontará de tu plan."
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("Sí") { _, _ ->
                val g = p.gratis.toMutableSet()
                if (a.gratis) g.removeAll(paquetes.toSet()) else g.addAll(paquetes)
                p.gratis = g
                refrescar()
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun consultarSaldoCnt() {
        if (p.ussdCode.isBlank()) {
            dialogoCodigo(true)
            return
        }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 2)
            return
        }
        Toast.makeText(this, "Consultando a CNT…", Toast.LENGTH_SHORT).show()
        Ussd.consultar(this, p.ussdCode, { respuesta ->
            val s = Ussd.interpretar(respuesta)
            if (s == null) {
                AlertDialog.Builder(this)
                    .setTitle("Respuesta de CNT")
                    .setMessage("$respuesta\n\nNo pude leer el saldo automáticamente. Escríbelo a mano.")
                    .setPositiveButton("Escribir a mano") { _, _ -> dialogoSaldo(null) }
                    .setNegativeButton("Cerrar", null)
                    .show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Respuesta de CNT")
                    .setMessage(respuesta)
                    .setPositiveButton("Usar este saldo") { _, _ -> dialogoSaldo(s) }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }, { err ->
            AlertDialog.Builder(this)
                .setMessage(err)
                .setPositiveButton("Escribir a mano") { _, _ -> dialogoSaldo(null) }
                .setNegativeButton("Cerrar", null)
                .show()
        })
    }

    private fun dialogoCodigo(luegoConsultar: Boolean) {
        val et = EditText(this)
        et.hint = "Ejemplo: *611#"
        et.setText(p.ussdCode)
        et.inputType = InputType.TYPE_CLASS_PHONE
        val caja = LinearLayout(this)
        caja.orientation = LinearLayout.VERTICAL
        caja.setPadding(dp(20), dp(8), dp(20), 0)
        caja.addView(texto("Escribe el código que marcas para ver tu saldo de datos CNT (el que te muestra \"Le quedan … MB\").", 14f, TEXTO))
        caja.addView(et)
        AlertDialog.Builder(this)
            .setTitle("Código de consulta")
            .setView(caja)
            .setPositiveButton("Guardar") { _, _ ->
                p.ussdCode = et.text.toString().trim()
                refrescar()
                if (luegoConsultar && p.ussdCode.isNotBlank()) consultarSaldoCnt()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun dialogoSaldo(sugerido: Ussd.Saldo?) {
        val caja = LinearLayout(this)
        caja.orientation = LinearLayout.VERTICAL
        caja.setPadding(dp(20), dp(8), dp(20), 0)

        caja.addView(texto("Saldo que te queda (MB)", 14f, GRIS))
        val etSaldo = EditText(this)
        etSaldo.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        etSaldo.hint = "Ej: 20473.99"
        sugerido?.let { etSaldo.setText(String.format(java.util.Locale.US, "%.2f", it.restanteMb)) }
        caja.addView(etSaldo)

        caja.addView(texto("Tamaño total del paquete (MB)", 14f, GRIS))
        val etPaq = EditText(this)
        etPaq.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        etPaq.hint = "Ej: 20480 (= 20 GB)"
        val paqSug = sugerido?.paqueteMb?.takeIf { it > 0 } ?: p.paqueteMb.takeIf { it > 0 }
        paqSug?.let { etPaq.setText(String.format(java.util.Locale.US, "%.0f", it)) }
        caja.addView(etPaq)

        caja.addView(texto("Fecha de vencimiento", 14f, GRIS))
        val cal = Calendar.getInstance()
        if (p.venceTime > System.currentTimeMillis()) cal.timeInMillis = p.venceTime
        else cal.add(Calendar.DAY_OF_YEAR, 30)
        val btFecha = Button(this)
        btFecha.isAllCaps = false
        btFecha.text = DataUsage.fecha(cal.timeInMillis)
        btFecha.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d)
                btFecha.text = DataUsage.fecha(cal.timeInMillis)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        caja.addView(btFecha)
        caja.addView(texto("Si compraste el paquete hoy, son 30 días desde hoy.", 12f, GRIS))

        AlertDialog.Builder(this)
            .setTitle("Registrar saldo")
            .setView(caja)
            .setPositiveButton("Guardar") { _, _ ->
                val saldo = etSaldo.text.toString().replace(",", ".").toDoubleOrNull()
                val paq = etPaq.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
                if (saldo == null || saldo < 0) {
                    Toast.makeText(this, "Escribe un saldo válido", Toast.LENGTH_LONG).show()
                } else {
                    p.registrarSaldo(saldo, paq, DataUsage.finDelDia(cal.timeInMillis))
                    Toast.makeText(this, "Saldo guardado", Toast.LENGTH_SHORT).show()
                    refrescar()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    @SuppressLint("BatteryLife")
    private fun pedirSinRestriccionBateria() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Listo: la app ya no tiene restricción de batería", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e: Exception) { }
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    // ------------------------------------------------------------------ piezas de UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun tarjeta(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        val fondo = GradientDrawable()
        fondo.setColor(Color.WHITE)
        fondo.cornerRadius = dp(16).toFloat()
        c.background = fondo
        c.elevation = dp(2).toFloat()
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(12))
        c.layoutParams = lp
        cont.addView(c)
        return c
    }

    private fun texto(t: String, size: Float, color: Int, negrita: Boolean = false): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = size
        tv.setTextColor(color)
        if (negrita) tv.setTypeface(tv.typeface, Typeface.BOLD)
        return tv
    }

    private fun barra(frac: Double, color: Int, alto: Int = 10): ProgressBar {
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        pb.max = 1000
        pb.progress = (min(1.0, maxOf(0.0, frac)) * 1000).toInt()
        pb.progressTintList = ColorStateList.valueOf(color)
        pb.progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#DDE3EA"))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(alto))
        lp.setMargins(0, dp(6), 0, dp(6))
        pb.layoutParams = lp
        return pb
    }

    private fun boton(t: String, secundario: Boolean = false, accion: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.textSize = 15f
        b.gravity = Gravity.CENTER
        val fondo = GradientDrawable()
        fondo.cornerRadius = dp(12).toFloat()
        if (secundario) {
            fondo.setColor(Color.parseColor("#E3ECF8"))
            b.setTextColor(AZUL)
        } else {
            fondo.setColor(AZUL)
            b.setTextColor(Color.WHITE)
        }
        b.background = fondo
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(8), 0, 0)
        b.layoutParams = lp
        b.setOnClickListener { accion() }
        return b
    }
}
