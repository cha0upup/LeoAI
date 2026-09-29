package org.leo.phpcore.component;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.leo.phpcore.PhpTestSupport.invokeComponent;
import static org.leo.phpcore.PhpTestSupport.phpAvailable;

class PhpScriptAndPluginComponentTest {

    @Test
    void scriptReturnsExplicitSuccessCode() throws Exception {
        Assumptions.assumeTrue(phpAvailable());
        Map<String, Object> result = invokeComponent("ExecScriptComponent.php", "exec",
                "array('language'=>'php','script'=>'return 7;')");
        assertEquals(200, result.get("code"));
        assertEquals(7, result.get("returnValue"));
    }

    @Test
    void pluginPreservesUserResultAndOwnStatus() throws Exception {
        Assumptions.assumeTrue(phpAvailable());
        Map<String, Object> scalar = invokeComponent("PluginComponent.php", "invoke",
                "array('source'=>'return \"ok\";')");
        assertEquals(200, scalar.get("code"));
        assertEquals("ok", scalar.get("result"));

        Map<String, Object> mapping = invokeComponent("PluginComponent.php", "invoke",
                "array('source'=>'return array(\"value\"=>7);')");
        assertEquals(200, mapping.get("code"));
        assertEquals(7, mapping.get("value"));

        Map<String, Object> failed = invokeComponent("PluginComponent.php", "invoke",
                "array('source'=>'return array(\"code\"=>409,\"msg\"=>\"blocked\");')");
        assertEquals(409, failed.get("code"));
        assertEquals("blocked", failed.get("msg"));
    }
}
