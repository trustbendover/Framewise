package app.framewise;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

public class SceneValidationTest {
    private static final String BASE = "\"summary\":\"s\",\"subjectMotion\":\"still\",\"lighting\":\"daylight\",\"focusAdvice\":\"f\",\"observations\":[],\"limitations\":[]";

    @Test public void acceptsAndClampsScore() throws Exception {
        assertEquals(10, AiClient.validateSceneText("{" + BASE + ",\"score\":14}").getInt("score"));
        assertEquals(7, AiClient.validateSceneText("{" + BASE + ",\"score\":7}").getInt("score"));
    }

    @Test public void scoreIsOptional() throws Exception {
        JSONObject scene = AiClient.validateSceneText("{" + BASE + "}");
        assertFalse(scene.has("score"));
    }

    @Test public void rejectsNonIntegerScore() {
        try { AiClient.validateSceneText("{" + BASE + ",\"score\":\"great\"}"); fail(); }
        catch (Exception expected) { }
        try { AiClient.validateSceneText("{" + BASE + ",\"score\":6.5}"); fail(); }
        catch (Exception expected) { }
    }
}
