#![allow(non_snake_case)]

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring};
use lazy_static::lazy_static;
use std::path::Path;
use std::fs::File;
use std::panic;
use parking_lot::RwLock;

// Logging profesional para Android
use android_logger::Config;
use log::{info, error, warn, LevelFilter};

// ONNX & Tokenizers
use ort::session::Session;
use ort::session::builder::GraphOptimizationLevel;
use tokenizers::Tokenizer;
use cozo::{Db, ScriptMutability, SqliteStorage, new_cozo_sqlite};

// ============================================================================
// FASE 3: ESTADO GLOBAL (ESTRICTO)
// ============================================================================
lazy_static! {
    static ref JARVIS_DB: RwLock<Option<Db<SqliteStorage>>> = RwLock::new(None);
    static ref JARVIS_ONNX: RwLock<Option<Session>> = RwLock::new(None);
    static ref JARVIS_TOKENIZER: RwLock<Option<Tokenizer>> = RwLock::new(None);
}

// FASE 4: Esquema por bloques para CozoDB v0.7.5
const SCHEMA_BLOCKS: &[&str] = &[
    ":create stored_chunks {chunk_id: String => text: String, timestamp: Int}",
    ":create vec_index {chunk_id: String => vector: [F32; 768]} index {dim: 768, m: 50, ef_construction: 200, dtype: f32}",
    ":create chunk_edges {source_id: String, target_id: String => peso_similitud: Float, tipo_relacion: String}"
];

// ----------------------------------------------------------------------------
// FUNCIÓN 1: INICIALIZACIÓN INTEGRAL (RIGOR ABSOLUTO)
// ----------------------------------------------------------------------------
#[no_mangle]
pub extern "system" fn Java_jhonatan_s_rag_1engine_NativeBridge_initGraphRepository(
    mut env: JNIEnv,
    _class: JClass,
    storage_path_jstring: JString,
    library_path_jstring: JString,
) -> jboolean {
    
    // 0. Inicializar Logger
    let _ = android_logger::init_once(
        Config::default().with_max_level(LevelFilter::Debug).with_tag("JARVIS_RUST")
    );

    // BLINDAJE: Atrapamos cualquier pánico para proteger la JVM
    let result = panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> bool {
        
        // 1. Decodificar Rutas JNI
        let path_str: String = match env.get_string(&storage_path_jstring) {
            Ok(s) => s.into(),
            Err(_) => { error!("Error decodificando storage_path"); return false; }
        };
        
        let lib_path_str: String = match env.get_string(&library_path_jstring) {
            Ok(s) => s.into(),
            Err(_) => { error!("Error decodificando library_path"); return false; }
        };

        info!(">> INICIANDO MOTOR JARVIS RAG (Capa 4) <<");

        // 2. CONFIGURACIÓN DE ENLACE DINÁMICO (HEURÍSTICA)
        let onnx_lib_path = Path::new(&lib_path_str).join("libonnxruntime.so");
        if onnx_lib_path.exists() {
            info!("Librería física ONNX detectada en: {:?}", onnx_lib_path);
            std::env::set_var("ORT_DYLIB_PATH", onnx_lib_path.as_os_str());
        }

        // 3. Inicializar Entorno ONNX (CORRECCIÓN DE TIPO BOOL)
        info!("Sincronizando entorno neuronal...");
        let ort_ready = ort::init().with_name("JarvisRAG").commit();
        if !ort_ready {
            warn!("ORT ya estaba inicializado o devolvió false.");
        }
        
        let base_path = Path::new(&path_str);

        // 4. Inicializar CozoDB (SQLite Vectorial)
        info!("Despertando motor Datalog...");
        let db_path = base_path.join("jarvis_knowledge.db");
        let db_instance = match new_cozo_sqlite(db_path.to_str().unwrap_or("")) {
            Ok(db) => db,
            Err(e) => { error!("Error Crítico CozoDB: {}", e); return false; }
        };
        
        for block in SCHEMA_BLOCKS {
            if let Err(e) = db_instance.run_script(block, Default::default(), ScriptMutability::Mutable) {
                info!("Nota de esquema: {}", e); 
            }
        }

        // 5. Inicializar Tokenizer
        info!("Cargando Tokenizer Nomic...");
        let tok_path = base_path.join("tokenizer.json");
        let tokenizer = match Tokenizer::from_file(&tok_path) {
            Ok(t) => t,
            Err(e) => { error!("Error Tokenizer: {}", e); return false; }
        };

        // 6. Inicializar ONNX Session (RIGOR DE CARGA)
        let model_path = base_path.join("model_quantized.onnx");
        info!("Mapeando modelo ONNX (mmap): {:?}", model_path);

        let session = match Session::builder() {
            Ok(builder) => {
                // Intentamos aplicar optimización L3. Si falla, usamos el builder original.
                let final_builder = match builder.clone().with_optimization_level(GraphOptimizationLevel::Level3) {
                    Ok(opt) => opt,
                    Err(e) => {
                        warn!("Optimización L3 falló ({}). Usando configuración base.", e);
                        builder
                    }
                };

                match final_builder.commit_from_file(&model_path) {
                    Ok(s) => s,
                    Err(e) => { 
                        error!("ERROR CRÍTICO AL CARGAR MODELO ONNX: {:?}", e); 
                        return false; 
                    }
                }
            },
            Err(e) => { 
                error!("ERROR AL CREAR SessionBuilder (¿Librerías .so enlazadas?): {:?}", e); 
                return false; 
            }
        };

        // 7. Consolidar Estado Global
        let mut db_lock = JARVIS_DB.write();
        let mut onnx_lock = JARVIS_ONNX.write();
        let mut tok_lock = JARVIS_TOKENIZER.write();
        
        *db_lock = Some(db_instance);
        *onnx_lock = Some(session);
        *tok_lock = Some(tokenizer);

        info!("✅ [MODULO RAG OPERATIVO] Capas 1-4 enlazadas con éxito.");
        true
    }));

    match result {
        Ok(success) => if success { 1 } else { 0 },
        Err(_) => {
            error!("🚨 EL MOTOR NATIVO COLAPSÓ (PANIC). PROTECCIÓN JVM ACTIVA.");
            0
        }
    }
}

// ----------------------------------------------------------------------------
// FUNCIÓN 2: INGESTA (Stub funcional)
// ----------------------------------------------------------------------------
#[no_mangle]
pub extern "system" fn Java_jhonatan_s_rag_1engine_NativeBridge_ingestDataViaMmap(
    mut env: JNIEnv,
    _class: JClass,
    path_jstring: JString,
) -> jboolean {
    let path_str: String = match env.get_string(&path_jstring) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    if JARVIS_DB.read().is_none() { return 0; }
    let file = match File::open(&path_str) {
        Ok(f) => f,
        Err(_) => return 0,
    };
    if let Ok(_mmap) = unsafe { memmap2::Mmap::map(&file) } {
        info!("Mmap de datos exitoso.");
        1
    } else { 0 }
}

// ----------------------------------------------------------------------------
// FUNCIÓN 3: BÚSQUEDA (Reactiva)
// ----------------------------------------------------------------------------
#[no_mangle]
pub extern "system" fn Java_jhonatan_s_rag_1engine_NativeBridge_queryGraphRag<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    query_jstring: JString<'local>,
) -> jstring {
    let query_str: String = match env.get_string(&query_jstring) {
        Ok(q) => q.into(),
        Err(_) => "Error".to_string(),
    };
    let db_guard = JARVIS_DB.read();
    if let Some(ref _db) = *db_guard {
        let response = format!(r#"{{"status": "ok", "engine": "Active", "query": "{}"}}"#, query_str);
        env.new_string(response).unwrap().into_raw()
    } else {
        env.new_string(r#"{"error": "Motor offline"}"#).unwrap().into_raw()
    }
}