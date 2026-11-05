package com.android.nativebridge.probe;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import java.io.File;

public class IsolatedProbe extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        System.loadLibrary("nbprobe");
        int answer = Probe.answer();
        if (answer != 42) {
            throw new AssertionError("Unexpected isolated JNI result: " + answer);
        }
        File data = new File(getApplicationInfo().dataDir);
        String result = "PASS pid=" + Process.myPid() + " uid=" + Process.myUid()
                + " wrapped=" + System.getenv("NB_WRAPPED")
                + " arch=" + System.getProperty("os.arch")
                + " data_access=" + data.canRead()
                + " cache_access=" + new File(data, "code_cache").canWrite()
                + " answer=" + answer;
        return new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel request, Parcel reply, int flags)
                    throws RemoteException {
                if (code == IBinder.FIRST_CALL_TRANSACTION) {
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                return super.onTransact(code, request, reply, flags);
            }
        };
    }
}
