package com.example.soul_knight_save_editor.unlock;

import android.os.ParcelFileDescriptor;

// All paths and arguments are validated again in the privileged process.
// Save bytes travel through reliable pipes, never through Binder parcels.
interface IShizukuSaveService {
    int rootUid() = 0;
    String instanceId() = 1;
    String stop(String packageName, int user) = 2;
    ParcelFileDescriptor read(String path, boolean backupOnly) = 3;
    String replace(String metadata, in ParcelFileDescriptor source, long length, String sha256) = 4;
    String exists(String directory) = 5;
    String list(String root, int sourceId) = 6;
    String packages(int user) = 7;
    String probePaths(String packageName, int user) = 8;
    String packageVersion(String packageName) = 9;
    String backupPaths(String packageName, int user) = 10;
    String assertIdle() = 11;
    String diagnostics() = 12;
    void destroy() = 16777114;
}
