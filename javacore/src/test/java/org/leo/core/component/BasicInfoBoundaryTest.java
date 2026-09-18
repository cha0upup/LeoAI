package org.leo.core.component;

import javassist.ClassPool;
import javassist.CtClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.leo.core.component.ComponentParameterBoundaryTest.*;

class BasicInfoBoundaryTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unknownActionIsRejectedAndByteActionUsesRequestedView(boolean payload) throws Exception {
        Object component = component("BasicInfoComponent", payload);
        for (Object action : new Object[]{"unknown", utf8("unknown")}) {
            Map<String, Object> result = prepare(component, Map.of("action", action));
            call(component, "invoke", new Class[0]);
            assertEquals(400, result.get("code"));
            assertFalse(result.containsKey("BasicInfo"));
        }
        Map<String, Object> result = prepare(component, Map.of("action", utf8("disks")));
        call(component, "invoke", new Class[0]);
        assertEquals(200, result.get("code"));
        assertInstanceOf(List.class, result.get("disks"));
        assertFalse(result.containsKey("BasicInfo"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void processSourceReportsTheCollectorThatReturnedResults(boolean payload) throws Exception {
        String resource = payload ? "/component/BasicInfoComponent.payload"
                : "/org/leo/core/component/BasicInfoComponent.class";
        String[] methods = {"getProcessHandleProcesses", "getJnaProcesses", "getProcProcesses"};
        String[] sources = {"ProcessHandle", "JNA", "/proc"};
        for (int selected = 0; selected < methods.length; selected++) {
            ClassPool pool = new ClassPool(true);
            try (var input = getClass().getResourceAsStream(resource)) {
                CtClass type = pool.makeClass(input);
                try {
                    for (int i = 0; i < methods.length; i++) {
                        type.getDeclaredMethod(methods[i]).setBody(i == selected
                                ? "{ return java.util.Collections.singletonList(new java.util.HashMap()); }"
                                : "{ return java.util.Collections.emptyList(); }");
                    }
                    Object component = new BytecodeLoader().load(type.toBytecode()).getDeclaredConstructor().newInstance();
                    Map<String, Object> result = prepare(component, Map.of("action", "processes"));
                    call(component, "invoke", new Class[0]);
                    assertEquals(sources[selected], result.get("source"));
                    assertEquals(1, result.get("total"));
                } finally { type.detach(); }
            }
        }
    }
}
