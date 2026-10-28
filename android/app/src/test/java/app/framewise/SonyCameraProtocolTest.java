package app.framewise;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class SonyCameraProtocolTest {
    @Test public void parsesShutterSpeeds() {
        assertEquals(1 / 250.0, SonyCameraClient.parseShutter("1/250"), 1e-12);
        assertEquals(2.0, SonyCameraClient.parseShutter("2\""), 1e-12);
        assertEquals(0.6, SonyCameraClient.parseShutter("0.6\""), 1e-12);
        assertEquals(-1.0, SonyCameraClient.parseShutter("BULB"), 0);
        assertEquals(-1.0, SonyCameraClient.parseShutter(null), 0);
    }

    @Test public void readsExposureFromGetEvent() throws Exception {
        JSONArray result = new JSONArray()
            .put(new JSONObject().put("type", "availableApiList"))
            .put(new JSONObject().put("type", "cameraStatus").put("cameraStatus", "IDLE"))
            .put(JSONObject.NULL)
            .put(new JSONObject().put("type", "exposureMode").put("currentExposureMode", "Manual"))
            .put(new JSONObject().put("type", "exposureCompensation").put("currentExposureCompensation", -3).put("stepIndexOfExposureCompensation", 1))
            .put(new JSONObject().put("type", "fNumber").put("currentFNumber", "5.6"))
            .put(new JSONObject().put("type", "shutterSpeed").put("currentShutterSpeed", "1/125"))
            .put(new JSONObject().put("type", "isoSpeedRate").put("currentIsoSpeedRate", "800"));
        JSONObject out = SonyCameraClient.parseEvent(result);
        assertEquals(1 / 125.0, out.getDouble("shutter"), 1e-12);
        assertEquals(5.6, out.getDouble("aperture"), 1e-12);
        assertEquals(800, out.getInt("iso"));
        assertEquals("Manual", out.getString("mode"));
        assertEquals(-1.0, out.getDouble("exposureCompensation"), 1e-12);
        assertEquals("IDLE", out.getString("cameraStatus"));
    }

    @Test public void autoIsoIsNotTurnedIntoANumber() throws Exception {
        JSONArray result = new JSONArray().put(new JSONArray().put(new JSONObject().put("type", "isoSpeedRate").put("currentIsoSpeedRate", "AUTO")));
        JSONObject out = SonyCameraClient.parseEvent(result);
        assertEquals("AUTO", out.getString("isoText"));
        assertFalse(out.has("iso"));
    }

    @Test public void liveviewHeaderResynchronises() throws Exception {
        byte[] stream = {0x00, 0x12, (byte) 0xFF, 0x01, 0x00, 0x05, 0x00, 0x00, 0x00, 0x01};
        byte[] common = new byte[8];
        assertTrue(SonyCameraClient.syncToStart(new ByteArrayInputStream(stream), common));
        assertEquals(0x01, common[1] & 0xff);
        assertEquals(5, ((common[2] & 0xff) << 8) | (common[3] & 0xff));
        assertFalse(SonyCameraClient.syncToStart(new ByteArrayInputStream(new byte[]{0x01, 0x02, 0x03}), new byte[8]));
    }
}
