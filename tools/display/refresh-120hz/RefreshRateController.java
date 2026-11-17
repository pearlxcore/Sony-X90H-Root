package com.pearlxcore.rootdriver;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses the app's existing authorized ADB/root path; never records a desired
 * rate as if it were the actual rate, and never reapplies a patch on boot. */
public final class RefreshRateController {
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    public interface Result { void done(String message); }

    public static boolean busy() { return BUSY.get(); }

    public static String actual(Context activity) {
        DisplayModeReader.Mode mode = DisplayModeReader.current();
        return mode == null ? "Active Android mode: checking..."
                : String.format(Locale.US, "Active Android mode: %.2f Hz", mode.hz);
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    private static File extract(Context activity, String asset) throws Exception {
        File out = new File(activity.getFilesDir(), "refresh-"+java.util.UUID.randomUUID().toString()+"-"+asset);
        try (InputStream in = activity.getAssets().open(asset);
             FileOutputStream stream = new FileOutputStream(out)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) stream.write(buffer, 0, n);
        }
        return out;
    }

    public static void select(final Context activity, final boolean enable120, final Result result) {
        if (!BUSY.compareAndSet(false, true)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                AdbBridge bridge = new AdbBridge(activity.getFilesDir());
                String message;
                try {
                    File script = extract(activity, "refresh-switch.sh");
                    File helper = extract(activity, "x90-refresh");
                    bridge.connect("127.0.0.1", 5555, 30000);
                    String root = bridge.runShell("ps -A | grep '[p]rod_exploit_v21'", 8000);
                    if (root == null || root.trim().length() == 0) {
                        throw new Exception("Root the TV first using the app's root controls.");
                    }
                    String output = RootSession.runRoot(bridge,
                            "sh " + quote(script.getAbsolutePath()) + " "
                            + (enable120 ? "on" : "off") + " " + quote(helper.getAbsolutePath()), 45000);
                    String marker = enable120 ? "REFRESH_OK_120" : "REFRESH_OK_60";
                    if (!output.contains(marker)) {
                        int error = output.indexOf("ERROR:");
                        String detail = error >= 0 ? output.substring(error).split("\n")[0]
                                : "Could not verify the change. Check the active mode and try 60 Hz.";
                        throw new Exception(detail);
                    }
                    message = enable120 ? "120 Hz enabled for this boot. Select 60 Hz to restore."
                            : "60 Hz restored.";
                } catch (Exception e) {
                    message = e.getMessage() == null ? "Refresh-rate change failed." : e.getMessage();
                } finally { bridge.close(); BUSY.set(false); }
                final String text = message;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        result.done(text);
                    }
                });
            }
        }, "X90RefreshRate").start();
    }
}
