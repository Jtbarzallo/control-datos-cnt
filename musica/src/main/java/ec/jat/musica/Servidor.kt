package ec.jat.musica

import android.content.Context
import android.os.Build
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Servidor web pequeño que corre en la radio.
 * El celular abre la página en Chrome, elige canciones y las envía aquí.
 */
class Servidor(private val ctx: Context, private val avisos: Avisos) {

    interface Avisos {
        fun recibiendo(nombre: String, hecho: Long, total: Long)
        fun recibida(nombre: String, ruta: String, bytes: Long)
        fun fallo(nombre: String, motivo: String)
    }

    var puerto = 0
        private set
    private var socket: ServerSocket? = null
    private val hilos = Executors.newCachedThreadPool()
    /** Solo una canción a la vez para no saturar la memoria de la radio. */
    private val candado = Any()

    fun iniciar(): Int {
        var ultimoError: IOException? = null
        for (p in 8080..8090) {
            try {
                val s = ServerSocket(p)
                s.reuseAddress = true
                socket = s
                puerto = p
                Thread({ escuchar(s) }, "servidor-musica").start()
                return p
            } catch (e: IOException) { ultimoError = e }
        }
        throw ultimoError ?: IOException("No hay puerto libre")
    }

    fun detener() {
        try { socket?.close() } catch (e: Exception) { }
        socket = null
        hilos.shutdownNow()
    }

    private fun escuchar(s: ServerSocket) {
        while (!s.isClosed) {
            try {
                val cliente = s.accept()
                hilos.execute { atender(cliente) }
            } catch (e: Exception) {
                if (s.isClosed) return
            }
        }
    }

    private fun atender(cliente: Socket) {
        try {
            cliente.soTimeout = 60_000
            val entrada = BufferedInputStream(cliente.getInputStream(), 64 * 1024)
            val salida = cliente.getOutputStream()
            val primera = leerLinea(entrada) ?: return
            val partes = primera.split(" ")
            if (partes.size < 2) return
            val metodo = partes[0].uppercase()
            val ruta = partes[1].substringBefore('?')
            val cabeceras = HashMap<String, String>()
            while (true) {
                val l = leerLinea(entrada) ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) cabeceras[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }

            when {
                metodo == "GET" && (ruta == "/" || ruta == "/index.html") -> {
                    val html = ctx.assets.open("subir.html").use { it.readBytes() }
                    responder(salida, 200, "text/html; charset=utf-8", html)
                }
                metodo == "GET" && ruta == "/info" -> {
                    val json = "{\"radio\":" + jsonTexto(Build.MANUFACTURER + " " + Build.MODEL) +
                        ",\"libre\":" + Guardado.espacioLibre() +
                        ",\"carpeta\":" + jsonTexto("Música/" + Guardado.SUBCARPETA) + "}"
                    responder(salida, 200, "application/json; charset=utf-8", json.toByteArray())
                }
                metodo == "GET" && ruta == "/canciones" -> {
                    val lista = Guardado.cancionesExistentes(ctx)
                    val json = lista.joinToString(",", "[", "]") { jsonTexto(it) }
                    responder(salida, 200, "application/json; charset=utf-8", json.toByteArray())
                }
                metodo == "POST" && ruta == "/subir" -> recibir(entrada, salida, cabeceras)
                else -> responder(salida, 404, "text/plain; charset=utf-8", "No encontrado".toByteArray())
            }
        } catch (e: Exception) {
            // conexión cortada: nada que hacer
        } finally {
            try { cliente.close() } catch (e: Exception) { }
        }
    }

    private fun recibir(entrada: InputStream, salida: OutputStream, cab: Map<String, String>) {
        val nombre = try {
            URLDecoder.decode(cab["x-nombre"] ?: "", "UTF-8")
        } catch (e: Exception) { "" }.ifBlank { "cancion.mp3" }
        val largo = cab["content-length"]?.toLongOrNull() ?: -1L
        if (largo <= 0) {
            responderJson(salida, false, "El celular no mandó el tamaño del archivo")
            return
        }
        if (!Guardado.hayEspacio(largo)) {
            avisos.fallo(nombre, "No hay espacio en la radio")
            responderJson(salida, false, "No hay espacio suficiente en la radio")
            return
        }
        synchronized(candado) {
            try {
                val ruta = Guardado.guardar(ctx, nombre, entrada, largo) { hecho ->
                    avisos.recibiendo(nombre, hecho, largo)
                }
                avisos.recibida(nombre, ruta, largo)
                responderJson(salida, true, ruta)
            } catch (e: Exception) {
                val motivo = e.message ?: "Error desconocido"
                avisos.fallo(nombre, motivo)
                try { responderJson(salida, false, motivo) } catch (x: Exception) { }
            }
        }
    }

    private fun responderJson(salida: OutputStream, ok: Boolean, mensaje: String) {
        val json = "{\"ok\":$ok,\"mensaje\":" + jsonTexto(mensaje) + "}"
        responder(salida, 200, "application/json; charset=utf-8", json.toByteArray())
    }

    private fun responder(salida: OutputStream, codigo: Int, tipo: String, cuerpo: ByteArray) {
        val estado = when (codigo) { 200 -> "OK"; 404 -> "Not Found"; else -> "Error" }
        val cabecera = "HTTP/1.1 $codigo $estado\r\n" +
            "Content-Type: $tipo\r\n" +
            "Content-Length: ${cuerpo.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        salida.write(cabecera.toByteArray(Charsets.ISO_8859_1))
        salida.write(cuerpo)
        salida.flush()
    }

    private fun leerLinea(entrada: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = entrada.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
            if (b.size() > 16 * 1024) throw IOException("Cabecera muy larga")
        }
        return b.toString("UTF-8")
    }

    private fun jsonTexto(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
