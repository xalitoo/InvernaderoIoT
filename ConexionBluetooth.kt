package com.example.alarma

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.crypto.SecretKey

// 1. Formato de los mensajes que viajan por Bluetooth
data class Mensaje(val tipo: String, val clave: String, val valor: String) {
    fun alinea() = "$tipo;$clave;$valor"
    companion object {
        private val TIPOS = setOf("LECTURA", "CMD", "ACK")
        fun desdeLinea(linea: String): Mensaje? {
            val p = linea.split(";")
            if (p.size != 3 || p[0] !in TIPOS) return null
            return Mensaje(p[0], p[1], p[2])
        }
    }
}

// 2. Lógica de la conexión cifrada
@SuppressLint("MissingPermission")
class ConexionBluetooth(private val adaptador: BluetoothAdapter, private val clave: SecretKey) {

    val estado = MutableStateFlow("Desconectado")
    val entrantes = MutableSharedFlow<Mensaje>(extraBufferCapacity = 64)
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var servidor: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    @Volatile private var escritor: BufferedWriter? = null

    // Rol Nodo (Botón de pánico): Espera a que el panel se conecte
    fun esperar() = alcance.launch {
        try {
            estado.value = "Esperando conexión..."
            val srv = adaptador.listenUsingRfcommWithServiceRecord("AlarmaIoT", UUID_APP).also { servidor = it }
            val s = srv.accept().also { socket = it }
            srv.close()
            atender(s.inputStream, s.outputStream)
        } catch (e: Exception) {
            estado.value = "Error: ${e.message}"
        }
    }

    // Rol Panel (Monitor): Se conecta al nodo
    fun conectar(equipo: BluetoothDevice) = alcance.launch {
        try {
            estado.value = "Conectando..."
            val s = equipo.createRfcommSocketToServiceRecord(UUID_APP).also { socket = it }
            s.connect()
            atender(s.inputStream, s.outputStream)
        } catch (e: Exception) {
            estado.value = "Error: ${e.message}"
        }
    }

    private fun atender(entrada: InputStream, salida: OutputStream) {
        escritor = salida.bufferedWriter()
        estado.value = "Conectado"
        try {
            entrada.bufferedReader().forEachLine { linea ->
                val texto = Cripto.descifrar(linea, clave) ?: return@forEachLine
                Mensaje.desdeLinea(texto)?.let { entrantes.tryEmit(it) }
            }
        } catch (e: Exception) {
            estado.value = "Desconectado"
        }
        escritor = null
    }

    suspend fun enviar(m: Mensaje) {
        val w = escritor ?: return
        withContext(Dispatchers.IO) {
            try {
                synchronized(w) {
                    w.write(Cripto.cifrar(m.alinea(), clave))
                    w.newLine()
                    w.flush()
                }
            } catch (e: Exception) {
                estado.value = "Desconectado"
            }
        }
    }

    fun cerrar() {
        runCatching { socket?.close() }
        runCatching { servidor?.close() }
        alcance.cancel()
    }

    companion object {
        // Identificador único para que los dos teléfonos se encuentren
        val UUID_APP: UUID = UUID.fromString("7f3c2a10-5b8e-4d21-9c6f-2e8a4b1d9f03")
    }
}

