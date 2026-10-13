package com.example.invernadero

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val pedirPermisos = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) pedirPermisos.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))

        val db = BaseDatos(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var pantalla by remember { mutableStateOf("login") }
                    var usuarioActual by remember { mutableStateOf("") }
                    var keyMQTT by remember { mutableIntStateOf(0) }
                    val mqtt = remember(keyMQTT) { ConexionMQTT() }

                    when (pantalla) {
                        "login" -> PantallaLogin(db,
                            onIngreso = { usr -> usuarioActual = usr; pantalla = "menu" }
                        )
                        "menu" -> {
                            Column(modifier = Modifier.padding(24.dp)) {
                                Text("Conexión al Invernadero", style = MaterialTheme.typography.headlineMedium)
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(onClick = {
                                    mqtt.conectar()
                                    pantalla = "panel"
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Conectar por WiFi (HiveMQ)")
                                }
                            }
                        }
                        "panel" -> PantallaPanel(mqtt, usuarioActual, onSalir = {
                            mqtt.desconectar()
                            pantalla = "menu"
                            keyMQTT++
                        })
                    }
                }
            }
        }
    }
}

@Composable
fun PantallaLogin(db: BaseDatos, onIngreso: (String) -> Unit) {
    var usr by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var mensaje by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(24.dp)) {
        Text("Login - Invernadero", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(value = usr, onValueChange = { usr = it }, label = { Text("Usuario") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Contraseña") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            if (usr.isNotBlank() && pass.isNotBlank()) {
                val rol = db.autenticar(usr, pass.toCharArray())
                if (rol != null) onIngreso(usr) else mensaje = "Error: Usuario/Clave incorrectos"
            } else {
                mensaje = "Por favor ingrese usuario y contraseña"
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Ingresar") }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(onClick = {
            if (usr.isNotBlank() && pass.isNotBlank()) {
                if (pass.length >= 8) {
                    val rol = db.registrar(usr, pass.toCharArray())
                    if (rol != null) onIngreso(usr) else mensaje = "El usuario ya existe"
                } else mensaje = "Mínimo 8 caracteres"
            } else {
                mensaje = "Los campos no pueden estar vacíos"
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Crear Cuenta") }

        Text(mensaje, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun PantallaPanel(mqtt: ConexionMQTT, usuario: String, onSalir: () -> Unit) {
    val estado by mqtt.estado.collectAsState()
    val temp by mqtt.temperatura.collectAsState()
    val humedad by mqtt.humedadSuelo.collectAsState()

    Column(modifier = Modifier.padding(24.dp)) {
        Text("Invernadero IoT", style = MaterialTheme.typography.headlineMedium)
        Text(text = "Usuario: $usuario | Estado: $estado")
        Spacer(modifier = Modifier.height(32.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Sensores en vivo", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Temperatura (DHT22): $temp")
                Text("Humedad Suelo: $humedad")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text("Control Manual", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(
                onClick = { mqtt.controlarBomba("ON") },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Encender")
            }
            Button(
                onClick = { mqtt.controlarBomba("OFF") },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Apagar")
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onSalir, modifier = Modifier.fillMaxWidth()) { Text("Desconectar") }
    }
}