package ec.jat.controldatos

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager

object Ussd {

    data class Saldo(val restanteMb: Double, val paqueteMb: Double)

    /** Lee textos como "Le quedan 20473.99 MB de su paquete de datos de 35840.0 MB". */
    fun interpretar(texto: String): Saldo? {
        val numero = """([\d][\d.,]*)\s*(MB|GB)"""
        val r = Regex("""quedan?\s+$numero.*?de\s+$numero""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val m = r.find(texto)
        if (m != null) {
            val a = aMb(m.groupValues[1], m.groupValues[2]) ?: return null
            val b = aMb(m.groupValues[3], m.groupValues[4]) ?: a
            return Saldo(a, b)
        }
        val solo = Regex("""quedan?\s+$numero""", RegexOption.IGNORE_CASE).find(texto) ?: return null
        val a = aMb(solo.groupValues[1], solo.groupValues[2]) ?: return null
        return Saldo(a, 0.0)
    }

    private fun aMb(num: String, unidad: String): Double? {
        var s = num.trim().trimEnd('.', ',')
        // "20,473.99" -> 20473.99 ; "20473,99" -> 20473.99
        s = if (s.contains('.') && s.contains(',')) s.replace(",", "") else s.replace(',', '.')
        val v = s.toDoubleOrNull() ?: return null
        return if (unidad.equals("GB", true)) v * 1024 else v
    }

    @SuppressLint("MissingPermission")
    fun consultar(ctx: Context, codigo: String, ok: (String) -> Unit, error: (String) -> Unit) {
        try {
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            tm.sendUssdRequest(codigo, object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(t: TelephonyManager, request: String, response: CharSequence) {
                    ok(response.toString())
                }

                override fun onReceiveUssdResponseFailed(t: TelephonyManager, request: String, failureCode: Int) {
                    error("La operadora no respondió (código $failureCode). Intenta de nuevo o marca el código a mano.")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            error("Falta el permiso de teléfono.")
        } catch (e: Exception) {
            error("No se pudo consultar: ${e.message}")
        }
    }
}
