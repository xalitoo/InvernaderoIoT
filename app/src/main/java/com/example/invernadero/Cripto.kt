package com.example.invernadero

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Cripto {
    private const val ITERACIONES = 120_000
    // Clave compartida entre la App Android y los dos nodos ESP32
    private const val CLAVE_HMAC = "INACAP_TI3042_Invernadero_2026#K9"

    fun nuevaSal(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun hash(clave: CharArray, sal: ByteArray): ByteArray {
        val spec = PBEKeySpec(clave, sal, ITERACIONES, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun verificar(clave: CharArray, sal: ByteArray, esperado: ByteArray): Boolean =
        MessageDigest.isEqual(hash(clave, sal), esperado)

    fun calcularHmacSha256(mensaje: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(CLAVE_HMAC.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val rawHmac = mac.doFinal(mensaje.toByteArray(StandardCharsets.UTF_8))
        return rawHmac.joinToString("") { "%02x".format(it) }
    }

    fun verificarHmacSha256(mensaje: String, firmaRecibida: String): Boolean {
        val firmaEsperada = calcularHmacSha256(mensaje)
        return MessageDigest.isEqual(
            firmaEsperada.lowercase().toByteArray(StandardCharsets.UTF_8),
            firmaRecibida.lowercase().toByteArray(StandardCharsets.UTF_8)
        )
    }
}