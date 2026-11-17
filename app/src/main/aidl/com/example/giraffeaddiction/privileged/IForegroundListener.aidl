package com.example.giraffeaddiction.privileged;

// Implemented in the app process; called by the shell-side worker.
oneway interface IForegroundListener {
    void onForegroundChanged(String packageName, int pid, int uid, boolean foreground);
    void onObserverFailed(String reason);
}
