#![allow(non_snake_case)]

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring, jboolean};
use lazy_static::lazy_static;
use parking_lot::RwLock;

use std::path::Path;
use std::fs::File;
use std::io::{BufRead, Cursor};
use std::panic;
use std::time::SystemTime;
use std::collections::BTreeMap;

use sha2::{Sha256, Digest};
use android_logger::Config;
use log::{info, error};

use ort::session::Session;
use ort::session::builder::GraphOptimizationLevel;
use tokenizers::Tokenizer;
use cozo::{Db, ScriptMutability, SqliteStorage, new_cozo_sqlite, DataValue, Num, JsonData};
use serde::{Deserialize, Serialize};
use serde_json::json;

// ============================================================================
// CONTRATOS DE DATOS (Estrictos)
// ============================================================================

#[derive(Deserialize, Debug)]
struct TranscriptChunk {
    speaker: String,
    start_time: i64,
    end_time: i64,
    text: String,
}

#[derive(Serialize)]
struct RagResponse {
    status: String,
    error: Option<String>,
    confidence: String,
    results: Vec<RagResultNode>,
}

#[derive(Serialize)]
struct RagResultNode {
    chunk_id: String,
    speaker: String,
    text: String,
    distance: f32,
    start_time: i64,
    end_time: i64,
}

#[derive(Serialize)]
struct EngineStatus {
    status: String,
    error: Option<String>,
    logs: Vec<String>,
    errores: Vec<String>,
}

#[derive(Serialize)]
struct SystemState {
    onnx_loaded: bool,
    db_connected: bool,
    tables_found: Vec<String>,
    counts: BTreeMap<String, i64>,
    error: Option<String>,
}

#[derive(Serialize)]
struct IngestionMetrics {
    status: String,
    chunks_processed: i32,
    execution_time_ms: i64,
    error: Option<String>,
}

// ============================================================================
// MEMORIA Y ORQUESTACIÓN (Off-Heap)
// ============================================================================

lazy_static! {
    static ref JARVIS_DB: RwLock<Option<Db<SqliteStorage>>> = RwLock::new(None);
    static ref JARVIS_ONNX: RwLock<Option<Session>> = RwLock::new(None);
    static ref JARVIS_TOKENIZER: RwLock<Option<Tokenizer>> = RwLock::new(None);
}

const DDL_STEPS: &[&str] = &[
    ":create fragmento_documento { chunk_id: String => texto_contenido: String, timestamp: Int, metadatos: Json }",
    ":create vec_index { chunk_id: String => vector: <F32; 768> }",
    ":create chunk_edges { source_id: String, target_id: String => peso_similitud: Float, tipo_relacion: String }",
    ":create system_rules { rule_id: String => content: String }",
    "::hnsw create vec_index:vector_idx { dim: 768, m: 32, ef_construction: 200, dtype: F32, distance: Cosine, fields: [vector] }",
    "::fts create fragmento_documento:fts_idx { extractor: texto_contenido, tokenizer: Simple }"
];

// ============================================================================
// EXPORTACIONES JNI
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_jhonatan_s_rag_1engine_NativeBridge_initGraphRepository<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, storage_path_jstring: JString<'local>, library_path_jstring: JString<'local>,
) -> jstring {
    let _ = android_logger::init_once(Config::default().with_max_level(log::LevelFilter::Debug).with_tag("JARVIS_RUST"));

    let result = panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> String {
        let path_str = env.get_string(&storage_path_jstring).unwrap().to_str().unwrap().to_owned();
        let lib_path_str = env.get_string(&library_path_jstring).unwrap().to_str().unwrap().to_owned();
        
        let onnx_lib_path = Path::new(&lib_path_str).join("libonnxruntime.so");
        if onnx_lib_path.exists() { std::env::set_var("ORT_DYLIB_PATH", onnx_lib_path.as_os_str()); }
        
        let _ = ort::init().with_name("JarvisRAG").commit();
        let base_path = Path::new(&path_str);
        
        let tokenizer = match Tokenizer::from_file(base_path.join("tokenizer.json")) {
            Ok(t) => t,
            Err(e) => return emit_error(&format!("Tok: {}", e)),
        };
        
        let session = match Session::builder().unwrap()
            .with_optimization_level(GraphOptimizationLevel::Level3).unwrap()
            .commit_from_file(base_path.join("model_quantized.onnx")) {
                Ok(s) => s,
                Err(e) => return emit_error(&format!("ONNX: {}", e)),
        };

        let db_path = base_path.join("jarvis_knowledge.db");
        let db = match new_cozo_sqlite(db_path.to_str().unwrap()) {
            Ok(d) => d,
            Err(e) => return emit_error(&format!("DB: {}", e)),
        };

        for &script in DDL_STEPS { 
            let _ = db.run_script(script, Default::default(), ScriptMutability::Mutable); 
        }
        
        *JARVIS_DB.write() = Some(db);
        *JARVIS_ONNX.write() = Some(session);
        *JARVIS_TOKENIZER.write() = Some(tokenizer);

        info!("✅ JARVIS RAG V8.2 (Memoria Unificada) ONLINE");
        let resp = EngineStatus { status: "success".into(), error: None, logs: vec!["Motor RAG Híbrido V8.2 Operativo".into()], errores: vec![] };
        serde_json::to_string(&resp).unwrap()
    }));

    env.new_string(result.unwrap_or_else(|_| emit_error("Panic Thread Rust"))).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_jhonatan_s_rag_1engine_NativeBridge_queryGraphRag<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, query_j: JString<'local>, max_k: jint
) -> jstring {
    let res = panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> String {
        let q_str = match env.get_string(&query_j) { Ok(s) => s.to_str().unwrap().to_owned(), Err(_) => return emit_rag_error("JNI Read Error") };
        
        let db_r = JARVIS_DB.read();
        let mut onnx_w = JARVIS_ONNX.write(); 
        if db_r.is_none() { return emit_rag_error("Motor Offline"); }
        let db = db_r.as_ref().unwrap();

        let vec = match vectorize(&q_str, JARVIS_TOKENIZER.read().as_ref().unwrap(), onnx_w.as_mut().unwrap()) {
            Ok(v) => v,
            Err(e) => return emit_rag_error(&e),
        };
        let vec_data: Vec<DataValue> = vec.into_iter().map(|f| DataValue::Num(Num::Float(f as f64))).collect();
        
        let safe_q = q_str
            .replace("\"", "")
            .replace("'", "")
            .replace("?", "")
            .replace("¿", "")
            .replace("!", "")
            .replace("¡", "")
            .replace(":", "")
            .replace(";", "");
            
        let final_q = if safe_q.trim().is_empty() { "fallback_vacío".to_string() } else { safe_q.trim().to_string() };
        let fts_query = format!("\"{}\"", final_q);

        let mut params = BTreeMap::new();
        params.insert("vec".into(), DataValue::List(vec_data));
        params.insert("q_str".into(), DataValue::Str(fts_query.into())); 
        params.insert("max_k".into(), DataValue::Num(Num::Int(max_k as i64)));

        let datalog_query = r#"
h_res[id, d] := ~vec_index:vector_idx{ chunk_id: id | query: vec($vec), k: 50, ef: 128, bind_distance: d }
h_rank[id, count(other)] := h_res[id, d1], h_res[other, d2], d1 >= d2
rrf_score[id, v, d] := h_rank[id, r], h_res[id, d], v = 1.0 / (60.0 + r)

f_res[id, s] := ~fragmento_documento:fts_idx{ chunk_id: id | query: $q_str, k: 50, score_kind: 'tf_idf', bind_score: s }
f_rank[id, count(other)] := f_res[id, s1], f_res[other, s2], s1 <= s2
rrf_score[id, v, d] := f_rank[id, r], v = 1.0 / (60.0 + r), d = 0.0

top_seeds[id, sum(v), min(d)] := rrf_score[id, v, d]
top_k[id, v, d] := top_seeds[id, v, d] :limit $max_k :order -v

graph[id, v, d] := top_k[id, v, d]
graph[adj, v_adj, d] := top_k[base, v, d], *chunk_edges[base, adj, _, "next"], v_adj = v - 0.0001
graph[adj, v_adj, d] := top_k[base, v, d], *chunk_edges[adj, base, _, "next"], v_adj = v - 0.0002

best_graph[id, max(v), min(d)] := graph[id, v, d]

?[id, txt, spk, dist, start_t, end_t, v] := best_graph[id, v, dist], *fragmento_documento[id, txt, start_t, m], spk = get(m, 'speaker'), end_t = get(m, 'end_time') :order -v
"#;

        match db.run_script(datalog_query, params, ScriptMutability::Immutable) {
            Ok(result) => {
                let mut nodes = Vec::new();
                for row in result.rows {
                    let id = match &row[0] { DataValue::Str(s) => s.to_string(), _ => "".into() };
                    let txt = match &row[1] { DataValue::Str(s) => s.to_string(), _ => "".into() };
                    let spk = match &row[2] { DataValue::Str(s) => s.to_string(), _ => "DOC".into() };
                    
                    let dist = match &row[3] { 
                        DataValue::Num(Num::Float(f)) => *f as f32, 
                        DataValue::Num(Num::Int(i)) => *i as f32, 
                        _ => 0.0 
                    };
                    
                    let start_time = match &row[4] { DataValue::Num(Num::Int(i)) => *i as i64, _ => 0 };
                    let end_time = match &row[5] { DataValue::Num(Num::Int(i)) => *i as i64, _ => 0 };

                    nodes.push(RagResultNode { chunk_id: id, speaker: spk, text: txt, distance: dist, start_time, end_time });
                }
                
                let confidence = if nodes.is_empty() {
                    "NULA"
                } else if nodes[0].distance == 0.0 {
                    "ALTA" 
                } else if nodes[0].distance <= 0.28 {
                    "ALTA" 
                } else if nodes[0].distance <= 0.36 {
                    "MEDIA" 
                } else {
                    "NULA" 
                };

                let resp = RagResponse { status: "ok".into(), error: None, confidence: confidence.into(), results: nodes };
                serde_json::to_string(&resp).unwrap()
            },
            Err(e) => emit_rag_error(&format!("Datalog: {}", e)),
        }
    }));
    
    env.new_string(res.unwrap_or_else(|_| emit_rag_error("Panic Thread Rust"))).unwrap().into_raw()
}

// ----------------------------------------------------------------------------
// INGESTA ESTÁNDAR (Documentos Externos)
// ----------------------------------------------------------------------------
#[no_mangle]
pub extern "system" fn Java_jhonatan_s_rag_1engine_NativeBridge_ingestDataViaMmap<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, path_j: JString<'local>,
) -> jstring {
    let result = panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> String {
        let p_str = match env.get_string(&path_j) { Ok(s) => s.to_str().unwrap().to_owned(), Err(_) => return emit_ingest_error("JNI String Error") };
        
        // Ingesta estándar se etiqueta como 'external_doc'
        match process_mmap_file(&p_str, "external_doc") {
            Ok((count, elapsed)) => {
                let metrics = IngestionMetrics { status: "ok".into(), chunks_processed: count, execution_time_ms: elapsed, error: None };
                serde_json::to_string(&metrics).unwrap()
            },
            Err(e) => emit_ingest_error(&e)
        }
    }));
    
    env.new_string(result.unwrap_or_else(|_| emit_ingest_error("Panic Thread Rust"))).unwrap().into_raw()
}

// ----------------------------------------------------------------------------
// SINCRONIZACIÓN DE MEMORIA VIVA (Purga y Reconstrucción)
// ----------------------------------------------------------------------------
#[no_mangle]
pub extern "system" fn Java_jhonatan_s_rag_1engine_NativeBridge_syncLiveMemory<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, path_j: JString<'local>,
) -> jstring {
    let result = panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> String {
        let p_str = match env.get_string(&path_j) { Ok(s) => s.to_str().unwrap().to_owned(), Err(_) => return emit_ingest_error("JNI String Error") };
        
        let db_lock = JARVIS_DB.write(); 
        if db_lock.is_none() { return emit_ingest_error("Motor Offline"); }
        let db = db_lock.as_ref().unwrap();

        info!("Iniciando Purga Quirúrgica de Memoria Viva (live_audio)...");
        let start_time = SystemTime::now();

        // 1. PURGA ESTRICTA (CORREGIDA: Nombres de variables coinciden con Schema)

        // A. Borrar aristas donde el origen es live_audio
        // Schema: chunk_edges { source_id, target_id => ... }
        let q_edges_src = "?[source_id, target_id] := *chunk_edges[source_id, target_id, _, _], *fragmento_documento[source_id, _, _, meta], get(meta, 'source') == 'live_audio'; :rm chunk_edges";
        if let Err(e) = db.run_script(q_edges_src, Default::default(), ScriptMutability::Mutable) {
            error!("Fallo al purgar aristas (src): {}", e);
        }

        // B. Borrar aristas donde el destino es live_audio
        let q_edges_tgt = "?[source_id, target_id] := *chunk_edges[source_id, target_id, _, _], *fragmento_documento[target_id, _, _, meta], get(meta, 'source') == 'live_audio'; :rm chunk_edges";
        if let Err(e) = db.run_script(q_edges_tgt, Default::default(), ScriptMutability::Mutable) {
            error!("Fallo al purgar aristas (tgt): {}", e);
        }

        // C. Borrar vectores
        // Schema: vec_index { chunk_id => ... }
        let q_vecs = "?[chunk_id] := *vec_index[chunk_id, _], *fragmento_documento[chunk_id, _, _, meta], get(meta, 'source') == 'live_audio'; :rm vec_index";
        if let Err(e) = db.run_script(q_vecs, Default::default(), ScriptMutability::Mutable) {
            error!("Fallo al purgar vectores: {}", e);
        }

        // D. Borrar documentos base
        // Schema: fragmento_documento { chunk_id => ... }
        let q_docs = "?[chunk_id] := *fragmento_documento[chunk_id, _, _, meta], get(meta, 'source') == 'live_audio'; :rm fragmento_documento";
        if let Err(e) = db.run_script(q_docs, Default::default(), ScriptMutability::Mutable) {
            error!("Fallo al purgar documentos: {}", e);
        }

        // Liberamos el lock de lectura/escritura de la DB para que process_mmap_file pueda tomarlo
        drop(db_lock);

        // 2. RE-INGESTA ZERO-COPY
        info!("Purga completada. Reconstruyendo grafo desde JSONL...");
        match process_mmap_file(&p_str, "live_audio") {
            Ok((count, _)) => {
                let total_elapsed = start_time.elapsed().unwrap().as_millis() as i64;
                let metrics = IngestionMetrics { status: "ok".into(), chunks_processed: count, execution_time_ms: total_elapsed, error: None };
                serde_json::to_string(&metrics).unwrap()
            },
            Err(e) => emit_ingest_error(&e)
        }
    }));
    
    env.new_string(result.unwrap_or_else(|_| emit_ingest_error("Panic Thread Rust"))).unwrap().into_raw()
}

// ============================================================================
// MOTOR INTERNO DE INGESTA (Reutilizable)
// ============================================================================

fn process_mmap_file(file_path: &str, source_type: &str) -> Result<(i32, i64), String> {
    let start_time = SystemTime::now();
    
    let db_lock = JARVIS_DB.write(); 
    let mut onnx_lock = JARVIS_ONNX.write();
    if db_lock.is_none() { return Err("Motor Offline".into()); }
    let db = db_lock.as_ref().unwrap();

    let file = File::open(file_path).map_err(|e| e.to_string())?;
    
    // Si el archivo está vacío, retornamos 0 chunks procesados sin error.
    if file.metadata().map_err(|e| e.to_string())?.len() == 0 {
        return Ok((0, start_time.elapsed().unwrap().as_millis() as i64));
    }

    let mmap = unsafe { memmap2::Mmap::map(&file).map_err(|e| e.to_string())? };
    
    let mut count = 0;
    let mut prev_id: Option<String> = None;
    let cursor = Cursor::new(&mmap[..]);

    for line_res in cursor.lines() {
        if let Ok(line) = line_res {
            let clean = line.trim();
            
            if clean.len() < 15 || clean.contains("--- BASE DE DATOS") { continue; }
            
            let (txt, spk, start_t, end_t) = if let Ok(chunk) = serde_json::from_str::<TranscriptChunk>(clean) { 
                (chunk.text, chunk.speaker, chunk.start_time, chunk.end_time) 
            } else { 
                let mut ext_spk = "DOC".to_string();
                let mut ext_txt = clean.to_string();
                if let Some(close_bracket) = clean.find("] ") {
                    let remainder = &clean[close_bracket + 2..];
                    if let Some(colon) = remainder.find(": ") {
                        ext_spk = remainder[..colon].trim().to_string();
                        ext_txt = remainder[colon + 2..].trim().to_string();
                    }
                }
                (ext_txt, ext_spk, 0, 0)
            };

            if let Err(e) = insert_node_v8(db, onnx_lock.as_mut().unwrap(), JARVIS_TOKENIZER.read().as_ref().unwrap(), &txt, &spk, start_t, end_t, source_type, &mut prev_id) {
                error!("Error insertando nodo: {}", e);
                continue; 
            }
            count += 1;
        }
    }
    
    let elapsed = start_time.elapsed().unwrap().as_millis() as i64;
    Ok((count, elapsed))
}

fn insert_node_v8(db: &Db<SqliteStorage>, sess: &mut Session, tok: &Tokenizer, txt: &str, spk: &str, start_t: i64, end_t: i64, source_type: &str, prev: &mut Option<String>) -> Result<(), String> {
    
    let mut hasher = Sha256::new();
    hasher.update(format!("{}|{}|{}", txt, spk, start_t).as_bytes());
    let cid = format!("{:x}", hasher.finalize());
    
    let vec = vectorize(txt, tok, sess)?;
    
    let mut params = BTreeMap::new();
    params.insert("cid".into(), DataValue::Str(cid.clone().into()));
    params.insert("txt".into(), DataValue::Str(txt.into()));
    params.insert("ts".into(), DataValue::Num(Num::Int(start_t)));
    
    // 🛡️ INYECCIÓN DE PROCEDENCIA FORENSE
    let meta_json = json!({
        "speaker": spk, 
        "end_time": end_t,
        "source": source_type
    });
    params.insert("meta".into(), DataValue::Json(JsonData(meta_json)));
    
    let vec_data: Vec<DataValue> = vec.into_iter().map(|f| DataValue::Num(Num::Float(f as f64))).collect();
    params.insert("vec".into(), DataValue::List(vec_data));

    let q1 = "?[chunk_id, texto_contenido, timestamp, metadatos] <- [[$cid, $txt, $ts, $meta]]; :put fragmento_documento";
    db.run_script(q1, params.clone(), ScriptMutability::Mutable).map_err(|e| format!("D1: {}", e))?;

    let q2 = "?[chunk_id, vector] <- [[$cid, $vec]]; :put vec_index";
    db.run_script(q2, params.clone(), ScriptMutability::Mutable).map_err(|e| format!("D2: {}", e))?;

    if let Some(pid) = prev {
        params.insert("pid".into(), DataValue::Str(pid.clone().into()));
        let q3 = "?[source_id, target_id, peso_similitud, tipo_relacion] <- [[$pid, $cid, 1.0, \"next\"]]; :put chunk_edges";
        let _ = db.run_script(q3, params, ScriptMutability::Mutable);
    }
    
    *prev = Some(cid);
    Ok(())
}

fn vectorize(txt: &str, tok: &Tokenizer, sess: &mut Session) -> Result<Vec<f32>, String> {
    let enc = tok.encode(txt, true).map_err(|e| e.to_string())?;
    let ids: Vec<i64> = enc.get_ids().iter().map(|&x| x as i64).collect();
    let mask: Vec<i64> = enc.get_attention_mask().iter().map(|&x| x as i64).collect();
    
    let seq_len = ids.len();
    let shape = [1, seq_len];
    
    let t_ids = ort::value::Tensor::from_array((shape, ids)).unwrap();
    let t_mask = ort::value::Tensor::from_array((shape, mask.clone())).unwrap();

    let inputs = if sess.inputs().iter().any(|i| i.name() == "token_type_ids") {
        let t_type = ort::value::Tensor::from_array((shape, vec![0i64; seq_len])).unwrap();
        ort::inputs!["input_ids"=>t_ids, "attention_mask"=>t_mask, "token_type_ids"=>t_type]
    } else { 
        ort::inputs!["input_ids"=>t_ids, "attention_mask"=>t_mask] 
    };
    
    let out = sess.run(inputs).map_err(|e| e.to_string())?;
    let (_, data) = out[0].try_extract_tensor::<f32>().map_err(|e| e.to_string())?;
    
    let hidden_size = 768; 
    if data.len() < hidden_size { return Err("Tensor size mismatch".into()); }

    let actual_seq_len = data.len() / hidden_size;
    let mut sba_pool = vec![0.0f32; hidden_size];

    if actual_seq_len == 1 {
        sba_pool.copy_from_slice(&data[0..hidden_size]);
    } else {
        let mut valid_tokens = 0.0f32;
        for i in 0..actual_seq_len {
            let mask_val = if i < mask.len() { mask[i] as f32 } else { 1.0 };
            if mask_val > 0.0 {
                let mut token_vec = vec![0.0f32; hidden_size];
                token_vec.copy_from_slice(&data[i*hidden_size .. (i+1)*hidden_size]);
                
                let mut norm = 0.0f32;
                for j in 0..hidden_size { norm += token_vec[j] * token_vec[j]; }
                norm = norm.sqrt().max(1e-12);
                
                for j in 0..hidden_size {
                    sba_pool[j] += (token_vec[j] / norm) * mask_val;
                }
                valid_tokens += mask_val;
            }
        }
        for j in 0..hidden_size { sba_pool[j] /= valid_tokens.max(1e-9); }
    }

    let mut sum_sq = 0.0f32;
    for j in 0..hidden_size { sum_sq += sba_pool[j] * sba_pool[j]; }
    let final_norm = sum_sq.sqrt().max(1e-12);
    for j in 0..hidden_size { sba_pool[j] /= final_norm; }

    Ok(sba_pool)
}

#[no_mangle]
pub extern "system" fn Java_jhonatan_s_rag_1engine_NativeBridge_getSystemDiagnostics<'local>(env: JNIEnv<'local>, _class: JClass<'local>) -> jstring {
    let result = panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> String {
        let db_r = JARVIS_DB.read();
        let mut state = SystemState { onnx_loaded: JARVIS_ONNX.read().is_some(), db_connected: db_r.is_some(), tables_found: vec![], counts: BTreeMap::new(), error: None };
        if let Some(db) = db_r.as_ref() {
            for t in vec!["fragmento_documento", "vec_index", "chunk_edges"] {
                state.tables_found.push(t.to_string());
                if let Ok(res) = db.run_script(&format!("?[count(id)] := *{}[id, ..]", t), Default::default(), ScriptMutability::Immutable) {
                    if let Some(row) = res.rows.first() { 
                        if let Some(DataValue::Num(n)) = row.first() { 
                            state.counts.insert(t.to_string(), match n { Num::Int(i) => *i, Num::Float(f) => *f as i64 }); 
                        } 
                    }
                }
            }
        }
        serde_json::to_string(&state).unwrap_or("{}".into())
    }));
    env.new_string(result.unwrap_or("{}".into())).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_jhonatan_s_rag_1engine_NativeBridge_runRawDatalog<'local>(mut env: JNIEnv<'local>, _class: JClass<'local>, query_j: JString<'local>) -> jstring {
    let q_str = match env.get_string(&query_j) { Ok(s) => s.to_str().unwrap().to_owned(), Err(_) => return env.new_string("Error").unwrap().into_raw() };
    let db_r = JARVIS_DB.read();
    if let Some(db) = db_r.as_ref() { 
        match db.run_script(&q_str, Default::default(), ScriptMutability::Immutable) { 
            Ok(r) => env.new_string(format!("{:?}", r.rows)).unwrap().into_raw(), 
            Err(e) => env.new_string(format!("Error: {}", e)).unwrap().into_raw() 
        } 
    } else { 
        env.new_string("Offline").unwrap().into_raw() 
    }
}

#[no_mangle]
pub extern "system" fn Java_jhonatan_s_rag_1engine_NativeBridge_setSystemRule(mut env: JNIEnv, _class: JClass, key: JString, val: JString) -> jboolean {
    let k_java = match env.get_string(&key) { Ok(s) => s.to_str().unwrap().to_string(), Err(_) => return 0 };
    let v_java = match env.get_string(&val) { Ok(s) => s.to_str().unwrap().to_string(), Err(_) => return 0 };
    
    let guard = JARVIS_DB.read();
    if let Some(db) = guard.as_ref() {
        let mut params = BTreeMap::new();
        params.insert("k".into(), DataValue::Str(k_java.into()));
        params.insert("v".into(), DataValue::Str(v_java.into()));
        let q = "?[id, c] <- [[$k, $v]]; :put system_rules";
        if db.run_script(q, params, ScriptMutability::Mutable).is_ok() { return 1; }
    }
    0
}

fn emit_error(msg: &str) -> String {
    let resp = EngineStatus { status: "fatal".into(), error: Some(msg.into()), logs: vec![], errores: vec![] };
    serde_json::to_string(&resp).unwrap()
}

fn emit_rag_error(msg: &str) -> String {
    let resp = RagResponse { status: "fatal".into(), error: Some(msg.into()), confidence: "NULA".into(), results: vec![] };
    serde_json::to_string(&resp).unwrap()
}

fn emit_ingest_error(msg: &str) -> String {
    let resp = IngestionMetrics { status: "error".into(), chunks_processed: 0, execution_time_ms: 0, error: Some(msg.into()) };
    serde_json::to_string(&resp).unwrap()
}