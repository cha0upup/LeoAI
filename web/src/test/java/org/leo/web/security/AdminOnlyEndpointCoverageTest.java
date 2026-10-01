package org.leo.web.security;

import org.junit.jupiter.api.Test;
import org.leo.web.controller.platform.ai.PlatformAiController;
import org.leo.web.controller.platform.disguise.DisguiseManagerController;
import org.leo.web.controller.platform.fingerprint.FingerprintManageController;
import org.leo.web.controller.platform.plugin.PluginManageController;
import org.leo.web.controller.platform.skill.SkillController;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminOnlyEndpointCoverageTest {

    @Test
    void dynamicCodeAndAiAgentConfigurationWritesRemainAdminOnly() {
        assertAdminOnly(DisguiseManagerController.class,
                Set.of("addDisguise", "delDisguise", "updateDisguise", "testDisguise",
                        "previewDisguise", "importDisguises"));
        assertAdminOnly(PluginManageController.class,
                Set.of("addPlugin", "delPlugin", "updatePlugin", "importPlugins"));
        assertAdminOnly(FingerprintManageController.class,
                Set.of("saveFingerprint", "deleteFingerprint", "importFingerprints"));
        assertAdminOnly(SkillController.class,
                Set.of("save", "delete", "deleteBatch", "toggle", "toggleBatch", "saveFile", "deleteFile",
                        "moveFile", "importSkills"));
        assertAdminOnly(PlatformAiController.class, Set.of("telemetry"));
    }

    private static void assertAdminOnly(Class<?> controllerType, Set<String> methodNames) {
        for (String methodName : methodNames) {
            boolean protectedMethodExists = Arrays.stream(controllerType.getDeclaredMethods())
                    .filter(method -> method.getName().equals(methodName))
                    .anyMatch(AdminOnlyEndpointCoverageTest::isAdminOnly);
            assertTrue(protectedMethodExists,
                    () -> controllerType.getSimpleName() + "." + methodName + " must be admin-only");
        }
    }

    private static boolean isAdminOnly(Method method) {
        return method.isAnnotationPresent(AdminOnlyEndpoint.class)
                || method.getDeclaringClass().isAnnotationPresent(AdminOnlyEndpoint.class);
    }
}
