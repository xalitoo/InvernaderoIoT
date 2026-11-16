package com.example.invernadero

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Lectura(val momento: Long, val clave: String, val valor: Double)

data class LecturaPendiente(
    val id: Int,
    val momento: Long,
    val clave: String,
    val valor: Double
)

data class ComandoPendiente(
    val id: Int,
    val momento: Long,
    val topico: String,
    val payload: String
)

class BaseDatos(ctx: Context) : SQLiteOpenHelper(ctx, "invernadero.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE usuarios (usuario TEXT PRIMARY KEY, sal BLOB NOT NULL, hash BLOB NOT NULL, rol TEXT NOT NULL)")
        db.execSQL(
            """
            CREATE TABLE lecturas (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                momento INTEGER NOT NULL,
                clave TEXT NOT NULL,
                valor REAL NOT NULL,
                sincronizado INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE comandos_pendientes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                momento INTEGER NOT NULL,
                topico TEXT NOT NULL,
                payload TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, anterior: Int, nueva: Int) {
        if (anterior < 2) {
            db.execSQL("ALTER TABLE lecturas ADD COLUMN sincronizado INTEGER DEFAULT 0")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS comandos_pendientes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    momento INTEGER NOT NULL,
                    topico TEXT NOT NULL,
                    payload TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    fun registrar(usuario: String, clave: CharArray): String? {
        val hayUsuarios = readableDatabase.rawQuery("SELECT 1 FROM usuarios LIMIT 1", null).use {
            it.moveToFirst()
        }
        val rol = if (hayUsuarios) "observador" else "operador"
        val sal = Cripto.nuevaSal()
        val fila = ContentValues().apply {
            put("usuario", usuario)
            put("sal", sal)
            put("hash", Cripto.hash(clave, sal))
            put("rol", rol)
        }
        return if (writableDatabase.insert("usuarios", null, fila) == -1L) null else rol
    }

    fun autenticar(usuario: String, clave: CharArray): String? =
        readableDatabase.rawQuery("SELECT sal, hash, rol FROM usuarios WHERE usuario = ?", arrayOf(usuario)).use { c ->
            if (c.moveToFirst() && Cripto.verificar(clave, c.getBlob(0), c.getBlob(1)))
                c.getString(2) else null
        }

    // Mantiene compatibilidad con tu código actual y agrega estado de sincronización (Paso 5)
    fun guardarLectura(clave: String, valor: Double, sincronizado: Int = 1) {
        val fila = ContentValues().apply {
            put("momento", System.currentTimeMillis())
            put("clave", clave)
            put("valor", valor)
            put("sincronizado", sincronizado)
        }
        writableDatabase.insert("lecturas", null, fila)
    }

    fun obtenerLecturasNoSincronizadas(): List<LecturaPendiente> {
        val lista = mutableListOf<LecturaPendiente>()
        readableDatabase.rawQuery(
            "SELECT id, momento, clave, valor FROM lecturas WHERE sincronizado = 0 ORDER BY id ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                lista.add(
                    LecturaPendiente(
                        id = c.getInt(0),
                        momento = c.getLong(1),
                        clave = c.getString(2),
                        valor = c.getDouble(3)
                    )
                )
            }
        }
        return lista
    }

    fun marcarLecturaSincronizada(id: Int) {
        val fila = ContentValues().apply { put("sincronizado", 1) }
        writableDatabase.update("lecturas", fila, "id = ?", arrayOf(id.toString()))
    }

    // Cola de contingencia para comandos de actuadores sin conexión (Paso 4)
    fun guardarComandoPendiente(topico: String, payload: String) {
        val fila = ContentValues().apply {
            put("momento", System.currentTimeMillis())
            put("topico", topico)
            put("payload", payload)
        }
        writableDatabase.insert("comandos_pendientes", null, fila)
    }

    fun obtenerComandosPendientes(): List<ComandoPendiente> {
        val lista = mutableListOf<ComandoPendiente>()
        readableDatabase.rawQuery(
            "SELECT id, momento, topico, payload FROM comandos_pendientes ORDER BY id ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                lista.add(
                    ComandoPendiente(
                        id = c.getInt(0),
                        momento = c.getLong(1),
                        topico = c.getString(2),
                        payload = c.getString(3)
                    )
                )
            }
        }
        return lista
    }

    fun eliminarComandoPendiente(id: Int) {
        writableDatabase.delete("comandos_pendientes", "id = ?", arrayOf(id.toString()))
    }
}