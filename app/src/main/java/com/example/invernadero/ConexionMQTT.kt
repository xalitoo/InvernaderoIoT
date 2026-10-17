package com.example.invernadero

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

class ConexionMQTT(private val context: Context) {
    val estado = MutableStateFlow("Desconectado")
    val temperatura = MutableStateFlow("-- °C")
    val humedadSuelo = MutableStateFlow("-- %")

    private var cliente: MqttClient? = null
    private val alcance = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val baseDatos = BaseDatos(context)

    fun conectar() = alcance.launch {
        try {
            estado.value = "Conectando a HiveMQ Seguro..."

            val broker = "ssl://broker.hivemq.com:8883"
            val clientId = "AppAndroid_" + UUID.randomUUID().toString().substring(0, 8)

            cliente = MqttClient(broker, clientId, MemoryPersistence())

            val opciones = MqttConnectOptions().apply {
                isCleanSession = true
                userName = "TU_USUARIO_AQUI"
                password = "TU_CONTRASEÑA_AQUI".toCharArray()
            }

            cliente?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) { estado.value = "Desconectado" }
                override fun messageArrived(topic: String, message: MqttMessage) {
                    val texto = String(message.payload)
                    val valorNumerico = texto.toDoubleOrNull() ?: 0.0

                    when (topic) {
                        "invernadero/temperatura" -> {
                            temperatura.value = "$texto °C"
                            // Guardar en la base de datos indicando la clave "temperatura"
                            baseDatos.guardarLectura("temperatura", valorNumerico)
                        }
                        "invernadero/humedad_suelo" -> {
                            humedadSuelo.value = "$texto %"
                            // Guardar en la base de datos indicando la clave "humedad"
                            baseDatos.guardarLectura("humedad", valorNumerico)
                        }
                    }
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            cliente?.connect(opciones)
            estado.value = "Conectado por WiFi (TLS)"

            cliente?.subscribe("invernadero/temperatura")
            cliente?.subscribe("invernadero/humedad_suelo")

        } catch (e: Exception) {
            estado.value = "Error: ${e.message}"
        }
    }

    fun controlarBomba(estadoCmd: String) = alcance.launch {
        try {
            cliente?.publish("invernadero/bomba", MqttMessage(estadoCmd.toByteArray()))
        } catch (e: Exception) { }
    }

    fun desconectar() {
        try { cliente?.disconnect() } catch (e: Exception) {}
    }
}