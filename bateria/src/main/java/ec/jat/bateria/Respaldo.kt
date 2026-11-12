package ec.jat.bateria

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Respaldo: guarda cada informe como archivo CSV en
 * Descargas/MonitorBateria/ para que quede aunque se desinstale la app.
 */
object Respaldo {

    const val CARPETA = "Download/MonitorBateria"

    fun guardar(ctx: Context, periodo: String, b: Bateria.Estado, diseno: Int, apps: List<Consumo.AppGasto>): String? {
        val ahora = Date()
        val nombre = "informe_bateria_" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Consumo.LOCALE).format(ahora) + ".csv"
        val sb = StringBuilder()
        sb.append('﻿') // para que Excel lea bien las tildes
        sb.append("Informe Monitor de Batería;").append(SimpleDateFormat("yyyy-MM-dd HH:mm", Consumo.LOCALE).format(ahora)).append('\n')
        sb.append("Periodo;").append(periodo).append('\n')
        sb.append("Nivel;").append(b.nivel).append(" %\n")
        sb.append("Temperatura;").append(String.format(Consumo.LOCALE, "%.1f", b.temperatura)).append(" °C\n")
        sb.append("Salud;").append(b.salud).append('\n')
        if (b.capacidadMah > 0) {
            sb.append("Capacidad real estimada;").append(b.capacidadMah).append(" mAh de ").append(diseno).append(" mAh\n")
        }
        if (b.ciclos >= 0) sb.append("Ciclos de carga;").append(b.ciclos).append('\n')
        sb.append('\n')
        sb.append("App;Paquete;Bateria estimada %;En pantalla (min);Segundo plano (min);Datos fondo moviles (MB);Datos fondo WiFi (MB);Datos totales (MB);Trabaja mucho en segundo plano\n")
        for (a in apps) {
            sb.append(a.nombre.replace(";", ",")).append(';')
                .append(a.paquete).append(';')
                .append(String.format(Consumo.LOCALE, "%.2f", a.pctEstimado)).append(';')
                .append(a.pantallaMs / 60000).append(';')
                .append(a.segundoPlanoMs / 60000).append(';')
                .append(String.format(Consumo.LOCALE, "%.1f", a.fondoMovilBytes / Consumo.MB)).append(';')
                .append(String.format(Consumo.LOCALE, "%.1f", a.fondoWifiBytes / Consumo.MB)).append(';')
                .append(String.format(Consumo.LOCALE, "%.1f", a.totalDatosBytes / Consumo.MB)).append(';')
                .append(if (a.sospechosa) "SI" else "no").append('\n')
        }

        return try {
            val valores = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, CARPETA)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
            "$CARPETA/$nombre"
        } catch (e: Exception) {
            null
        }
    }
}
