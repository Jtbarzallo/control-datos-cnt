package ec.jat.musica

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Guarda las canciones en la memoria pública de la radio (carpeta Música).
 * Las canciones quedan como archivos normales: si se desinstala la app,
 * la música sigue ahí (regla de respaldo de Jorge).
 */
object Guardado {

    /** Carpeta dentro de "Music" donde se guardan las canciones recibidas. */
    const val SUBCARPETA = "Del celular"

    /** Dejamos siempre al menos 200 MB libres en la radio. */
    private const val RESERVA = 200L * 1024 * 1024

    fun espacioLibre(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (e: Exception) { -1L }

    fun hayEspacio(bytes: Long): Boolean {
        val libre = espacioLibre()
        return libre < 0 || bytes + RESERVA < libre
    }

    /** Limpia el nombre del archivo para que sea válido. */
    fun limpiar(nombre: String): String {
        var n = nombre.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim()
        if (n.isEmpty()) n = "cancion_" + System.currentTimeMillis() + ".mp3"
        if (n.length > 150) {
            val ext = n.substringAfterLast('.', "")
            n = n.take(140) + if (ext.isNotEmpty() && ext.length <= 5) ".$ext" else ""
        }
        return n
    }

    private fun tipo(nombre: String): String {
        val ext = nombre.substringAfterLast('.', "").lowercase()
        val m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return if (m != null && m.startsWith("audio/")) m else when (ext) {
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/x-wav"
            "wma" -> "audio/x-ms-wma"
            else -> "audio/mpeg"
        }
    }

    private fun carpetaVieja(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), SUBCARPETA)

    /** Nombres (en minúsculas) de todas las canciones que ya tiene la radio. */
    fun cancionesExistentes(ctx: Context): Set<String> {
        val set = HashSet<String>()
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                while (c.moveToNext()) c.getString(0)?.let { set.add(it.lowercase()) }
            }
        } catch (e: Exception) { }
        try {
            carpetaVieja().listFiles()?.forEach { set.add(it.name.lowercase()) }
        } catch (e: Exception) { }
        return set
    }

    /**
     * Copia exactamente [largo] bytes de [entrada] a una canción nueva.
     * Devuelve la ruta donde quedó. Si falla, borra el archivo a medias y lanza IOException.
     */
    fun guardar(ctx: Context, nombreOriginal: String, entrada: InputStream, largo: Long, progreso: (Long) -> Unit): String {
        val nombre = limpiar(nombreOriginal)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val valores = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
                put(MediaStore.MediaColumns.MIME_TYPE, tipo(nombre))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + SUBCARPETA)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val coleccion = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri: Uri = ctx.contentResolver.insert(coleccion, valores)
                ?: throw IOException("La radio no dejó crear el archivo")
            try {
                val salida = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("No se pudo abrir el archivo")
                salida.use { copiar(entrada, it, largo, progreso) }
                val fin = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                ctx.contentResolver.update(uri, fin, null, null)
            } catch (e: Exception) {
                try { ctx.contentResolver.delete(uri, null, null) } catch (x: Exception) { }
                throw if (e is IOException) e else IOException(e.message ?: "Error al guardar")
            }
            "Música/$SUBCARPETA/$nombre"
        } else {
            val carpeta = carpetaVieja()
            if (!carpeta.exists() && !carpeta.mkdirs()) throw IOException("Sin permiso para escribir en la memoria")
            var archivo = File(carpeta, nombre)
            var i = 1
            while (archivo.exists()) {
                val base = nombre.substringBeforeLast('.')
                val ext = nombre.substringAfterLast('.', "")
                archivo = File(carpeta, "$base ($i)" + if (ext.isNotEmpty()) ".$ext" else "")
                i++
            }
            try {
                FileOutputStream(archivo).use { copiar(entrada, it, largo, progreso) }
            } catch (e: IOException) {
                archivo.delete()
                throw e
            }
            MediaScannerConnection.scanFile(ctx, arrayOf(archivo.absolutePath), null, null)
            "Música/$SUBCARPETA/${archivo.name}"
        }
    }

    private fun copiar(entrada: InputStream, salida: OutputStream, largo: Long, progreso: (Long) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var falta = largo
        var hecho = 0L
        var ultimoAviso = 0L
        while (falta > 0) {
            val n = entrada.read(buf, 0, minOf(buf.size.toLong(), falta).toInt())
            if (n < 0) throw IOException("Se cortó la conexión con el celular")
            salida.write(buf, 0, n)
            falta -= n
            hecho += n
            if (hecho - ultimoAviso > 512 * 1024) { progreso(hecho); ultimoAviso = hecho }
        }
        salida.flush()
        progreso(hecho)
    }
}
