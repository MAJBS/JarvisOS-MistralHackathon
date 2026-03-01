// app/src/main/java/jhonatan/s/jarvisrag/MainActivity.kt
package jhonatan.s.jarvisrag

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jhonatan.s.jarvisrag.network.ElevenLabsClient
import jhonatan.s.jarvisrag.ui.AuditoryCortexScreen
import jhonatan.s.jarvisrag.ui.ReasoningState
import jhonatan.s.jarvisrag.ui.ReasoningViewModel
import jhonatan.s.jarvisrag.ui.theme.JarvisRagTheme
import jhonatan.s.rag_engine.JarvisBootstrapper
import jhonatan.s.rag_engine.RagResultNode
import jhonatan.s.rag_engine.RagViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val engineInitialized = mutableStateOf(false)
        val initError = mutableStateOf<String?>(null)

        val externalFilesDir = getExternalFilesDir(null)
        // Modelo Qwen para modo local (Edge)
        //val modelFile = File(externalFilesDir, "qwen2.5-0.5b-instruct-q4_0.gguf")
        val modelFile = File(externalFilesDir, "mistral-7b-instruct-v0.3-q2_k.gguf")
        val absoluteModelPath = modelFile.absolutePath

        lifecycleScope.launch {
            val success = JarvisBootstrapper.prepareAndInit(applicationContext)
            if (success) {
                engineInitialized.value = true
            } else {
                initError.value = "FATAL: Error al inicializar CozoDB/ONNX. Revisa Logcat (JARVIS_RUST)."
            }
        }

        setContent {
            JarvisRagTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
                    if (!engineInitialized.value && initError.value == null) {
                        LoadingScreen()
                    } else if (initError.value != null) {
                        ErrorScreen(initError.value!!)
                    } else {
                        MainAppContainer(absoluteModelPath = absoluteModelPath)
                    }
                }
            }
        }
    }
}

@Composable
fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = Color(0xFF00E676))
        Spacer(modifier = Modifier.height(16.dp))
        Text("Cargando Red Neuronal (INT8)...", color = Color.Gray)
        Text("Mapeando Vectores a Memoria...", color = Color.DarkGray, fontSize = 12.sp)
    }
}

@Composable
fun ErrorScreen(msg: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFB00020)),
            modifier = Modifier.padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("ERROR CRÍTICO DEL MOTOR", fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                Text(msg, color = Color.White)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContainer(
    ragViewModel: RagViewModel = viewModel(),
    reasoningViewModel: ReasoningViewModel = viewModel(),
    absoluteModelPath: String
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("IA Mayor", "Corteza Auditiva", "Memoria RAG", "Dev & Oracle")

    Scaffold(
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Terminal, contentDescription = null, tint = Color(0xFF00E676))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("JARVIS OS", fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color(0xFF0D0D0D),
                        titleContentColor = Color.White
                    )
                )
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color(0xFF1E1E1E),
                    contentColor = Color(0xFF00E676),
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = Color(0xFF00E676)
                        )
                    }
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1) },
                            unselectedContentColor = Color.Gray
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color(0xFF121212))) {
            when (selectedTab) {
                0 -> JarvisCognitiveScreen(reasoningViewModel, absoluteModelPath)
                1 -> AuditoryCortexScreen(reasoningViewModel)
                2 -> AssistantScreen(ragViewModel)
                3 -> TacticalDashboard(ragViewModel)
            }
        }
    }
}

// ============================================================================
// PANTALLA 1: IA MAYOR (ORQUESTADOR SLM + RAG + TERMINAL)
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisCognitiveScreen(
    viewModel: ReasoningViewModel,
    modelPath: String
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val telemetryLogs by viewModel.telemetryLogs.collectAsStateWithLifecycle()

    // ESTADOS NUEVOS (Hibridación)
    val isOnlineMode by viewModel.isOnlineMode.collectAsStateWithLifecycle()
    val isVoiceEnabled by viewModel.isVoiceEnabled.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val terminalListState = rememberLazyListState()
    val context = LocalContext.current

    var isMicActive by remember { mutableStateOf(false) }
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        val clip = ClipData.newPlainText("Jarvis Interaction", text)
        clipboardManager.setPrimaryClip(clip)
        Toast.makeText(context, "Copiado al portapapeles", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) {
        viewModel.bootCognitiveEngine(modelPath, context)
    }

    LaunchedEffect(uiState) {
        if (uiState is ReasoningState.Generating) {
            listState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(telemetryLogs.size) {
        if (telemetryLogs.isNotEmpty()) {
            terminalListState.animateScrollToItem(telemetryLogs.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // 1. ÁREA DE PENSAMIENTO
        Box(modifier = Modifier.weight(0.6f).fillMaxWidth().padding(16.dp)) {
            var currentContentToCopy = ""

            when (val state = uiState) {
                is ReasoningState.Idle -> StatusMessage("Motor Cognitivo Operativo.\nEsperando directiva...", Color.Gray)
                is ReasoningState.LoadingEngine -> StatusMessage("Cargando...\n${state.message}", Color(0xFF3B82F6))
                is ReasoningState.Thinking -> StatusMessage("Procesando RAG...\n${state.operation}", Color(0xFFF59E0B))
                is ReasoningState.Error -> StatusMessage("FALLO CRÍTICO:\n${state.reason}", Color(0xFFEF4444))
                is ReasoningState.Generating -> {
                    currentContentToCopy = state.currentText
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), reverseLayout = true) {
                        item {
                            SelectionContainer {
                                Text(
                                    text = state.currentText,
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 15.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 22.sp
                                )
                            }
                        }
                    }
                }
            }

            if (currentContentToCopy.isNotEmpty()) {
                IconButton(
                    onClick = { copyToClipboard(currentContentToCopy) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color(0x40000000), shape = RoundedCornerShape(50)),
                ) {
                    Icon(Icons.Default.ContentCopy, "Copiar", tint = Color(0xFF00E676), modifier = Modifier.size(20.dp))
                }
            }
        }

        // 2. ORÁCULO DE TELEMETRÍA
        Column(modifier = Modifier.fillMaxWidth().weight(0.4f).background(Color.Black).border(1.dp, Color(0xFF333333))) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Terminal, null, tint = Color(0xFF00E676), modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("KERNEL LOGS", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))

                IconButton(onClick = { isMicActive = !isMicActive; viewModel.toggleEars(isMicActive) }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Mic, "Ears", tint = if (isMicActive) Color.Red else Color.Gray)
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { copyToClipboard(telemetryLogs.joinToString("\n")) }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.ContentCopy, "Logs", tint = Color.Gray)
                }
            }

            SelectionContainer {
                LazyColumn(state = terminalListState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    items(telemetryLogs) { logLine ->
                        val textColor = when {
                            logLine.contains("E JARVIS") || logLine.contains("error", true) -> Color(0xFFFF5252)
                            logLine.contains("I JARVIS") -> Color(0xFF69F0AE)
                            else -> Color(0xFFA0A0A0)
                        }
                        Text(text = logLine, color = textColor, fontSize = 9.sp, fontFamily = FontFamily.Monospace, lineHeight = 12.sp)
                    }
                }
            }
        }

        // ====================================================================
        // PANEL DE CONTROL TÁCTICO (MISTRAL API & ELEVENLABS)
        // ====================================================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF151515))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Switch Offline/Online
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isOnlineMode) "🌐 MISTRAL API" else "🧠 MISTRAL-7b-Q2 LOCAL",
                    color = if (isOnlineMode) Color(0xFF29B6F6) else Color(0xFF00E676),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = isOnlineMode,
                    onCheckedChange = { viewModel.toggleOnlineMode(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF29B6F6),
                        uncheckedThumbColor = Color.Black,
                        uncheckedTrackColor = Color(0xFF00E676)
                    ),
                    modifier = Modifier.scale(0.7f)
                )
            }

            // Botón de Voz (ElevenLabs) con STOP
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "SÍNTESIS VOCAL",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = {
                        if (isVoiceEnabled) {
                            // Detener audio si está sonando
                            ElevenLabsClient.stop()
                            Toast.makeText(context, "Audio detenido", Toast.LENGTH_SHORT).show()
                        }
                        viewModel.toggleVoice(!isVoiceEnabled)
                    }
                ) {
                    Icon(
                        imageVector = if (isVoiceEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = "Toggle Voice",
                        tint = if (isVoiceEnabled) Color(0xFFFFD600) else Color.DarkGray
                    )
                }
            }
        }

        // 3. CONSOLA DE ENTRADA
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Instruye al Núcleo...", color = Color.Gray, fontSize = 13.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.LightGray,
                    focusedBorderColor = Color(0xFF00E676),
                    unfocusedBorderColor = Color.DarkGray,
                    cursorColor = Color(0xFF00E676),
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                ),
                shape = RoundedCornerShape(12.dp),
                maxLines = 3
            )
            Spacer(modifier = Modifier.width(12.dp))
            FloatingActionButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        viewModel.processQuery(inputText.trim(), context)
                        inputText = ""
                    }
                },
                containerColor = Color(0xFF00E676),
                contentColor = Color.Black,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(50.dp)
            ) {
                Icon(Icons.Filled.Send, contentDescription = "Ejecutar")
            }
        }
    }
}

// ============================================================================
// PANTALLA 2: MEMORIA RAG
// ============================================================================
@Composable
fun AssistantScreen(ragViewModel: RagViewModel) {
    val query by ragViewModel.searchQuery.collectAsStateWithLifecycle()
    val response by ragViewModel.structuredResponse.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { ragViewModel.updateQuery(it) },
            placeholder = { Text("Pregunta al grafo...", color = Color.Gray) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.Gray) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00E676),
                unfocusedBorderColor = Color.DarkGray,
                focusedContainerColor = Color(0xFF1E1E1E),
                unfocusedContainerColor = Color(0xFF1E1E1E),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )
        Spacer(modifier = Modifier.height(16.dp))

        AnimatedVisibility(visible = response != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("🧠 MEMORIA RECUPERADA", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    "NODOS: ${response?.results?.size ?: 0} | CONFIANZA: ${response?.confidenceLevel ?: "N/A"}",
                    color = if ((response?.results?.size ?: 0) > 0) Color(0xFF00E676) else Color.Red,
                    fontWeight = FontWeight.Bold, fontSize = 12.sp
                )
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(response?.results ?: emptyList()) { node -> RetrievalCard(node) }
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun RetrievalCard(node: RagResultNode) {
    val isGraphAdjacency = node.distance == 0.0f
    val borderColor = if (isGraphAdjacency) Color(0xFF2962FF) else Color(0xFF00E676)
    val distanceStr = String.format(Locale.US, "%.4f", node.distance)
    val badgeText = if (isGraphAdjacency) "ADYACENCIA ($distanceStr)" else "HNSW ($distanceStr)"
    val timeFormatter = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())
    val dateStr = if (node.startTime > 0L) timeFormatter.format(Date(node.startTime)) else "Sin fecha"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        border = BorderStroke(1.dp, borderColor.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Color(0xFF333333), shape = RoundedCornerShape(4.dp)) {
                        Text("👤 ${node.speaker}", fontSize = 10.sp, color = Color.White, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("⏱️ $dateStr", fontSize = 9.sp, color = Color(0xFFB0BEC5), fontFamily = FontFamily.Monospace)
                }
                Text(badgeText, fontSize = 10.sp, color = borderColor, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            SelectionContainer {
                Text("\"${node.text}\"", color = Color(0xFFE0E0E0), fontSize = 14.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("ID: ${node.chunkId.take(8)}...", color = Color.DarkGray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// ============================================================================
// PANTALLA 3: MODO DEV
// ============================================================================
@Composable
fun TacticalDashboard(ragViewModel: RagViewModel) {
    val rawResults by ragViewModel.ragResults.collectAsStateWithLifecycle()
    val systemStatus by ragViewModel.systemStatus.collectAsStateWithLifecycle()
    val jsonlText by ragViewModel.rawJsonlEditor.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var rawDatalogQuery by remember { mutableStateOf("?[id, txt] := *fragmento_documento[id, txt, _, _]\n:limit 5") }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { ragViewModel.ingestFile(context, it) }
    }

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Jarvis Log", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Log copiado", Toast.LENGTH_SHORT).show()
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF212121)), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("PANEL DE INGESTA EXTERNA (ZERO-COPY)", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { filePicker.launch("*/*") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2979FF)), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp)) {
                        Text("SELECCIONAR ARCHIVO E INYECTAR A COZO")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = systemStatus, color = if (systemStatus.contains("error", true) || systemStatus.contains("❌")) Color.Red else Color(0xFF00E676), fontSize = 11.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("MEMORIA EPISÓDICA INTERNA (JSONL)", color = Color(0xFF00E676), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row {
                            IconButton(onClick = { ragViewModel.clearAllJsonlMemory(context) }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Delete, "Borrar", tint = Color(0xFFFF5252)) }
                            Spacer(modifier = Modifier.width(16.dp))
                            IconButton(onClick = { ragViewModel.loadInternalJsonl(context) }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Refresh, "Cargar", tint = Color.White) }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = jsonlText, onValueChange = { ragViewModel.updateJsonlEditor(it) },
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color.LightGray),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF00E676), unfocusedBorderColor = Color.DarkGray)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { ragViewModel.ingestEditedJsonl(context) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00695C)), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp), enabled = jsonlText.isNotBlank()) {
                        Text("GUARDAR E INYECTAR AL GRAFO", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF263238)), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("ORÁCULO DATALOG", color = Color(0xFFFFD600), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("CozoDB v0.7.5", color = Color.Gray, fontSize = 10.sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = rawDatalogQuery, onValueChange = { rawDatalogQuery = it },
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFECEFF1)),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFFFD600), unfocusedBorderColor = Color.Gray)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SmallScriptButton("DOCS (5)", Modifier.weight(1f)) {
                            rawDatalogQuery = "?[id, txt, meta] := *fragmento_documento[id, txt, _, meta]\n:limit 5"
                            ragViewModel.executeRawDatalog(rawDatalogQuery)
                        }
                        SmallScriptButton("VECS (Check)", Modifier.weight(1f)) {
                            rawDatalogQuery = "?[count(id)] := *vec_index[id, _]"
                            ragViewModel.executeRawDatalog(rawDatalogQuery)
                        }
                        SmallScriptButton("GRAFO (Links)", Modifier.weight(1f)) {
                            rawDatalogQuery = "?[src, tgt] := *chunk_edges[src, tgt, _, _]\n:limit 10"
                            ragViewModel.executeRawDatalog(rawDatalogQuery)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { ragViewModel.executeRawDatalog(rawDatalogQuery) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD600)), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp)) {
                        Text("EJECUTAR SCRIPT", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("MONITOR CRUDO (JSON):", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { copyToClipboard(rawResults) }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.ContentCopy, "Copiar", tint = Color.Gray) }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Surface(modifier = Modifier.fillMaxWidth().height(200.dp), color = Color.Black, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color.DarkGray)) {
                SelectionContainer {
                    LazyColumn(modifier = Modifier.padding(8.dp)) {
                        item { Text(text = rawResults, color = Color(0xFF00E676), fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}

@Composable
fun SmallScriptButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(36.dp), contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242)), shape = RoundedCornerShape(4.dp)) {
        Text(label, fontSize = 10.sp, color = Color.White)
    }
}

@Composable
fun StatusMessage(message: String, color: Color) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = message, color = color, textAlign = TextAlign.Center, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium), modifier = Modifier.padding(16.dp))
    }
}
