package app.framewise;

import static org.junit.Assert.*;

import org.junit.Test;

public class ReportUploaderTest {
    @Test public void sentQueuedOrRejected() {
        assertEquals(ReportUploader.Outcome.SENT, ReportUploader.outcomeFor(201));
        assertEquals(ReportUploader.Outcome.QUEUE, ReportUploader.outcomeFor(-1));
        assertEquals(ReportUploader.Outcome.QUEUE, ReportUploader.outcomeFor(429));
        assertEquals(ReportUploader.Outcome.QUEUE, ReportUploader.outcomeFor(503));
        assertEquals(ReportUploader.Outcome.REJECTED, ReportUploader.outcomeFor(400));
        assertEquals(ReportUploader.Outcome.REJECTED, ReportUploader.outcomeFor(413));
    }

    @Test public void onlyFramewiseReportsUnder64KbAreSent() {
        assertNull(ReportUploader.prepare(null));
        assertNull(ReportUploader.prepare("{\"format\":\"other\"}"));
        StringBuilder big = new StringBuilder("{\"format\":\"framewise-test-report/1\",\"x\":\"");
        for (int i = 0; i < 70_000; i++) big.append('a');
        assertNull(ReportUploader.prepare(big.append("\"}").toString()));
    }

    @Test public void secretsAreRemovedBeforeSending() {
        byte[] body = ReportUploader.prepare("{\"format\":\"framewise-test-report/1\",\"log\":\"key sk-abcdefghijklmnopqrstuvwx\"}");
        assertNotNull(body);
        assertFalse(new String(body, java.nio.charset.StandardCharsets.UTF_8).contains("sk-abcdefghijklmnop"));
    }
}
