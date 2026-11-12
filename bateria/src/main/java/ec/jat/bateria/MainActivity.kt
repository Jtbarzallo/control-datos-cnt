package ec.jat.bateria

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : Activity() {

    private val VERDE = Color.parseColor("#2E7D32")
    private val AZUL = Color.parseColor("#1565C0")
    private val NARANJA = Color.parseColor("#EF6C00")
    private val ROJO = Color.parseColor("#C62828")
    private val GRIS = Color.parseColor("#757575")
    private val TEXTO = Color.parseColor("#212121")
    private val FONDO = Color.parseColor("#EEF3EE")

    private lateinit var cont: LinearLayout
    private val trabajo = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val prefs by lazy { getSharedPreferences("bateria", Context.MODE_PRIVATE) }

    /** 0 = hoy, 1 = últimas 24 h, 2 = últimos 7 días */
    private var periodo: Int
        get() = prefs.getInt("periodo", 1)
        set(v) = prefs.edit().putInt("periodo", v).apply()

    /** Capacidad original de la batería (Redmi Note 11 = 5000 mAh). */
    private var diseno: Int
        get() = prefs.getInt("diseno", 5000)
        set(v) = prefs.edit().putInt("diseno", v).apply()

    private var ultimoRespaldo: String
        get() = prefs.getString("ultimoRespaldo", "") ?: ""
        set(v) = prefs.edit().putString("ultimoRespaldo", v).apply()

    private var ultimaLista: List<Consumo.AppGasto> = emptyList()
    private var ultimaBateria: Bateria.Estado? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = VERDE
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(FONDO)
        scroll.fitsSystemWindows = true
        cont = LinearLayout(this)
        cont.orientation = LinearLayout.VERTICAL
        cont.setPadding(dp(14), dp(14), dp(14), dp(40))
        scroll.addView(cont)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refrescar()
    }

    private fun nombrePeriodo(p: Int) = when (p) {
        0 -> "Hoy"
        2 -> "Últimos 7 días"
        else -> "Últimas 24 horas"
    }

    private fun refrescar() {
        val ctx = applicationContext
        cont.removeAllViews()
        cont.addView(texto("Monitor de Batería", 24f, VERDE, true))
        cont.addView(texto("Cargando…", 14f, GRIS))
        trabajo.execute {
            val permiso = Consumo.tienePermiso(ctx)
            val bat = Bateria.leer(ctx)
            val ahora = System.currentTimeMillis()
            val desde = when (periodo) {
                0 -> Consumo.inicioDeHoy()
                2 -> ahora - 7L * 24 * 60 * 60 * 1000
                else -> ahora - 24L * 60 * 60 * 1000
            }
            val lista = if (permiso) Consumo.ranking(ctx, desde, ahora) else emptyList()

            // Respaldo automático: un informe por día en Descargas/MonitorBateria
            var guardado: String? = null
            val hoy = SimpleDateFormat("yyyy-MM-dd", Consumo.LOCALE).format(Date())
            if (permiso && lista.isNotEmpty() && ultimoRespaldo != hoy) {
                guardado = Respaldo.guardar(ctx, nombrePeriodo(periodo), bat, diseno, lista)
                if (guardado != null) ultimoRespaldo = hoy
            }
            main.post {
                if (isFinishing) return@post
                ultimaLista = lista
                ultimaBateria = bat
                dibujar(permiso, bat, lista)
                if (guardado != null) {
                    Toast.makeText(this, "Informe del día guardado en Descargas/MonitorBateria", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ------------------------------------------------------------------ pantalla

    private fun dibujar(permiso: Boolean, b: Bateria.Estado, apps: List<Consumo.AppGasto>) {
        cont.removeAllViews()
        cont.addView(texto("Monitor de Batería", 24f, VERDE, true))
        cont.addView(texto("Qué apps te gastan la batería", 14f, GRIS).apply { setPadding(0, 0, 0, dp(10)) })

        dibujarBateria(b)

        if (!permiso) {
            val c = tarjeta()
            c.addView(texto("Falta un permiso", 18f, TEXTO, true))
            c.addView(texto(
                "Para ver qué apps gastan más, Android pide activar \"Acceso a datos de uso\" para " +
                    "Monitor de Batería. Pulsa el botón, busca \"Monitor de Batería\", actívalo y vuelve aquí.",
                15f, TEXTO
            ))
            c.addView(boton("Dar permiso") { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
            return
        }

        // --- selector de periodo ---
        val fila = LinearLayout(this)
        fila.orientation = LinearLayout.HORIZONTAL
        for (p in 0..2) {
            val bt = boton(nombrePeriodo(p).replace("Últimas ", "").replace("Últimos ", ""), secundario = p != periodo) {
                periodo = p
                refrescar()
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(if (p == 0) 0 else dp(4), 0, if (p == 2) 0 else dp(4), dp(12))
            bt.layoutParams = lp
            fila.addView(bt)
        }
        cont.addView(fila)

        // --- ranking ---
        val c = tarjeta()
        c.addView(texto("Apps que más gastan · ${nombrePeriodo(periodo)}", 18f, TEXTO, true))
        c.addView(texto(
            "El % es una estimación hecha con el tiempo en pantalla, el trabajo en segundo plano y los datos. " +
                "Sirve para comparar apps entre sí.",
            13f, GRIS
        ))
        if (apps.isEmpty()) {
            c.addView(texto("Todavía no hay datos para este periodo.", 15f, TEXTO).apply { setPadding(0, dp(10), 0, 0) })
        }
        val maximo = apps.firstOrNull()?.pctEstimado ?: 1.0
        for ((i, a) in apps.take(25).withIndex()) {
            dibujarApp(c, i + 1, a, maximo)
        }

        // --- sospechosas ---
        val sosp = apps.filter { it.sospechosa && !it.esSistema }
        if (sosp.isNotEmpty()) {
            val s = tarjeta()
            s.addView(texto("⚠️ Trabajan mucho en segundo plano", 17f, NARANJA, true))
            s.addView(texto(
                "Estas apps gastan batería aunque no las estés usando: " +
                    sosp.take(6).joinToString(", ") { it.nombre } +
                    ". Tócalas en la lista y, en \"Ahorro de batería\", ponlas en \"Restringir\" " +
                    "(o desinstálalas si no las usas).",
                14f, TEXTO
            ))
        }

        // --- acciones ---
        val acc = tarjeta()
        acc.addView(texto("Más opciones", 17f, TEXTO, true))
        acc.addView(boton("Guardar informe ahora (CSV)") { guardarAhora() })
        acc.addView(boton("Abrir ajustes de batería del celular", secundario = true) { abrirAjustesBateria() })
        acc.addView(boton("Cambiar capacidad original (${diseno} mAh)", secundario = true) { dialogoDiseno() })
        acc.addView(texto(
            "Cada día se guarda solo un informe en Descargas/MonitorBateria, para que quede aunque borres la app.",
            12f, GRIS
        ).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun dibujarBateria(b: Bateria.Estado) {
        val c = tarjeta()
        val color = when {
            b.nivel <= 15 -> ROJO
            b.nivel <= 30 -> NARANJA
            else -> VERDE
        }
        c.addView(texto(if (b.cargando) "🔌 Cargando" else "🔋 Batería", 14f, GRIS))
        c.addView(texto("${b.nivel} %", 40f, color, true))
        c.addView(barra(b.nivel / 100.0, color))

        val tempColor = if (b.temperatura >= 40) ROJO else TEXTO
        c.addView(texto(String.format(Consumo.LOCALE, "🌡️ Temperatura: %.1f °C", b.temperatura) +
            if (b.temperatura >= 40) "  (muy caliente)" else "", 15f, tempColor))
        c.addView(texto("Estado: ${b.salud}", 15f, TEXTO))
        if (b.consumoMa > 0) {
            c.addView(texto(
                (if (b.cargando) "Entrando ahora: " else "Gasto en este momento: ") + "${b.consumoMa} mA",
                15f, TEXTO
            ))
        }
        if (b.ciclos >= 0) c.addView(texto("Ciclos de carga: ${b.ciclos}", 15f, TEXTO))

        // --- desgaste ---
        if (b.capacidadMah > 0 && diseno > 0) {
            val vida = b.capacidadMah * 100.0 / diseno
            val vColor = when {
                vida < 70 -> ROJO
                vida < 80 -> NARANJA
                else -> VERDE
            }
            c.addView(texto("Capacidad real estimada", 16f, TEXTO, true).apply { setPadding(0, dp(12), 0, 0) })
            c.addView(texto(
                String.format(Consumo.LOCALE, "%d mAh de %d mAh (%.0f %%)", b.capacidadMah, diseno, min(vida, 100.0)),
                18f, vColor, true
            ))
            c.addView(barra(vida / 100.0, vColor))
            val consejo = when {
                vida < 70 -> "La batería está muy gastada. Lo que más ayudaría es cambiarla."
                vida < 80 -> "La batería ya tiene bastante desgaste. Puede ser buena idea cambiarla pronto."
                else -> "La batería aún está bien. El gasto rápido viene más por las apps."
            }
            c.addView(texto(consejo, 14f, GRIS))
            c.addView(texto(
                "Es más exacto con la batería entre 30 % y 90 % y sin cargar. Revísalo varias veces.",
                12f, GRIS
            ))
        }
    }

    private fun dibujarApp(c: LinearLayout, n: Int, a: Consumo.AppGasto, maximo: Double) {
        val fila = LinearLayout(this)
        fila.orientation = LinearLayout.VERTICAL
        fila.setPadding(0, dp(12), 0, dp(4))
        val color = when {
            a.sospechosa && !a.esSistema -> NARANJA
            n <= 3 -> ROJO
            else -> AZUL
        }
        fila.addView(texto("$n. ${a.nombre}" + if (a.sospechosa && !a.esSistema) "  ⚠️" else "", 16f, TEXTO, true))
        fila.addView(texto("≈ ${Consumo.pct(a.pctEstimado)} de batería", 15f, color, true))
        fila.addView(barra(a.pctEstimado / maximo, color, 8))
        val partes = ArrayList<String>()
        partes.add("📱 En pantalla: ${Consumo.horas(a.pantallaMs)}")
        if (a.segundoPlanoMs >= 60000) partes.add("⏳ Segundo plano: ${Consumo.horas(a.segundoPlanoMs)}")
        val fondo = a.fondoMovilBytes + a.fondoWifiBytes
        if (fondo >= Consumo.MB) partes.add("📶 Datos en segundo plano: ${Consumo.mb(fondo)}")
        fila.addView(texto(partes.joinToString("\n"), 13f, GRIS))
        if (!a.paquete.startsWith("uid:")) {
            fila.setOnClickListener { abrirApp(a.paquete) }
            fila.addView(texto("Toca para restringir o desinstalar ›", 12f, AZUL))
        }
        c.addView(fila)
    }

    // ------------------------------------------------------------------ acciones

    private fun abrirApp(pkg: String) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir", Toast.LENGTH_SHORT).show()
        }
    }

    private fun abrirAjustesBateria() {
        try {
            startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) } catch (e2: Exception) { }
        }
    }

    private fun guardarAhora() {
        val b = ultimaBateria ?: return
        val lista = ultimaLista
        val ctx = applicationContext
        val per = nombrePeriodo(periodo)
        val dis = diseno
        trabajo.execute {
            val r = Respaldo.guardar(ctx, per, b, dis, lista)
            main.post {
                Toast.makeText(
                    this,
                    if (r != null) "Guardado en $r" else "No se pudo guardar el informe",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun dialogoDiseno() {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.setText(diseno.toString())
        et.setPadding(dp(20), dp(12), dp(20), dp(12))
        AlertDialog.Builder(this)
            .setTitle("Capacidad original (mAh)")
            .setMessage("Redmi Note 11 = 5000 mAh. Cámbialo solo si usas la app en otro celular.")
            .setView(et)
            .setPositiveButton("Guardar") { _, _ ->
                val v = et.text.toString().toIntOrNull()
                if (v != null && v in 1000..20000) {
                    diseno = v
                    refrescar()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
            fondo.setColor(Color.parseColor("#E3F0E4"))
            b.setTextColor(VERDE)
        } else {
            fondo.setColor(VERDE)
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
