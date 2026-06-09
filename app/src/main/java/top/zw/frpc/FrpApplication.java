package top.zw.frpc;

import android.app.Application;

public class FrpApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        // Global exception handler to prevent crashes
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                android.util.Log.e("FrpAndroid", "Uncaught exception in thread: " + thread.getName(), throwable);
                // Write to a file for debugging
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                throwable.printStackTrace(pw);
                android.util.Log.e("FrpAndroid", sw.toString());
            } catch (Exception ignored) {}
        });
    }
}
