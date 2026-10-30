package app.framewise;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Sends a (redacted) test report to the Framewise report relay (a Cloudflare Worker). When the phone is
 * offline, or the relay is busy, the report is queued in app storage and sent on the next launch.
 */
final class ReportUploader {
    interface Callback { void done(String result, String message); }

    static final int MAX_BYTES = 64 * 1024;
    static final int MAX_QUEUED = 5;
    enum Outcome { SENT, QUEUE, REJECTED }

    private final File queueDir;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    ReportUploader(Context context) { queueDir = new File(context.getApplicationContext().getFilesDir(), "report-queue"); }

    /** What to do with an HTTP status (or -1 when the relay could not be reached). */
    static Outcome outcomeFor(int status) {
        if (status >= 200 && status < 300) return Outcome.SENT;
        if (status < 0 || status == 408 || status == 429 || status >= 500) return Outcome.QUEUE;
        return Outcome.REJECTED;
    }

    /** Redacts secrets again on the native side and checks the relay's size limit; null when it can't be sent. */
    static byte[] prepare(String reportJson) {
        if (reportJson == null || !reportJson.contains("framewise-test-report/1")) return null;
        byte[] body = DiagLog.redact(reportJson).getBytes(StandardCharsets.UTF_8);
        return body.length > MAX_BYTES ? null : body;
    }

    void send(String reportJson, Callback callback) {
        byte[] body = prepare(reportJson);
        if (body == null) { callback.done("error", "This report is too big to send automatically. Use “Share another way”."); return; }
        run(() -> {
            Outcome outcome = post(body);
            if (outcome == Outcome.SENT) {
                DiagLog.markReported();
                DiagLog.event("report", "report sent to the relay (" + body.length + " bytes)");
                callback.done("sent", "Sent, thanks!");
            } else if (outcome == Outcome.QUEUE) {
                queue(body);
                callback.done("queued", "");
            } else {
                callback.done("error", "The report could not be sent. Try “Share another way”.");
            }
        }, callback);
    }

    /** Sends reports that were queued while offline. */
    void flushQueue() {
        run(() -> {
            File[] files = queueDir.listFiles((d, n) -> n.endsWith(".json"));
            if (files == null || files.length == 0) return;
            Arrays.sort(files);
            for (File f : files) {
                byte[] body;
                try { body = Files.readAllBytes(f.toPath()); } catch (IOException e) { //noinspection ResultOfMethodCallIgnored
                    f.delete(); continue; }
                Outcome outcome = post(body);
                if (outcome == Outcome.QUEUE) return; // still offline: try again next launch
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                if (outcome == Outcome.SENT) { DiagLog.markReported(); DiagLog.event("report", "queued report sent"); }
            }
        }, null);
    }

    void close() { executor.shutdownNow(); }

    private void run(Runnable task, Callback callback) {
        try { executor.execute(task); }
        catch (RejectedExecutionException closed) { if (callback != null) callback.done("error", "Reopen Framewise and try again."); }
    }

    private void queue(byte[] body) {
        try {
            if (!queueDir.isDirectory() && !queueDir.mkdirs()) return;
            File[] old = queueDir.listFiles((d, n) -> n.endsWith(".json"));
            if (old != null && old.length >= MAX_QUEUED) {
                Arrays.sort(old);
                for (int i = 0; i <= old.length - MAX_QUEUED; i++) //noinspection ResultOfMethodCallIgnored
                    old[i].delete();
            }
            try (OutputStream out = new FileOutputStream(new File(queueDir, System.currentTimeMillis() + ".json"))) { out.write(body); }
            DiagLog.event("report", "offline: report queued for the next launch");
        } catch (IOException ignored) { }
    }

    private static Outcome post(byte[] body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BuildConfig.REPORT_URL).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "Framewise/" + BuildConfig.VERSION_NAME);
            try (OutputStream out = c.getOutputStream()) { out.write(body); }
            int status = c.getResponseCode();
            try (InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream()) { if (in != null) while (in.read() != -1) { /* drain */ } }
            return outcomeFor(status);
        } catch (IOException e) {
            return Outcome.QUEUE;
        } finally { if (c != null) c.disconnect(); }
    }
}
