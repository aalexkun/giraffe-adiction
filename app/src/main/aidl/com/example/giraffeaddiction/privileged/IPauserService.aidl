package com.example.giraffeaddiction.privileged;

import com.example.giraffeaddiction.privileged.IForegroundListener;

// The thin privileged worker Shizuku runs as the shell user. Every method either executes one
// privileged call or reports foreground changes; all the rules live on the app side.
interface IPauserService {
    // Transaction code reserved by the Shizuku server for tearing the service down.
    void destroy() = 16777114;

    void exit() = 1;
    int getVersion() = 2;

    // Each returns null on success or a short error message.
    String suspendPackage(String packageName) = 3;
    String unsuspendPackage(String packageName) = 4;
    String forceStopPackage(String packageName) = 5;

    boolean isPackageSuspended(String packageName) = 6;

    void setForegroundListener(IForegroundListener listener) = 7;

    // The package on top right now, or null; used to seed the engine after a (re)connect.
    String getForegroundPackage() = 8;

    // "observer" or "polling", for the status screen.
    String getObserverMode() = 9;

    // Puts a package on the device-idle temporary allowlist for a few seconds, so Android lets it
    // start a service from the background: WireGuard's VpnService when the tunnel is asked up.
    // Null on success, otherwise the shell output.
    String allowBackgroundStart(String packageName, int seconds) = 10;
}
