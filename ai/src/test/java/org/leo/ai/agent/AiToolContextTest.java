package org.leo.ai.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.AiExecutionPolicy;

import static org.junit.jupiter.api.Assertions.*;

class AiToolContextTest {

    private final AiExecutionPolicy policy = new AiExecutionPolicy("user-1", "user", AiExecutionPolicy.PRIVILEGE_ADMIN);
    private final AiToolDescriptor descriptor = AiToolDescriptor.conservative("fixtureTool");

    @BeforeEach
    @AfterEach
    void clearContext() {
        AiToolContext.clear();
    }

    @ParameterizedTest
    @CsvSource(value = {"null,null,null", "'',null,null", "' ',null,null", "session,session,null",
            "session:thread,session,thread", "session:thread:child,session,thread:child",
            "session:,session,null", ":thread,:thread,null"}, nullValues = "null")
    void memoryIdsKeepTheirExistingSessionAndThreadFormat(String memoryId, String sessionId, String threadId) {
        AiToolContext.setFromMemoryId(memoryId);

        assertEquals(sessionId, AiToolContext.getSessionId());
        assertEquals(threadId, AiToolContext.getThreadId());
        if (sessionId == null) assertThrows(IllegalStateException.class, AiToolContext::requireSessionId);
        else assertEquals(sessionId, AiToolContext.requireSessionId());
    }

    @Test
    void capturedContextCanBeRestoredAfterEveryFieldChanges() {
        populate();
        AiToolContext.Snapshot snapshot = AiToolContext.capture();
        AiToolContext.setFromMemoryId("other:thread-2");
        AiToolContext.setPlanStepIndex(7);
        AiToolContext.setExecutionPolicy(new AiExecutionPolicy("other", "other", null));
        AiToolContext.setToolDescriptor(AiToolDescriptor.conservative("otherTool"));

        AiToolContext.restore(snapshot);

        assertEquals("session", AiToolContext.requireSessionId());
        assertEquals("thread", AiToolContext.getThreadId());
        assertEquals(3, AiToolContext.getPlanStepIndex());
        assertEquals("user-1", AiToolContext.requireExecutionPolicy().getUserId());
        assertTrue(AiToolContext.requireExecutionPolicy().isAdmin());
        assertEquals(descriptor, AiToolContext.getToolDescriptor());
    }

    @Test
    void restoringPartialOrNullContextRemovesPreviousIdentityAndToolMetadata() {
        populate();

        AiToolContext.restore(new AiToolContext.Snapshot("new-session", "new-thread", Integer.MIN_VALUE, null, null));

        assertEquals("new-session", AiToolContext.requireSessionId());
        assertEquals("new-thread", AiToolContext.getThreadId());
        assertEquals(-1, AiToolContext.getPlanStepIndex());
        assertNull(AiToolContext.getToolDescriptor());
        assertThrows(SecurityException.class, AiToolContext::requireExecutionPolicy);
        AiToolContext.restore(null);
        assertEmpty();
    }

    @Test
    void fieldResetsPreserveUnrelatedContextUntilClear() {
        populate();
        AiToolContext.setPlanStepIndex(-2);
        assertEquals(-1, AiToolContext.getPlanStepIndex());
        assertEquals("session", AiToolContext.requireSessionId());
        assertEquals("user-1", AiToolContext.requireExecutionPolicy().getUserId());
        assertEquals(descriptor, AiToolContext.getToolDescriptor());

        AiToolContext.setFromMemoryId(null);
        assertNull(AiToolContext.getSessionId());
        assertNull(AiToolContext.getThreadId());
        assertEquals("user-1", AiToolContext.requireExecutionPolicy().getUserId());
        assertEquals(descriptor, AiToolContext.getToolDescriptor());

        AiToolContext.setExecutionPolicy(null);
        assertThrows(SecurityException.class, AiToolContext::requireExecutionPolicy);
        assertEquals(descriptor, AiToolContext.getToolDescriptor());
        AiToolContext.setToolDescriptor(null);
        assertEmpty();
        populate();
        AiToolContext.clear();
        assertEmpty();
    }

    @Test
    void changingAFallbackPolicyCannotAuthenticateTheContext() {
        AiExecutionPolicy fallback = AiToolContext.getExecutionPolicy();
        fallback.setUserId("forged");
        fallback.setPrivilege(AiExecutionPolicy.PRIVILEGE_ADMIN);

        assertThrows(SecurityException.class, AiToolContext::requireExecutionPolicy);
        assertFalse(AiToolContext.getExecutionPolicy().isAdmin());
        AiToolContext.restore(AiToolContext.capture());
        assertThrows(SecurityException.class, AiToolContext::requireExecutionPolicy);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void aRoleWithoutAnAuthenticatedUserCannotBeRequired(String userId) {
        AiToolContext.setExecutionPolicy(new AiExecutionPolicy(userId, "user", AiExecutionPolicy.PRIVILEGE_ADMIN));

        assertThrows(SecurityException.class, AiToolContext::requireExecutionPolicy);
    }

    private void populate() {
        AiToolContext.setFromMemoryId("session:thread");
        AiToolContext.setPlanStepIndex(3);
        AiToolContext.setExecutionPolicy(policy);
        AiToolContext.setToolDescriptor(descriptor);
    }

    private static void assertEmpty() {
        assertNull(AiToolContext.getSessionId());
        assertNull(AiToolContext.getThreadId());
        assertEquals(-1, AiToolContext.getPlanStepIndex());
        assertNull(AiToolContext.getToolDescriptor());
        assertThrows(SecurityException.class, AiToolContext::requireExecutionPolicy);
    }
}
