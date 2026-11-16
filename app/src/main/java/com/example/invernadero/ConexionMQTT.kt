package com.example.invernadero

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import javax.net.ssl.SSLSocketFactory

class ConexionMQTT(private val context: Context) {
    val estado = MutableStateFlow("Desconectado")
    val temperatura = MutableStateFlow("-- °C")
    val humedadSuelo = MutableStateFlow("-- %")
    val alertaContingencia = MutableStateFlow<String?>(null)

    private var cliente: MqttClient? = null
    private val alcance = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val baseDatos = BaseDatos(context)

    companion object {
        const val TOPIC_TEMP = "invernadero/temperatura"
        const val TOPIC_HUM_SUELO = "invernadero/humedad_suelo"
        const val TOPIC_BOMBA = "invernadero/bomba"
        const val TOPIC_VENTILACION = "invernadero/ventilacion"
        const val TOPIC_SYNC_LOGS = "invernadero/sincronizacion"
    }

    // 1. Verificación de estado de red (Permiso ACCESS_NETWORK_STATE - Corrección U2)
    fun hayConexionInternet(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // 2. Firma SHA-256 para integridad de mensajes (ISO 27000 - Paso 6)
    private fun calcularSha256(datos: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(datos.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun construirPayloadSeguro(accion: String): String {
        val timestamp = System.currentTimeMillis()
        val hash = calcularSha256("accion|$accion|$timestamp")
        return JSONObject().apply {
            put("origen", "AndroidApp")
            put("accion", accion)
            put("timestamp", timestamp)
            put("hash", hash)
        }.toString()
    }

    // 3. Conexión segura TLS 8883 con reconexión automática (Pasos 3 y 6)
    fun conectar() = alcance.launch {
        if (!hayConexionInternet()) {
            estado.value = "Sin red (Modo Local)"
            alertaContingencia.value = "Sin conexión a Internet. Los comandos se guardarán localmente."
            return@launch
        }

        if (cliente?.isConnected == true) return@launch

        try {
            estado.value = "Conectando a HiveMQ Seguro..."
            alertaContingencia.value = null

            val broker = "ssl://broker.hivemq.com:8883"
            val clientId = "AppAndroid_" + UUID.randomUUID().toString().substring(0, 8)

            cliente = MqttClient(broker, clientId, MemoryPersistence())

            val opciones = MqttConnectOptions().apply {
                isCleanSession = true
                isAutomaticReconnect = true // Reconexión automática ante cortes (Paso 3)
                connectionTimeout = 10
                keepAliveInterval = 30
                socketFactory = SSLSocketFactory.getDefault()
            }

            cliente?.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    estado.value = if (reconnect) "Reconectado por WiFi (TLS)" else "Conectado por WiFi (TLS)"
                    alertaContingencia.value = null
                    suscribirTopicos()
                    sincronizarDatosPendientes() // Despacha cola local al reconectar (Paso 5)
                }

                override fun connectionLost(cause: Throwable?) {
                    estado.value = "Desconectado - Reintentando..."
                    alertaContingencia.value =
                        "Contingencia activa: acciones y lecturas se respaldan en SQLite."
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    val texto = String(message.payload).trim()
                    procesarMensajeEntrante(topic, texto)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            cliente?.connect(opciones)
            estado.value = "Conectado por WiFi (TLS)"
            suscribirTopicos()
            sincronizarDatosPendientes()

        } catch (e: Exception) {
            estado.value = "Error: ${e.message}"
            alertaContingencia.value = "Falla de enlace MQTT. Operando con respaldo local SQLite."
        }
    }

    private fun suscribirTopicos() {
        try {
            cliente?.subscribe(TOPIC_TEMP, 1)
            cliente?.subscribe(TOPIC_HUM_SUELO, 1)
        } catch (e: Exception) {
            alertaContingencia.value = "Error al suscribir tópicos: ${e.message}"
        }
    }

    // 4. Validación de integridad SHA-256 y persistencia local (Pasos 3, 5 y 6)
    private fun procesarMensajeEntrante(topic: String, texto: String) {
        val valorNumerico: Double? = if (texto.startsWith("{")) {
            try {
                val json = JSONObject(texto)
                val valorStr = json.optString("valor", "")
                val hashRecibido = json.optString("hash", "")
                val ts = json.optLong("timestamp", 0L)

                if (hashRecibido.isNotEmpty() && ts > 0L) {
                    val hashEsperado = calcularSha256("valor|$valorStr|$ts")
                    if (!hashRecibido.equals(hashEsperado, ignoreCase = true)) {
                        alertaContingencia.value = "Alerta ISO 27000: trama alterada descartada en $topic"
                        return
                    }
                }
                valorStr.toDoubleOrNull()
            } catch (_: Exception) {
                null
            }
        } else {
            texto.toDoubleOrNull()
        }

        val valorFinal = valorNumerico ?: return
        val flagSync = if (cliente?.isConnected == true) 1 else 0

        when (topic) {
            TOPIC_TEMP -> {
                temperatura.value = "$valorFinal °C"
                baseDatos.guardarLectura("temperatura", valorFinal, flagSync)
            }
            TOPIC_HUM_SUELO -> {
                humedadSuelo.value = "$valorFinal %"
                baseDatos.guardarLectura("humedad", valorFinal, flagSync)
            }
        }
    }

    // 5. Control de actuadores con Plan de Contingencia (Paso 4)
    fun controlarBomba(estadoCmd: String) = alcance.launch {
        val payloadSeguro = construirPayloadSeguro(estadoCmd)
        publicarConContingencia(TOPIC_BOMBA, payloadSeguro, "Bomba ($estadoCmd)")
    }

    fun controlarVentilacion(estadoCmd: String) = alcance.launch {
        val payloadSeguro = construirPayloadSeguro(estadoCmd)
        publicarConContingencia(TOPIC_VENTILACION, payloadSeguro, "Ventilación ($estadoCmd)")
    }

    private fun publicarConContingencia(topico: String, payload: String, descripcion: String) {
        if (!hayConexionInternet() || cliente?.isConnected != true) {
            baseDatos.guardarComandoPendiente(topico, payload)
            alertaContingencia.value =
                "Sin conexión: '$descripcion' encolado en SQLite. Se enviará al reconectar."
            return
        }

        try {
            val mensaje = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply { qos = 1 }
            cliente?.publish(topico, mensaje)
            alertaContingencia.value = null
        } catch (e: Exception) {
            baseDatos.guardarComandoPendiente(topico, payload)
            alertaContingencia.value =
                "Error de envío: '$descripcion' respaldado en cola de contingencia."
        }
    }

    // 6. Sincronización automática de datos locales y comandos al reconectar (Paso 5)
    fun sincronizarDatosPendientes() = alcance.launch {
        if (cliente?.isConnected != true) return@launch

        // Despachar comandos de actuadores pendientes
        val comandos = baseDatos.obtenerComandosPendientes()
        for (cmd in comandos) {
            try {
                val msg = MqttMessage(cmd.payload.toByteArray(Charsets.UTF_8)).apply { qos = 1 }
                cliente?.publish(cmd.topico, msg)
                baseDatos.eliminarComandoPendiente(cmd.id)
            } catch (_: Exception) {
                break
            }
        }

        // Sincronizar lecturas guardadas localmente sin red
        val lecturas = baseDatos.obtenerLecturasNoSincronizadas()
        for (lectura in lecturas) {
            try {
                val jsonSync = JSONObject().apply {
                    put("id_local", lectura.id)
                    put("momento", lectura.momento)
                    put("clave", lectura.clave)
                    put("valor", lectura.valor)
                }.toString()
                val msg = MqttMessage(jsonSync.toByteArray(Charsets.UTF_8)).apply { qos = 1 }
                cliente?.publish(TOPIC_SYNC_LOGS, msg)
                baseDatos.marcarLecturaSincronizada(lectura.id)
            } catch (_: Exception) {
                break
            }
        }
    }

    fun desconectar() {
        try {
            if (cliente?.isConnected == true) cliente?.disconnect()
        } catch (_: Exception) {
        }
    }
}