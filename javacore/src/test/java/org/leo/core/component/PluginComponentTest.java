package org.leo.core.component;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtNewMethod;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.leo.core.component.ComponentTestSupport.setField;

class PluginComponentTest {

    @Test
    void loadsPluginOnJava17WithoutOpeningJavaLang() throws Exception {
        String className = "org.leo.generated.Plugin" + System.nanoTime();
        ClassPool pool = new ClassPool(true);
        CtClass generated = pool.makeClass(className);
        generated.addField(CtField.make("private String value = \"\";", generated));
        generated.addMethod(CtNewMethod.make(
                "public boolean equals(Object other) {"
                        + "if (other instanceof java.util.Map) {"
                        + "Object v = ((java.util.Map) other).get(\"value\");"
                        + "value = v == null ? \"\" : v.toString();"
                        + "} return true; }", generated));
        generated.addMethod(CtNewMethod.make(
                "public String toString() { return value; }", generated));

        byte[] bytecode;
        try {
            bytecode = generated.toBytecode();
        } finally {
            generated.detach();
        }

        HashMap<String, Object> pluginParam = new HashMap<>();
        pluginParam.put("value", "jdk17-ok");
        HashMap<String, Object> params = new HashMap<>();
        params.put("pluginBytecode", bytecode);
        params.put("pluginParam", pluginParam);
        HashMap<String, Object> results = new HashMap<>();

        PluginComponent component = new PluginComponent();
        setField(component, "params", params);
        setField(component, "results", results);
        component.invoke();

        assertEquals(200, results.get("code"));
        assertEquals("jdk17-ok", results.get("result"));
    }
}
