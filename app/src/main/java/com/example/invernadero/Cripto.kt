package com.example.invernadero

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Cripto {
    private const val ITERACIONES = 120_000

    fun nuevaSal(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun hash(clave: CharArray, sal: ByteArray): ByteArray {
        val spec = PBEKeySpec(clave, sal, ITERACIONES, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun verificar(clave: CharArray, sal: ByteArray, esperado: ByteArray): Boolean =
        MessageDigest.isEqual(hash(clave, sal), esperado)
}