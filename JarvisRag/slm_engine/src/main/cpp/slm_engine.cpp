#include <jni.h>
#include <android/log.h>
#include "llama.h"
#include <string>
#include <vector>
#include <algorithm>
#include <chrono>
#include <random>
#include <cmath>
#include <thread>
#include <sys/resource.h>
#include <unistd.h>
#include <sched.h>
#include <fstream>
#include <iostream>
#include <sstream>

// ============================================================================
// 🛡️ TELEMETRÍA NATIVA
// ============================================================================
#define TAG "JARVIS_SLM_ENGINE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static void jarvis_llama_log_callback(ggml_log_level level, const char * text, void * user_data) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        LOGE("LLAMA_ERR: %s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        LOGW("LLAMA_WARN: %s", text);
    }
}

// ============================================================================
// 📐 MOTOR DE CÁLCULO DE HARDWARE (L2 STRICT CONTAINMENT)
// ============================================================================
class HardwareIntelligence {
public:
    std::vector<int> performance_core_ids;
    int calculated_ubatch = 128; // El estándar de oro por defecto
    long l2_cache_size_bytes = 0;

    HardwareIntelligence() {
        scan_topology();
        scan_cache_geometry();
    }

    void compute_perfect_fit_batch(llama_model* model, int active_threads) {
        if (model == nullptr) return;

        // 1. Geometría del Modelo
        int n_embd = llama_n_embd(model);
        long bytes_per_token = n_embd * 4; // f32 size

        // 2. Detección de Caché L2 (Si falla, usamos 512KB que es el estándar ARM Cortex moderno)
        long effective_l2 = (l2_cache_size_bytes > 0) ? l2_cache_size_bytes : (512 * 1024);

        // 3. REGLA DE ORO: MARGEN DE SEGURIDAD DEL 50%     
        // Solo usamos la mitad de la L2. La otra mitad es para instrucciones, OS y pesos.
        // Esto evita el "Thrashing" que vimos en la prueba anterior.
        long usable_l2 = effective_l2 * 0.50;

        // 4. Cálculo de tokens por núcleo
        long tokens_per_core = usable_l2 / bytes_per_token;

        // 5. Cálculo del Batch Total
        long raw_batch = tokens_per_core * active_threads;

        // 6. Alineación a 64 bytes (Cache Line Size de ARM)
        int aligned_batch = (raw_batch / 64) * 64;

        // 7. LÍMITES EMPÍRICOS DE SILICIO MÓVIL
        // - Mínimo 64: Menos de esto es ineficiente por overhead de llamada.
        // - Máximo 256: Más de esto suele calentar la ALU y causar throttling, aunque quepa en caché.
        if (aligned_batch < 64) aligned_batch = 64;
        if (aligned_batch > 256) aligned_batch = 256;

        calculated_ubatch = aligned_batch;

        LOGI("📐 CÁLCULO AGNÓSTICO (L2 STRICT):");
        LOGI("   • Dimensión Embedding: %d", n_embd);
        LOGI("   • L2 Detectada: %ld KB", effective_l2 / 1024);
        LOGI("   • Margen de Seguridad: 50%%");
        LOGI("   • RESULTADO: uBatch = %d", calculated_ubatch);
    }

private:
    void scan_topology() {
        int num_cores = sysconf(_SC_NPROCESSORS_CONF);
        std::vector<std::pair<long, int>> core_freqs;

        for (int i = 0; i < num_cores; i++) {
            std::string path = "/sys/devices/system/cpu/cpu" + std::to_string(i) + "/cpufreq/cpuinfo_max_freq";
            std::ifstream file(path);
            long freq = 0;
            if (file.is_open()) {
                file >> freq;
                file.close();
            }
            core_freqs.push_back({freq, i});
        }
        std::sort(core_freqs.rbegin(), core_freqs.rend());

        int cores_to_pick = std::min(4, num_cores);
        for (int i = 0; i < cores_to_pick; i++) {
            if (core_freqs[i].first > 0) performance_core_ids.push_back(core_freqs[i].second);
        }

        if (performance_core_ids.empty()) {
            for (int i = std::max(0, num_cores - 4); i < num_cores; i++) performance_core_ids.push_back(i);
        }
    }

    void scan_cache_geometry() {
        int target_cpu = performance_core_ids.empty() ? 0 : performance_core_ids[0];
        // Intentamos leer L2 (index 2)
        l2_cache_size_bytes = read_cache_bytes(target_cpu, 2);

        // Si no podemos leer L2, NO intentamos adivinar con L3.
        // Es mejor fallar a un default seguro (512KB) que sobreestimar con L3.
        if (l2_cache_size_bytes == 0) {
            LOGW("⚠️ No se pudo leer L2. Usando fallback estándar (512KB).");
        }
    }

    long read_cache_bytes(int cpu, int index) {
        std::string path = "/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cache/index" + std::to_string(index) + "/size";
        std::ifstream file(path);
        if (!file.is_open()) return 0;

        std::string size_str;
        file >> size_str;
        file.close();

        long multiplier = 1;
        if (!size_str.empty()) {
            char unit = size_str.back();
            if (unit == 'K') { multiplier = 1024; size_str.pop_back(); }
            else if (unit == 'M') { multiplier = 1024 * 1024; size_str.pop_back(); }
        }

        try {
            return std::stol(size_str) * multiplier;
        } catch (...) {
            return 0;
        }
    }
};

static HardwareIntelligence* hw_intel = nullptr;

struct JarvisEngineContext {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    int32_t n_past = 0;
    std::vector<llama_token> history_ring;
};

void lock_to_detected_cores() {
    if (hw_intel == nullptr || hw_intel->performance_core_ids.empty()) return;
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    for (int id : hw_intel->performance_core_ids) CPU_SET(id, &cpuset);
    sched_setaffinity(0, sizeof(cpu_set_t), &cpuset);
}

llama_token jarvis_sample_token(float* logits, int n_vocab, float temp = 0.7f, float top_p = 0.9f, int top_k = 40) {
    std::vector<std::pair<float, llama_token>> candidates;
    candidates.reserve(n_vocab);
    for (llama_token i = 0; i < n_vocab; ++i) candidates.emplace_back(logits[i], i);

    top_k = std::min(top_k, n_vocab);
    std::partial_sort(candidates.begin(), candidates.begin() + top_k, candidates.end(),
                      [](const std::pair<float, llama_token>& a, const std::pair<float, llama_token>& b) {
                          return a.first > b.first;
                      });

    std::vector<float> probs;
    probs.reserve(top_k);
    float max_logit = candidates[0].first;
    float sum_probs = 0.0f;
    for (int i = 0; i < top_k; ++i) {
        float p = std::exp((candidates[i].first - max_logit) / temp);
        probs.push_back(p);
        sum_probs += p;
    }

    float cumulative_prob = 0.0f;
    int last_idx = 0;
    for (int i = 0; i < top_k; ++i) {
        probs[i] /= sum_probs;
        cumulative_prob += probs[i];
        last_idx = i;
        if (cumulative_prob >= top_p) break;
    }

    static std::mt19937 rng(std::random_device{}());
    std::discrete_distribution<> dist(probs.begin(), probs.begin() + last_idx + 1);
    return candidates[dist(rng)].second;
}

extern "C" JNIEXPORT jlong JNICALL
Java_jhonatan_s_slm_1engine_jni_SlmNativeBridge_initSlmEngine(
        JNIEnv *env, jobject thiz, jstring modelPath) {

    if (hw_intel == nullptr) hw_intel = new HardwareIntelligence();
    lock_to_detected_cores();

    llama_log_set(jarvis_llama_log_callback, nullptr);
    LOGI(">> INICIANDO PROTOCOLO AGNÓSTICO (L2 CONTAINMENT) <<");

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.use_mmap = true;
    model_params.use_mlock = false;

    llama_model * model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (model == nullptr) return 0L;

    // CÁLCULO DINÁMICO
    int threads_count = std::max(1, (int)hw_intel->performance_core_ids.size());
    hw_intel->compute_perfect_fit_batch(model, threads_count);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 1661;
    ctx_params.offload_kqv = true;
    ctx_params.flash_attn = true;

    ctx_params.n_batch = 616;
    // APLICAMOS EL RESULTADO MATEMÁTICO ESTRICTO
    ctx_params.n_ubatch = hw_intel->calculated_ubatch;

    ctx_params.type_k = GGML_TYPE_Q8_0;
    ctx_params.type_v = GGML_TYPE_Q8_0;

    ctx_params.n_threads = threads_count;
    ctx_params.n_threads_batch = threads_count;

    llama_context * ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        llama_model_free(model);
        return 0L;
    }

    JarvisEngineContext * engine = new JarvisEngineContext();
    engine->model = model;
    engine->ctx = ctx;
    engine->n_past = 0;

    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_jhonatan_s_slm_1engine_jni_SlmNativeBridge_resetKvCache(
        JNIEnv *env, jobject thiz, jlong enginePtr) {
    if (enginePtr != 0L) {
        JarvisEngineContext * engine = reinterpret_cast<JarvisEngineContext *>(enginePtr);
        llama_kv_cache_clear(engine->ctx);
        engine->n_past = 0;
        engine->history_ring.clear();
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_jhonatan_s_slm_1engine_jni_SlmNativeBridge_generateTokensZeroCopy(
        JNIEnv *env, jobject thiz, jlong enginePtr, jobject promptBuffer, jint promptLength, jobject callback) {

    if (enginePtr == 0L) return 0;
    JarvisEngineContext * engine = reinterpret_cast<JarvisEngineContext *>(enginePtr);
    if (engine->model == nullptr || engine->ctx == nullptr) return 0;

    setpriority(PRIO_PROCESS, 0, -15);
    lock_to_detected_cores();

    llama_kv_cache_clear(engine->ctx);
    engine->n_past = 0;
    engine->history_ring.clear();

    void* bufferAddress = env->GetDirectBufferAddress(promptBuffer);
    if (bufferAddress == nullptr) return 0;

    std::string prompt(static_cast<const char*>(bufferAddress), promptLength);
    const struct llama_vocab * vocab = llama_model_get_vocab(engine->model);

    std::vector<llama_token> tokens_list(promptLength * 2);
    int n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.length(), tokens_list.data(), tokens_list.size(), true, true);
    if (n_tokens < 0) return 0;
    tokens_list.resize(n_tokens);

    if (n_tokens > llama_n_ctx(engine->ctx)) return -1;

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenGeneratedMethod = env->GetMethodID(callbackClass, "onTokenGenerated", "([B)V");
    if (onTokenGeneratedMethod == nullptr) return 0;

    uint32_t physical_batch = llama_n_ubatch(engine->ctx);
    llama_batch batch = llama_batch_init(physical_batch, 0, 1);

    int prompt_tokens_processed = 0;
    auto t_start_ingest = std::chrono::high_resolution_clock::now();

    while (prompt_tokens_processed < n_tokens) {
        int chunk_size = std::min((int)physical_batch, n_tokens - prompt_tokens_processed);
        batch.n_tokens = 0;

        for (int i = 0; i < chunk_size; i++) {
            int token_idx = prompt_tokens_processed + i;
            batch.token[batch.n_tokens] = tokens_list[token_idx];
            batch.pos[batch.n_tokens]   = engine->n_past + token_idx;
            batch.n_seq_id[batch.n_tokens] = 1;
            batch.seq_id[batch.n_tokens][0] = 0;
            batch.logits[batch.n_tokens] = (token_idx == n_tokens - 1) ? 1 : 0;
            batch.n_tokens++;
        }

        if (llama_decode(engine->ctx, batch) != 0) {
            llama_batch_free(batch);
            return 0;
        }
        prompt_tokens_processed += chunk_size;
    }

    engine->n_past += n_tokens;

    auto t_end_ingest = std::chrono::high_resolution_clock::now();
    long long ms_ingest_total = std::chrono::duration_cast<std::chrono::milliseconds>(t_end_ingest - t_start_ingest).count();
    float tokens_per_second = ms_ingest_total > 0 ? ((float)n_tokens / (ms_ingest_total / 1000.0f)) : 0.0f;

    LOGI("🔥 [AGNOSTIC] INGESTA: %d tokens en %lld ms (%.2f t/s)", n_tokens, ms_ingest_total, tokens_per_second);

    // ========================================================================
    // DECODE
    // ========================================================================
    int max_tokens = 2048;
    int tokens_generados = 0;
    const int HISTORY_SIZE = 64;
    const float REPEAT_PENALTY = 1.15f;

    std::string token_buffer = "";
    int flush_counter = 0;
    const int FLUSH_THRESHOLD = 4;

    auto t_start_decode = std::chrono::high_resolution_clock::now();

    while (tokens_generados <= max_tokens) {

        if (engine->n_past >= llama_n_ctx(engine->ctx)) break;

        auto * logits = llama_get_logits_ith(engine->ctx, batch.n_tokens - 1);
        if (logits == nullptr) break;

        for (llama_token past_token : engine->history_ring) {
            if (logits[past_token] > 0) logits[past_token] /= REPEAT_PENALTY;
            else logits[past_token] *= REPEAT_PENALTY;
        }

        int n_vocab = llama_vocab_n_tokens(vocab);
        llama_token new_token_id = jarvis_sample_token(logits, n_vocab, 0.7f, 0.9f, 40);
        // 🛡️ RIGOR: Verificación de fin de secuencia
        if (llama_vocab_is_eog(vocab, new_token_id) || new_token_id == 151643) {
            LOGI("🛑 Token de parada detectado. Finalizando secuencia.");
            break;
        }
        bool is_eog = llama_vocab_is_eog(vocab, new_token_id);

        engine->history_ring.push_back(new_token_id);
        if (engine->history_ring.size() > HISTORY_SIZE) engine->history_ring.erase(engine->history_ring.begin());

        char token_buf[128];
        int n_chars = llama_token_to_piece(vocab, new_token_id, token_buf, sizeof(token_buf), 0, true);

        if (n_chars > 0) {
            token_buffer.append(token_buf, n_chars);
            flush_counter++;
        }

        if (flush_counter >= FLUSH_THRESHOLD || is_eog) {
            if (!token_buffer.empty()) {
                jbyteArray jByteArray = env->NewByteArray(token_buffer.length());
                if (jByteArray != nullptr) {
                    env->SetByteArrayRegion(jByteArray, 0, token_buffer.length(), reinterpret_cast<const jbyte*>(token_buffer.c_str()));
                    env->CallVoidMethod(callback, onTokenGeneratedMethod, jByteArray);
                    env->DeleteLocalRef(jByteArray);
                }
                token_buffer.clear();
                flush_counter = 0;
            }
        }

        if (is_eog) break;

        batch.n_tokens = 0;
        batch.token[0] = new_token_id;
        batch.pos[0] = engine->n_past;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        batch.n_tokens = 1;

        if (llama_decode(engine->ctx, batch) != 0) break;

        engine->n_past += 1;
        tokens_generados += 1;
    }

    auto t_end_decode = std::chrono::high_resolution_clock::now();
    long long ms_decode_total = std::chrono::duration_cast<std::chrono::milliseconds>(t_end_decode - t_start_decode).count();
    float decode_tps = ms_decode_total > 0 ? ((float)tokens_generados / (ms_decode_total / 1000.0f)) : 0.0f;

    LOGI("⚡ [AGNOSTIC] DECODE COMPLETADO: %d tokens en %lld ms (%.2f t/s)", tokens_generados, ms_decode_total, decode_tps);

    setpriority(PRIO_PROCESS, 0, 0);
    llama_batch_free(batch);
    return 1;
}

extern "C" JNIEXPORT void JNICALL
Java_jhonatan_s_slm_1engine_jni_SlmNativeBridge_releaseSlmEngine(
        JNIEnv *env, jobject thiz, jlong enginePtr) {
    if (enginePtr != 0L) {
        JarvisEngineContext * engine = reinterpret_cast<JarvisEngineContext *>(enginePtr);
        if (engine->ctx != nullptr) llama_free(engine->ctx);
        if (engine->model != nullptr) llama_model_free(engine->model);
        delete engine;
        if (hw_intel != nullptr) { delete hw_intel; hw_intel = nullptr; }
        LOGI("Motor SLM destruido. RAM liberada.");
    }
}