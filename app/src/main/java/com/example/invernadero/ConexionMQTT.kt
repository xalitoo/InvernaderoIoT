package com.example.invernadero

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

class ConexionMQTT {
    val estado = MutableStateFlow("Desconectado")
    val temperatura = MutableStateFlow("-- °C")
    val humedadSuelo = MutableStateFlow("-- %")

    private var cliente: MqttClient? = null
    private val alcance = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun conectar() = alcance.launch {
        try {
            estado.value = "Conectando a HiveMQ Seguro..."

            // CORRECCIÓN 1: Conexión cifrada TLS (ssl y puerto 8883)
            val broker = "ssl://broker.hivemq.com:8883"
            val clientId = "AppAndroid_" + UUID.randomUUID().toString().substring(0, 8)

            cliente = MqttClient(broker, clientId, MemoryPersistence())

            // CORRECCIÓN 2: Opciones de conexión con usuario y clave
            val opciones = MqttConnectOptions().apply {
                isCleanSession = true
                userName = "TU_USUARIO_AQUI" // Reemplaza esto con tu usuario del broker
                password = "TU_CONTRASEÑA_AQUI".toCharArray() // Reemplaza esto con tu clave
            }

            cliente?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) { estado.value = "Desconectado" }
                override fun messageArrived(topic: String, message: MqttMessage) {
                    val texto = String(message.payload)
                    when (topic) {
                        "invernadero/temperatura" -> temperatura.value = "$texto °C"
                        "invernadero/humedad_suelo" -> humedadSuelo.value = "$texto %"
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
        // CORRECCIÓN 3: Se eliminó alcance.cancel() para arreglar el bug de reconexión
    }
}