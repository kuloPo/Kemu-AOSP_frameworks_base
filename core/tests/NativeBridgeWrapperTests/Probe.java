package com.android.nativebridge.probe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class Probe extends Activity {
    static native int answer();

    private boolean mBound;
    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Parcel request = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                if (!service.transact(IBinder.FIRST_CALL_TRANSACTION, request, reply, 0)) {
                    throw new AssertionError("Isolated probe transaction failed");
                }
                reply.readException();
                String result = reply.readString();
                try (FileOutputStream out =
                        new FileOutputStream(new File(getFilesDir(), "isolated-result"))) {
                    out.write(result.getBytes(StandardCharsets.UTF_8));
                }
                Log.i("NB_PROBE", result);
            } catch (Exception error) {
                throw new AssertionError(error);
            } finally {
                request.recycle();
                reply.recycle();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            System.loadLibrary("nbprobe");
            int result = answer();
            if (result != 42) {
                throw new AssertionError("Unexpected JNI result: " + result);
            }
            String message = "PASS package=" + getPackageName()
                    + " wrapped=" + System.getenv("NB_WRAPPED")
                    + " arch=" + System.getProperty("os.arch")
                    + " data=" + getDataDir() + " cache=" + getCodeCacheDir()
                    + " answer=" + result;
            try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "result"))) {
                out.write(message.getBytes(StandardCharsets.UTF_8));
            }
            Log.i("NB_PROBE", message);
            if (getIntent().getBooleanExtra("isolated", false)) {
                mBound = bindService(new Intent(this, IsolatedProbe.class), mConnection,
                        Context.BIND_AUTO_CREATE);
                if (!mBound) {
                    throw new AssertionError("Could not bind isolated probe");
                }
            }
        } catch (Throwable error) {
            Log.e("NB_PROBE", "FAIL", error);
            throw new AssertionError(error);
        }
    }

    @Override
    public void onDestroy() {
        if (mBound) {
            unbindService(mConnection);
        }
        super.onDestroy();
    }
}
