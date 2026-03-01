// app/src/main/aidl/jhonatan/s/jarvis/ipc/IVoiceEngineCallback.aidl
package jhonatan.s.jarvis.ipc;

interface IVoiceEngineCallback {
    // Flujo en tiempo real (Inestable, solo para la UI visual)
    void onTranscription(String speakerName, String text, long timestampMs);

    // 🚀 NUEVO: Flujo Documental (Inmutable, frase terminada, va al JSONL)
    void onTranscriptionSealed(String speakerName, String text, long startTimeMs, long endTimeMs);

    void onEngineStateChanged(String state, String message);
    void onBiometricResult(String profileName, float shiftPercent, boolean success);
    void onProgressUpdate(int currentSecond, int totalSeconds);
    void onProfilesUpdated(String profilesJson);
}