// app/src/main/aidl/jhonatan/s/jarvis/ipc/IVoiceEngineService.aidl
package jhonatan.s.jarvis.ipc;
import jhonatan.s.jarvis.ipc.IVoiceEngineCallback;

interface IVoiceEngineService {
    void registerCallback(IVoiceEngineCallback callback);
    void unregisterCallback(IVoiceEngineCallback callback);
    void startContinuousCapture();
    void stopContinuousCapture();
    void enrollSpeakerProfile(String name);
    void verifySpeaker(); // 🚀 Ahora el esclavo sabrá que son 8s
    void setParallelMode(boolean enabled);
    void loadModel(String modelName);
    void requestProfiles(); // 🚀 Para refrescar la lista de perfiles
    void deleteProfile(String name); // 🚀 Para gestionar desde el Master
}