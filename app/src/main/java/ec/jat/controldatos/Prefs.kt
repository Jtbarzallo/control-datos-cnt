package ec.jat.controldatos

import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("datos", Context.MODE_PRIVATE)

    companion object {
        val GRATIS_POR_DEFECTO = setOf(
            "com.whatsapp", "com.whatsapp.w4b",
            "com.instagram.android", "com.instagram.lite",
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
            "com.facebook.katana", "com.facebook.lite", "com.facebook.orca"
        )
    }

    /** Saldo de datos (MB) en el momento de la última actualización. */
    var saldoMb: Double
        get() = sp.getString("saldoMb", "0")!!.toDoubleOrNull() ?: 0.0
        set(v) = sp.edit().putString("saldoMb", v.toString()).apply()

    /** Tamaño total del paquete (MB), para calcular el porcentaje. */
    var paqueteMb: Double
        get() = sp.getString("paqueteMb", "0")!!.toDoubleOrNull() ?: 0.0
        set(v) = sp.edit().putString("paqueteMb", v.toString()).apply()

    /** Momento (ms) en que se registró el saldo. */
    var calibTime: Long
        get() = sp.getLong("calibTime", 0L)
        set(v) = sp.edit().putLong("calibTime", v).apply()

    /** Fin del último día de vigencia (ms). */
    var venceTime: Long
        get() = sp.getLong("venceTime", 0L)
        set(v) = sp.edit().putLong("venceTime", v).apply()

    var gratis: Set<String>
        get() = HashSet(sp.getStringSet("gratis", GRATIS_POR_DEFECTO) ?: GRATIS_POR_DEFECTO)
        set(v) = sp.edit().putStringSet("gratis", HashSet(v)).apply()

    var avisoWifi: Boolean
        get() = sp.getBoolean("avisoWifi", true)
        set(v) = sp.edit().putBoolean("avisoWifi", v).apply()

    var ussdCode: String
        get() = sp.getString("ussdCode", "") ?: ""
        set(v) = sp.edit().putString("ussdCode", v).apply()

    var ultimoAvisoDiario: String
        get() = sp.getString("ultimoAvisoDiario", "") ?: ""
        set(v) = sp.edit().putString("ultimoAvisoDiario", v).apply()

    var ultimoAvisoVence: String
        get() = sp.getString("ultimoAvisoVence", "") ?: ""
        set(v) = sp.edit().putString("ultimoAvisoVence", v).apply()

    /** Último porcentaje de saldo por el que ya se avisó (100 = ninguno). */
    var ultimoAvisoPct: Int
        get() = sp.getInt("ultimoAvisoPct", 100)
        set(v) = sp.edit().putInt("ultimoAvisoPct", v).apply()

    val configurado: Boolean get() = calibTime > 0L && venceTime > 0L

    fun registrarSaldo(saldo: Double, paquete: Double, vence: Long) {
        saldoMb = saldo
        paqueteMb = if (paquete > 0) paquete else saldo
        calibTime = System.currentTimeMillis()
        venceTime = vence
        ultimoAvisoPct = 100
        ultimoAvisoDiario = ""
    }
}
