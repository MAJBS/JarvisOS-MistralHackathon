// app/src/main/java/jhonatan/s/jarvisrag/ui/AuditoryCortexScreen.kt
package jhonatan.s.jarvisrag.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.lazy.items

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditoryCortexScreen(viewModel: ReasoningViewModel) {
    val context = LocalContext.current
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val transcriptions by viewModel.liveTranscriptions.collectAsStateWithLifecycle()
    val profiles by viewModel.remoteProfiles.collectAsStateWithLifecycle()

    val progress by viewModel.recordingProgress.collectAsStateWithLifecycle()
    val timerText by viewModel.currentTimer.collectAsStateWithLifecycle()

    val verificationAlert by viewModel.verificationAlert.collectAsStateWithLifecycle()

    // 🗑️ ELIMINADO: val jsonlContent ... (Causante de la pantalla verde)

    var newProfileName by remember { mutableStateOf("") }
    var selectedProfile by remember { mutableStateOf<RemoteSpeakerProfile?>(null) }
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    var isBiometricsExpanded by remember { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(targetValue = if (isBiometricsExpanded) 180f else 0f)

    if (verificationAlert != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearVerificationAlert() },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("ANÁLISIS BIOMÉTRICO", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = verificationAlert!!,
                    color = if (verificationAlert!!.contains("MATCH")) Color(0xFF00E676) else Color(0xFFFF5252),
                    fontSize = 16.sp, fontWeight = FontWeight.Medium
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearVerificationAlert() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                ) {
                    Text("ENTENDIDO")
                }
            }
        )
    }

    // 🗑️ ELIMINADO: Bloque if (jsonlContent != null) { AlertDialog(...) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .padding(12.dp)
    ) {
        // ====================================================================
        // 0. BOTÓN MAESTRO DE ESCUCHA CONTINUA
        // ====================================================================
        Button(
            onClick = { viewModel.toggleEars(!isListening) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isListening) Color(0xFFB71C1C) else Color(0xFF00E676),
                contentColor = if (isListening) Color.White else Color.Black
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isListening) "DETENER CORTEZA AUDITIVA" else "INICIAR ESCUCHA CONTINUA",
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        Spacer(Modifier.height(16.dp))

        // ====================================================================
        // 1. LIVE FEED (Transcripciones en tiempo real)
        // ====================================================================
        Text("SEÑAL ACÚSTICA EN VIVO", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black, RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF222222), RoundedCornerShape(8.dp))
                .padding(8.dp),
            reverseLayout = true
        ) {
            if (transcriptions.isEmpty()) {
                item {
                    Text(
                        text = "Esperando señales acústicas...\nLos datos se guardan en transcriptions.jsonl",
                        color = Color.DarkGray,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(20.dp)
                    )
                }
            }
            items(transcriptions.reversed()) { msg ->
                val isKnown = msg.speaker != "Desconocido"
                val speakerColor = if (isKnown) Color(0xFF29B6F6) else Color(0xFFFFB74D)

                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "[${timeFormatter.format(Date(msg.timestamp))}]",
                            color = Color.Gray,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = msg.speaker,
                            color = speakerColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                    Text(
                        text = msg.text,
                        color = Color(0xFFE0E0E0),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                    HorizontalDivider(
                        color = Color(0xFF222222),
                        thickness = 1.dp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ====================================================================
        // 2. PANEL DESPLEGABLE: LABORATORIO BIOMÉTRICO & TOPOLOGÍA
        // ====================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF151515)),
            border = BorderStroke(1.dp, Color(0xFF333333)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column {
                // CABECERA CLICABLE
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isBiometricsExpanded = !isBiometricsExpanded }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null, tint = Color(0xFF00E676))
                        Spacer(Modifier.width(8.dp))
                        Text("PANEL BIOMÉTRICO Y TOPOLOGÍA", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expandir",
                        tint = Color.Gray,
                        modifier = Modifier.rotate(rotationAngle)
                    )
                }

                // CONTENIDO DESPLEGABLE
                AnimatedVisibility(visible = isBiometricsExpanded) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {

                        // --- ZONA DE MATRICULACIÓN ---
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newProfileName,
                                onValueChange = { newProfileName = it },
                                placeholder = { Text("ID de Locutor", fontSize = 12.sp) },
                                modifier = Modifier.weight(1f).height(50.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF29B6F6),
                                    unfocusedBorderColor = Color.DarkGray,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            Spacer(Modifier.width(8.dp))

                            val profileExists = profiles.any { it.name.equals(newProfileName, ignoreCase = true) }
                            val btnColor = if (profileExists) Color(0xFF8E24AA) else Color(0xFF1976D2)
                            val btnText = if (profileExists) "MEJORAR (12s)" else "NUEVO (12s)"

                            Button(
                                onClick = {
                                    if (newProfileName.isNotBlank()) {
                                        viewModel.enrollRemoteProfile(newProfileName)
                                        newProfileName = ""
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = btnColor),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(50.dp),
                                enabled = timerText.isEmpty()
                            ) {
                                Text(btnText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // --- BOTÓN DE VERIFICACIÓN ---
                        Button(
                            onClick = { viewModel.verifyRemoteSpeaker() },
                            modifier = Modifier.fillMaxWidth().height(45.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00695C)),
                            shape = RoundedCornerShape(8.dp),
                            enabled = timerText.isEmpty() && profiles.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Fingerprint, null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("VERIFICAR IDENTIDAD (8s)", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }

                        // --- CRONÓMETRO ---
                        AnimatedVisibility(visible = timerText.isNotEmpty()) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                                    .border(1.dp, Color(0xFF00E676), RoundedCornerShape(8.dp))
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("🎙️ CAPTURANDO MUESTRA...", color = Color(0xFF00E676), fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 2.sp)

                                Text(
                                    text = timerText,
                                    color = Color.White,
                                    fontSize = 32.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )

                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = Color(0xFF00E676),
                                    trackColor = Color.DarkGray
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // --- LA MATRIX (Perfiles y Heatmap) ---
                        Text("TOPOLOGÍA NEURONAL (768 DIMENSIONES)", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)

                        LazyRow(
                            modifier = Modifier.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(profiles) { profile ->
                                val isSelected = selectedProfile?.name == profile.name
                                Surface(
                                    color = if (isSelected) Color(0xFF29B6F6) else Color(0xFF222222),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.clickable { selectedProfile = profile }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = null,
                                            tint = if (isSelected) Color.Black else Color.Gray,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "${profile.name} (x${profile.enrollmentCount})",
                                            color = if (isSelected) Color.Black else Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        if (selectedProfile != null) {
                            val profile = selectedProfile!!
                            Card(
                                modifier = Modifier.fillMaxWidth().height(140.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.Black),
                                border = BorderStroke(1.dp, Color(0xFF333333))
                            ) {
                                Column(Modifier.padding(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Vector Maestro: ${profile.name}",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                        IconButton(
                                            onClick = {
                                                viewModel.deleteRemoteProfile(profile.name)
                                                selectedProfile = null
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, "Borrar", tint = Color(0xFFFF5252))
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    VectorHeatmap(profile.vector)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun VectorHeatmap(vector: FloatArray) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(4.dp),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        items(vector.size) { index ->
            val value = vector[index]
            val color = when {
                value > 0 -> Color(0f, 1f, 1f, (value * 5).coerceIn(0.1f, 1f))
                value < 0 -> Color(1f, 0f, 0.5f, (Math.abs(value) * 5).coerceIn(0.1f, 1f))
                else -> Color(0.1f, 0.1f, 0.1f)
            }
            Box(Modifier.size(4.dp).background(color, shape = CircleShape))
        }
    }
}

