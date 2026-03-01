// rust_core/src/lib.rs
use jni::JNIEnv;
use jni::objects::{JClass, JString, JByteBuffer};
use jni::sys::{jstring, jboolean, jfloatArray, JNI_TRUE, JNI_FALSE};
use std::sync::Mutex;
use std::ffi::CString;
use log::error;
use sherpa_rs_sys::*;

// =========================================================================================
// ESTRUCTURAS DE MANEJO SEGURO DE MEMORIA (WRAPPERS)
// =========================================================================================

pub struct EcapaHandle(pub *const SherpaOnnxSpeakerEmbeddingExtractor);
unsafe impl Send for EcapaHandle {}
unsafe impl Sync for EcapaHandle {}

pub struct TranscriberHandle(pub *const SherpaOnnxOfflineRecognizer);
unsafe impl Send for TranscriberHandle {}
unsafe impl Sync for TranscriberHandle {}

static ECAPA_EXTRACTOR: Mutex<Option<EcapaHandle>> = Mutex::new(None);
static OFFLINE_RECOGNIZER: Mutex<Option<TranscriberHandle>> = Mutex::new(None);

// =========================================================================================
// UTILIDADES INTERNAS
// =========================================================================================

fn escape_json_string(s: &str) -> String {
    s.replace('\\', "\\\\")
     .replace('"', "\\\"")
     .replace('\n', "\\n")
     .replace('\r', "\\r")
     .replace('\t', "\\t")
}

// =========================================================================================
// INTERFACES JNI (JAVA NATIVE INTERFACE)
// =========================================================================================

#[no_mangle]
pub extern "system" fn Java_jhonatan_s_voice_1context_RustCore_helloFromRust<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let output = env.new_string("¡Motor Rust Bilingüe inicializado con rigor!").expect("Error");
    output.into_raw()
}

// --- 1. INICIALIZAR ECAPA (Biometría) ---
#[no_mangle]
pub extern "system" fn Java_jhonatan_s_voice_1context_RustCore_initEcapaEngine<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path_jni: JString<'local>,
) -> jboolean {
    let _ = android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    let path_rust: String = match env.get_string(&path_jni) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    let mut guard = ECAPA_EXTRACTOR.lock().unwrap();
    if guard.is_some() { return JNI_TRUE; }

    let c_model_path = match CString::new(path_rust) {
        Ok(c) => c,
        Err(_) => return JNI_FALSE,
    };
    let c_provider = CString::new("cpu").unwrap();

    let mut config: SherpaOnnxSpeakerEmbeddingExtractorConfig = unsafe { std::mem::zeroed() };
    config.model = c_model_path.as_ptr();
    config.num_threads = 1;
    config.debug = 0;
    config.provider = c_provider.as_ptr();

    unsafe {
        let extractor_ptr = SherpaOnnxCreateSpeakerEmbeddingExtractor(&config);
        if extractor_ptr.is_null() { 
            error!("Error Rust: No se pudo crear el extractor ECAPA.");
            return JNI_FALSE; 
        }
        *guard = Some(EcapaHandle(extractor_ptr));
        JNI_TRUE
    }
}

// --- 2. EXTRAER VECTOR BIOMÉTRICO (ECAPA con VAD Activo) ---
#[no_mangle]
pub extern "system" fn Java_jhonatan_s_voice_1context_RustCore_extractVoicePrint<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    buffer: JByteBuffer<'local>,
) -> jfloatArray {
    
    let ptr_result = env.get_direct_buffer_address(&buffer);
    if ptr_result.is_err() { return std::ptr::null_mut(); }
    
    let raw_ptr = ptr_result.unwrap();
    let capacity = env.get_direct_buffer_capacity(&buffer).unwrap_or(0);
    if raw_ptr.is_null() || capacity < 2 { return std::ptr::null_mut(); }

    let buffer_data: &[u8] = unsafe { std::slice::from_raw_parts(raw_ptr, capacity) };
    let mut samples_f32: Vec<f32> = Vec::with_capacity(buffer_data.len() / 2);
    for chunk in buffer_data.chunks_exact(2) {
        let sample_i16 = i16::from_le_bytes([chunk[0], chunk[1]]);
        samples_f32.push(sample_i16 as f32 / 32768.0);
    }

    let frame_size = 480; 
    let mut max_energy = 0.0f32;
    let mut frame_energies = Vec::with_capacity(samples_f32.len() / frame_size + 1);

    for frame in samples_f32.chunks(frame_size) {
        let energy: f32 = frame.iter().map(|&x| x * x).sum::<f32>() / frame.len() as f32;
        frame_energies.push(energy);
        if energy > max_energy { max_energy = energy; }
    }

    if max_energy < 1e-6 { return std::ptr::null_mut(); }

    let threshold = max_energy * 0.005;
    let mut active_samples = Vec::with_capacity(samples_f32.len());
    for (i, frame) in samples_f32.chunks(frame_size).enumerate() {
        if frame_energies[i] > threshold {
            active_samples.extend_from_slice(frame);
        }
    }

    if active_samples.is_empty() { active_samples = samples_f32; }

    // ALCANCE REDUCIDO DEL MUTEX
    // Clonamos el puntero crudo y soltamos el Lock inmediatamente.
    let extractor_ptr = {
        let guard = ECAPA_EXTRACTOR.lock().unwrap();
        match guard.as_ref() {
            Some(handle) => handle.0,
            None => return std::ptr::null_mut(),
        }
    };

    unsafe {
        let stream_ptr = SherpaOnnxSpeakerEmbeddingExtractorCreateStream(extractor_ptr);
        if stream_ptr.is_null() { return std::ptr::null_mut(); }
        
        SherpaOnnxOnlineStreamAcceptWaveform(
            stream_ptr as *const _, 
            16000, 
            active_samples.as_ptr(), 
            active_samples.len() as i32
        );
        
        let embedding_ptr = SherpaOnnxSpeakerEmbeddingExtractorComputeEmbedding(extractor_ptr, stream_ptr);
        if embedding_ptr.is_null() {
            SherpaOnnxDestroyOnlineStream(stream_ptr as *const _);
            return std::ptr::null_mut();
        }

        let dim = SherpaOnnxSpeakerEmbeddingExtractorDim(extractor_ptr) as usize;
        let final_embedding: &[f32] = std::slice::from_raw_parts(embedding_ptr, dim);

        let java_float_array = env.new_float_array(dim as i32).unwrap();
        env.set_float_array_region(&java_float_array, 0, final_embedding).unwrap();

        SherpaOnnxDestroyOnlineStream(stream_ptr as *const _);
        java_float_array.into_raw()
    }
}

// --- 3. INICIALIZAR TRANSCRIPTOR (Whisper/SenseVoice) ---
#[no_mangle]
pub extern "system" fn Java_jhonatan_s_voice_1context_RustCore_initTranscriberEngine<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path_jni: JString<'local>,
    type_jni: JString<'local>,
) -> jboolean {
    let path_rust: String = match env.get_string(&path_jni) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let type_rust: String = match env.get_string(&type_jni) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    let mut guard = OFFLINE_RECOGNIZER.lock().unwrap();
    if guard.is_some() { return JNI_TRUE; }

    let mut config: SherpaOnnxOfflineRecognizerConfig = unsafe { std::mem::zeroed() };
    let c_provider = CString::new("cpu").unwrap();
    config.model_config.provider = c_provider.as_ptr();
    config.model_config.num_threads = 4;
    config.model_config.debug = 0;
    config.feat_config.sample_rate = 16000;
    config.feat_config.feature_dim = 80;

    let encoder_path;
    let decoder_path;
    let model_path;
    let tokens_path = CString::new(format!("{}/tokens.txt", path_rust)).unwrap();
    let model_type;
    let language;

    let recognizer_ptr = if type_rust == "sensevoice" {
        model_path = CString::new(format!("{}/model.int8.onnx", path_rust)).unwrap();
        model_type = CString::new("sense_voice").unwrap();
        language = CString::new("auto").unwrap();

        config.model_config.sense_voice.model = model_path.as_ptr();
        config.model_config.tokens = tokens_path.as_ptr();
        config.model_config.model_type = model_type.as_ptr();
        config.model_config.sense_voice.language = language.as_ptr();
        config.model_config.sense_voice.use_itn = 1;

        unsafe { SherpaOnnxCreateOfflineRecognizer(&config) }
    } else {
        encoder_path = CString::new(format!("{}/encoder.int8.onnx", path_rust)).unwrap();
        decoder_path = CString::new(format!("{}/decoder.int8.onnx", path_rust)).unwrap();
        model_type = CString::new("whisper").unwrap();
        language = CString::new("").unwrap(); // String vacío para auto-detect bilingüe

        config.model_config.whisper.encoder = encoder_path.as_ptr();
        config.model_config.whisper.decoder = decoder_path.as_ptr();
        config.model_config.tokens = tokens_path.as_ptr();
        config.model_config.model_type = model_type.as_ptr();
        config.model_config.whisper.language = language.as_ptr();

        unsafe { SherpaOnnxCreateOfflineRecognizer(&config) }
    };

    if recognizer_ptr.is_null() { 
        error!("Error IA: No se pudo crear el reconocedor offline.");
        return JNI_FALSE; 
    }

    *guard = Some(TranscriberHandle(recognizer_ptr));
    JNI_TRUE
}

// --- 4. LIBERAR MEMORIA ---
#[no_mangle]
pub extern "system" fn Java_jhonatan_s_voice_1context_RustCore_freeTranscriberEngine<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    let mut guard = OFFLINE_RECOGNIZER.lock().unwrap();
    if let Some(handle) = guard.take() {
        unsafe { 
            SherpaOnnxDestroyOfflineRecognizer(handle.0);
            error!("Rust: Memoria del Transcriptor liberada correctamente.");
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_jhonatan_s_voice_1context_RustCore_freeEcapaEngine<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    let mut guard = ECAPA_EXTRACTOR.lock().unwrap();
    if let Some(handle) = guard.take() {
        unsafe { 
            SherpaOnnxDestroySpeakerEmbeddingExtractor(handle.0);
            error!("Rust: Memoria de ECAPA liberada correctamente.");
        }
    }
}

// --- 5. TRANSCRIBIR AUDIO (Gating VAD) ---
#[no_mangle]
pub extern "system" fn Java_jhonatan_s_voice_1context_RustCore_transcribeAudio<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    buffer: JByteBuffer<'local>,
) -> jstring {
    
    let empty_json = r#"{"text": "", "start_offset_ms": 0, "end_offset_ms": 0}"#;
    
    let ptr_result = env.get_direct_buffer_address(&buffer);
    if ptr_result.is_err() { return env.new_string(empty_json).unwrap().into_raw(); }
    
    let raw_ptr = ptr_result.unwrap();
    let capacity = env.get_direct_buffer_capacity(&buffer).unwrap_or(0);
    if raw_ptr.is_null() || capacity < 2 { return env.new_string(empty_json).unwrap().into_raw(); }

    let buffer_data: &[u8] = unsafe { std::slice::from_raw_parts(raw_ptr, capacity) };
    let mut samples_f32: Vec<f32> = Vec::with_capacity(buffer_data.len() / 2);
    
    let mut max_amplitude: f32 = 0.0;
    for chunk in buffer_data.chunks_exact(2) {
        let sample_i16 = i16::from_le_bytes([chunk[0], chunk[1]]);
        let sample_f32 = sample_i16 as f32 / 32768.0; 
        
        let abs_s = sample_f32.abs();
        if abs_s > max_amplitude {
            max_amplitude = abs_s;
        }
        
        samples_f32.push(sample_f32);
    }

    if max_amplitude < 0.02 {
        return env.new_string(empty_json).unwrap().into_raw();
    }

    // ALCANCE REDUCIDO DEL MUTEX
    // Obtenemos el puntero y soltamos el candado ANTES de la costosa inferencia neuronal.
    let recognizer_ptr = {
        let guard = OFFLINE_RECOGNIZER.lock().unwrap();
        match guard.as_ref() {
            Some(handle) => handle.0,
            None => return env.new_string(r#"{"error": "No inicializado"}"#).unwrap().into_raw(),
        }
    };

    unsafe {
        let stream_ptr = SherpaOnnxCreateOfflineStream(recognizer_ptr);
        SherpaOnnxAcceptWaveformOffline(stream_ptr as *const _, 16000, samples_f32.as_ptr(), samples_f32.len() as i32);
        
        // ¡Magia Paralela! Ahora Kotlin puede enviar múltiples bloques simultáneos y se procesarán en verdaderos hilos separados de C++
        SherpaOnnxDecodeOfflineStream(recognizer_ptr, stream_ptr);
        
        let result_ptr = SherpaOnnxGetOfflineStreamResult(stream_ptr);
        let text_c = std::ffi::CStr::from_ptr((*result_ptr).text);
        let text_rust = text_c.to_string_lossy().into_owned();

        // Destruir el stream libera toda su memoria interna de resultados en la API C
        SherpaOnnxDestroyOfflineStream(stream_ptr as *const _);

        if text_rust.trim().is_empty() {
            return env.new_string(empty_json).unwrap().into_raw();
        }

        let duration_ms = (samples_f32.len() as f64 / 16.0).round() as u64;
        let escaped_text = escape_json_string(&text_rust);
        
        let json_result = format!(
            r#"{{"text": "{}", "start_offset_ms": 0, "end_offset_ms": {}}}"#,
            escaped_text, duration_ms
        );

        // Seguridad extra: unwrap_or_else para evitar crashes
        env.new_string(json_result).unwrap_or_else(|_| env.new_string(empty_json).unwrap()).into_raw()
    }
}