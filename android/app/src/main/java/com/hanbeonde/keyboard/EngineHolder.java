package com.hanbeonde.keyboard;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** assets/words.json 을 한 번만 읽어서 엔진을 만들어 둔다 (백그라운드 스레드) */
final class EngineHolder {
    private static volatile Engine engine;
    private static boolean loading;

    private EngineHolder() {}

    static Engine get(Context c) {
        if (engine == null) load(c);
        return engine;
    }

    static synchronized void load(Context c) {
        if (engine != null || loading) return;
        loading = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try (InputStream in = app.getAssets().open("words.json")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                engine = new Engine(out.toString("UTF-8"));
            } catch (Exception ignored) {
            } finally {
                synchronized (EngineHolder.class) {
                    loading = false;
                }
            }
        }, "hanbeonde-engine").start();
    }
}
