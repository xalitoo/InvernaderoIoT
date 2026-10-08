package com.example.alarma

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
            estado.value = "Conectando a HiveMQ..."
            val broker = "tcp://broker.hivemq.com:1883"
            val clientId = "AppAndroid_" + UUID.randomUUID().toString().substring(0, 8)

            cliente = MqttClient(broker, clientId, MemoryPersistence())
            val opciones = MqttConnectOptions().apply { isCleanSession = true }

            cliente?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) { estado.value = "Desconectado" }
                override fun messageArrived(topic: String, message: MqttMessage) {
                    val texto = String(message.payload)
                    // Leemos los sensores según tu esquema
                    when (topic) {
                        "invernadero/temperatura" -> temperatura.value = "$texto °C"
                        "invernadero/humedad_suelo" -> humedadSuelo.value = "$texto %"
                    }
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            cliente?.connect(opciones)
            estado.value = "Conectado por WiFi"

            // Suscribir a los datos que enviará el ESP32 #1
            cliente?.subscribe("invernadero/temperatura")
            cliente?.subscribe("invernadero/humedad_suelo")

        } catch (e: Exception) {
            estado.value = "Error: ${e.message}"
        }
    }

    // Enviar orden al ESP32 #2 para la bomba de agua
    fun controlarBomba(estado: String) = alcance.launch {
        try {
            cliente?.publish("invernadero/bomba", MqttMessage(estado.toByteArray()))
        } catch (e: Exception) { }
    }

    fun desconectar() {
        try { cliente?.disconnect() } catch (e: Exception) {}
        alcance.cancel()
    }
}