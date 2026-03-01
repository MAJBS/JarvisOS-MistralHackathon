# 🧠 JARVIS OS: Edge-Cloud Hybrid Cognitive Node

![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Rust](https://img.shields.io/badge/Rust-000000?style=for-the-badge&logo=rust&logoColor=white)
![C++](https://img.shields.io/badge/C++-00599C?style=for-the-badge&logo=c%2B%2B&logoColor=white)
![Mistral AI](https://img.shields.io/badge/Mistral_AI-F54E42?style=for-the-badge&logo=mistral&logoColor=white)
![ElevenLabs](https://img.shields.io/badge/ElevenLabs-000000?style=for-the-badge&logo=elevenlabs&logoColor=white)

**Jarvis OS** is a military-grade, hybrid AI assistant designed for Android. It solves the critical flaws of modern AI assistants (amnesia, high latency, and privacy leaks) by implementing a **Zero-Copy, Off-Heap Local GraphRAG** written in Rust/C++, seamlessly routed to **Mistral AI** for deterministic reasoning, and synthesized by **ElevenLabs**.

## 🏗️ System Architecture (ASCII Topology)

The architecture is strictly divided into a Native Hardware Layer (Edge) and a Cognitive Routing Layer (Cloud), bridged by a Zero-Copy JNI interface to prevent JVM Garbage Collection (GC) pauses and Low Memory Killer (LMK) terminations.

```text
[PHYSICAL HARDWARE]
       │
       ▼
+-----------------------------------------------------------------------+
| 1. AUDITORY CORTEX (KOTLIN / C++)                                     |
|  • AudioCaptureManager (Single Source of Truth)                       |
|  • AudioRingBuffer & ByteBufferPool (Zero-Allocation Memory)          |
+-----------------------------------------------------------------------+
       │ (Direct Memory Access / No System.arraycopy)
       ▼
+-----------------------------------------------------------------------+
| 2. NATIVE INFERENCE & BIOMETRICS (RUST / C++ / ONNX)                  |
|  • Sherpa-ONNX: Offline Transcription (Whisper / SenseVoice)          |
|  • ECAPA-TDNN: Real-time Biometric Speaker Diarization (768-dim)      |
|  • SHA-256: Cryptographic Provenance Hashing for each chunk           |
+-----------------------------------------------------------------------+
       │ (Immutable JSONL Sealed Memory)
       ▼
+-----------------------------------------------------------------------+
| 3. EPISODIC MEMORY GRAPH (RUST / COZODB)                              |
|  • Datalog Relational Engine (Multi-hop context retrieval)            |
|  • HNSW Vector Index (Cosine Similarity)                              |
|  • FTS (Full Text Search) Fallback                                    |
+-----------------------------------------------------------------------+
       │ (JNI Zero-Copy Bridge)
       ▼
+-----------------------------------------------------------------------+
| 4. COGNITIVE ORCHESTRATOR (KOTLIN HEADLESS SERVICE)                   |
|  • CRAG (Corrective RAG): Mathematical hallucination firewall.        |
|  • GemmaPromptOrchestrator: XML-like strict context injection.        |
+-----------------------------------------------------------------------+
       │ (SSE Stream / HTTP)
       ▼
+-----------------------------------------------------------------------+
| 5. CLOUD REASONING & SYNTHESIS (EXTERNAL APIs)                        |
|  • Mistral API (ministral-8b-latest): Low-latency deterministic logic |
|  • ElevenLabs API: Vocal synthesis with MD5 Audio Caching             |
+-----------------------------------------------------------------------+

#⚙️ Core Engineering & Architectural Modifications

This project is not a standard API wrapper. It involves deep architectural modifications to the Android OS execution model:
1. Zero-Allocation Acoustic Pipeline

To allow continuous 24/7 listening without triggering Android's Garbage Collector (which causes audio stuttering), the system uses a pre-allocated AudioRingBuffer and a ByteBufferPool. Audio bytes are written in a circular loop and passed to the C++ layer via GetDirectBufferAddress. RAM usage remains static after boot.
2. Biometric Diarization & Mean Pooling

The system doesn't just transcribe; it knows who is speaking. Using an ECAPA-TDNN model, it extracts a 768-dimensional voice print. The SpeakerProfileRepository applies mathematical Mean Pooling (L2 Normalization) to dynamically update the user's biometric vector over time, calculating the Euclidean shift to detect acoustic anomalies.
3. Deterministic GraphRAG (CozoDB + Datalog)

Memory is not stored in a flat vector database. We embedded CozoDB (a Datalog relational graph database) directly into the Rust core.
When a query is made:

    The query is vectorized via ONNX.

    An HNSW index finds the closest semantic node.

    A Datalog script traverses the chunk_edges table to retrieve the topological adjacent context (what was said immediately before and after the matched node).

4. CRAG (Corrective RAG) Firewall

The Rust core evaluates the cosine distance of the retrieved nodes. If the distance exceeds 0.85, the system assigns a NULA (Null) confidence level. The Kotlin orchestrator intercepts this and forces the Mistral API to admit a lack of local records, mathematically guaranteeing zero hallucinations regarding local data.
5. Mistral API Integration (ministral-8b-latest)

For the reasoning phase, the structured context is routed to Mistral's official API. We specifically target ministral-8b-latest with a temperature of 0.3. This ensures ultra-low latency (crucial for voice assistants) and highly deterministic, technical responses based strictly on the injected GraphRAG context.
6. ElevenLabs Smart Caching

To minimize latency and API costs, the ElevenLabsClient hashes the Mistral response (MD5). If the exact phrase was synthesized before, it bypasses the network call and plays the audio directly from the local cache via MediaPlayer.

#🛠️ Build Instructions (Native Toolchain)

Building this project requires a strict native toolchain setup due to the C++/Rust interoperability.

Prerequisites:

    Android NDK 26.1.10909125

    Rust Toolchain (aarch64-linux-android)

    CMake 3.22.1

Compiler Flags (Hardware Acceleration):
The slm_engine and rag_engine modules are strictly locked to 64-bit (arm64-v8a) and utilize specific CXX flags to leverage ARM NEON and DotProd instructions:

 Cmake

-DCMAKE_C_FLAGS="-march=armv8.2-a+dotprod -O3 -flto=thin -ffast-math -fno-finite-math-only"


JNI Packaging Rigor:
To prevent memory duplication, the build.gradle.kts enforces useLegacyPackaging = true. This prevents .so libraries from being compressed inside the APK, allowing the OS to mmap them directly from storage.

#🛡️ Thread Safety & Concurrency

The JarvisRagHeadlessService operates on a dedicated single-thread dispatcher (Executors.newSingleThreadExecutor().asCoroutineDispatcher()). Since the underlying ONNX runtime already parallelizes across all available CPU/NPU cores, this architectural decision prevents thread starvation and thermal throttling on mobile devices.

Built with military rigor for the Mistral Worldwide Hackathon 2025.

***
