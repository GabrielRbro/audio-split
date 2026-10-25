package io.github.audiosplit.ipc;

interface IAudioSplitService {
    String diagnose() = 0;
    String applyRoutes(boolean testMode, String bluetoothDeviceKey) = 1;
    String getStatus() = 2;
    String restore() = 3;
    void attachClient(IBinder token, IBinder shizukuToken) = 4;
    // Shizuku UserService lifecycle transaction; AIDL adds FIRST_CALL_TRANSACTION (1).
    void destroy() = 16777114;
}
