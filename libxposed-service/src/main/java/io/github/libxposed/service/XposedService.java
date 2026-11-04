package io.github.libxposed.service;

import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("unused")
public final class XposedService {
    public static final long PROP_CAP_SYSTEM = IXposedService.PROP_CAP_SYSTEM;
    public static final long PROP_CAP_REMOTE = IXposedService.PROP_CAP_REMOTE;
    public static final long PROP_RT_API_PROTECTION = IXposedService.PROP_RT_API_PROTECTION;

    public static final class ServiceException extends RuntimeException {
        ServiceException(String message) {
            super(message);
        }

        ServiceException(RemoteException e) {
            super("Xposed service error", e);
        }
    }

    private static final Map<OnScopeEventListener, IXposedScopeCallback> scopeCallbacks = new ConcurrentHashMap<>();

    public interface OnScopeEventListener {
        default void onScopeRequestApproved(@NonNull List<String> approved) {
        }

        default void onScopeRequestFailed(@NonNull String message) {
        }

        private IXposedScopeCallback asInterface() {
            return scopeCallbacks.computeIfAbsent(this, (listener) -> new IXposedScopeCallback.Stub() {
                @Override
                public void onScopeRequestApproved(List<String> approved) {
                    listener.onScopeRequestApproved(approved);
                    scopeCallbacks.remove(listener);
                }

                @Override
                public void onScopeRequestFailed(String message) {
                    listener.onScopeRequestFailed(message);
                    scopeCallbacks.remove(listener);
                }
            });
        }
    }

    private final IXposedService mService;
    private final Map<String, RemotePreferences> mRemotePrefs = new HashMap<>();

    XposedService(IXposedService service) {
        mService = service;
    }

    IXposedService asInterface() {
        return mService;
    }

    public int getApiVersion() {
        try {
            return mService.getApiVersion();
        } catch (RemoteException e) {
            throw new ServiceException(e);
        }
    }

    @NonNull
    public String getFrameworkName() {
        try {
            return mService.getFrameworkName();
        } catch (RemoteException e) {
            throw new ServiceException(e);
        }
    }

    @NonNull
    public String getFrameworkVersion() {
        try {
            return mService.getFrameworkVersion();
        } catch (RemoteException e) {
            throw new ServiceException(e);
        }
    }

    public long getFrameworkVersionCode() {
        try {
            return mService.getFrameworkVersionCode();
        } catch (RemoteException e) {
            throw new ServiceException(e);
        }
    }

    public long getFrameworkProperties() {
        try {
            return mService.getFrameworkProperties();
        } catch (RemoteException e) {
            throw new ServiceException(e);
        }
    }

    @NonNull
    public List<String> getScope() {
        try {
            return mService.getScope();
        } catch (RemoteException e) {
            throw new ServiceException(e);
        }
    }

    public void requestScope(@NonNull List<String> packages, @NonNull OnScopeEventListener callback) {
        try {
            mService.requestScope(packages, callback.asInterface());
        } catch (RemoteException e) {
            throw new ServiceException(e);
        }
    }

    public void removeScope(@NonNull List<String> packages) {
        try {
            mService.removeScope(packages);
        } catch (RemoteException e) {
            throw new ServiceException(e);
        }
    }

    @NonNull
    public synchronized SharedPreferences getRemotePreferences(@NonNull String group) {
        return mRemotePrefs.computeIfAbsent(group, k -> {
            try {
                return RemotePreferences.newInstance(this, k);
            } catch (RemoteException e) {
                if (e.getCause() instanceof UnsupportedOperationException cause) {
                    throw cause;
                }
                throw new ServiceException(e);
            }
        });
    }

    public synchronized void deleteRemotePreferences(@NonNull String group) {
        try {
            var prefs = mRemotePrefs.get(group);
            if (prefs != null) prefs.onDelete();
            mService.deleteRemotePreferences(group);
        } catch (RemoteException e) {
            if (e.getCause() instanceof UnsupportedOperationException cause) {
                throw cause;
            }
            throw new ServiceException(e);
        }
    }

    @NonNull
    public String[] listRemoteFiles() {
        try {
            return mService.listRemoteFiles();
        } catch (RemoteException e) {
            if (e.getCause() instanceof UnsupportedOperationException cause) {
                throw cause;
            }
            throw new ServiceException(e);
        }
    }

    @NonNull
    public ParcelFileDescriptor openRemoteFile(@NonNull String name) {
        try {
            var pfd = mService.openRemoteFile(name);
            if (pfd == null) throw new ServiceException("Framework returns null");
            return pfd;
        } catch (RemoteException e) {
            if (e.getCause() instanceof UnsupportedOperationException cause) {
                throw cause;
            }
            throw new ServiceException(e);
        }
    }

    public boolean deleteRemoteFile(@NonNull String name) {
        try {
            return mService.deleteRemoteFile(name);
        } catch (RemoteException e) {
            if (e.getCause() instanceof UnsupportedOperationException cause) {
                throw cause;
            }
            throw new ServiceException(e);
        }
    }
}
