package ec.jat.bateria

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.abs

/** Estado de la batería del celular. */
object Bateria {

    data class Estado(
        val nivel: Int,
        val cargando: Boolean,
        val temperatura: Double,
        val voltaje: Double,
        val salud: String,
        val capacidadMah: Int,      // capacidad real estimada (0 = no disponible)
        val consumoMa: Int,         // corriente en este momento (0 = no disponible)
        val ciclos: Int             // -1 = no disponible
    )

    fun leer(ctx: Context): Estado {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val nivelRaw = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val escala = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val nivel = if (nivelRaw >= 0 && escala > 0) nivelRaw * 100 / escala else -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val cargando = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val volt = (i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000.0
        val salud = when (i?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Buena"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Sobrecalentada"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Agotada"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Sobrevoltaje"
            BatteryManager.BATTERY_HEALTH_COLD -> "Fría"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Falla"
            else -> "Desconocida"
        }
        val ciclos = i?.getIntExtra("android.os.extra.CYCLE_COUNT", -1) ?: -1

        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        // Capacidad real estimada = carga actual / porcentaje
        var capacidad = 0
        try {
            val contador = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) // µAh
            if (contador > 0 && nivel in 15..100) {
                var mah = contador / 1000.0
                if (mah < 50) mah = contador.toDouble() // algunos celulares lo dan en mAh
                capacidad = (mah * 100.0 / nivel).toInt()
                if (capacidad < 500 || capacidad > 20000) capacidad = 0
            }
        } catch (e: Exception) { }

        var consumo = 0
        try {
            val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (cur != Int.MIN_VALUE && cur != 0) {
                consumo = if (abs(cur) > 20000) abs(cur) / 1000 else abs(cur)
            }
        } catch (e: Exception) { }

        return Estado(nivel, cargando, temp, volt, salud, capacidad, consumo, ciclos)
    }
}
