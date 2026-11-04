package io.github.libxposed.service;
import io.github.libxposed.service.IXposedScopeCallback;

interface IXposedService {
    const String AUTHORITY_SUFFIX = ".XposedService";
    const String SEND_BINDER = "SendBinder";

    const int LIB_API = 101;

    const long PROP_CAP_SYSTEM = 1L;
    const long PROP_CAP_REMOTE = 1L << 1;
    const long PROP_RT_API_PROTECTION = 1L << 2;

    int getApiVersion() = 1;
    String getFrameworkName() = 2;
    String getFrameworkVersion() = 3;
    long getFrameworkVersionCode() = 4;
    long getFrameworkProperties() = 5;

    List<String> getScope() = 10;
    oneway void requestScope(in List<String> packages, IXposedScopeCallback callback) = 11;
    void removeScope(in List<String> packages) = 12;

    Bundle requestRemotePreferences(String group) = 20;
    void updateRemotePreferences(String group, in Bundle diff) = 21;
    void deleteRemotePreferences(String group) = 22;

    String[] listRemoteFiles() = 30;
    ParcelFileDescriptor openRemoteFile(String name) = 31;
    boolean deleteRemoteFile(String name) = 32;
}
