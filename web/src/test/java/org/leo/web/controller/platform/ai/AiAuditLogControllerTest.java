package org.leo.web.controller.platform.ai;

import org.junit.jupiter.api.Test;
import org.leo.ai.audit.AiAuditLogStore;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.User;
import org.leo.service.agentnode.AgentService;
import org.leo.service.user.UserService;
import org.leo.web.config.GlobalExceptionHandler;
import org.leo.web.security.PermissionService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiAuditLogControllerTest {

    @Test
    void readsAndClearsBoundedAuditLogsWithoutChangingTheResponseContract() throws Exception {
        var store = new AiAuditLogStore();
        for (int i = 0; i < 2000; i++) {
            store.append(AiChatAuditEntry.platform("admin", "admin", "admin", "entry-" + i));
        }
        var newest = AiChatAuditEntry.platform("admin", "admin", "admin", "entry-2000");
        store.append(newest);
        var admin = new User("admin", "admin", "fixture", "admin", null);
        var member = new User("member", "member", "fixture", "normal", null);
        var users = mock(UserService.class);
        when(users.getUserById("admin")).thenReturn(admin);
        when(users.getUserById("member")).thenReturn(member);
        var permissions = new PermissionService(mock(AgentService.class), users);
        var mvc = MockMvcBuilders.standaloneSetup(new AiAuditLogController(store, permissions))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        String logs = "/platform/ai/audit/logs";

        mvc.perform(post(logs).sessionAttr("user", member))
                .andExpect(status().isForbidden());
        mvc.perform(post(logs).sessionAttr("user", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2000))
                .andExpect(jsonPath("$.data.returned").value(100))
                .andExpect(jsonPath("$.data.logs.length()").value(100))
                .andExpect(jsonPath("$.data.logs[0].messageSummary").value("entry-2000"))
                .andExpect(jsonPath("$.data.logs[0].sessionType").value("PLATFORM"))
                .andExpect(jsonPath("$.data.logs[0].sessionId").hasJsonPath())
                .andExpect(jsonPath("$.data.logs[0].replySummary").hasJsonPath())
                .andExpect(jsonPath("$.data.logs[0].status").value("pending"));
        mvc.perform(post(logs).sessionAttr("user", admin).contentType(MediaType.APPLICATION_JSON).content("{\"limit\":0}"))
                .andExpect(jsonPath("$.data.returned").value(2000))
                .andExpect(jsonPath("$.data.logs[1999].messageSummary").value("entry-1"));

        var pending = store.snapshot(1).logs().get(0);
        newest.complete("fixture reply", 3, 42L);
        mvc.perform(post(logs).sessionAttr("user", admin).contentType(MediaType.APPLICATION_JSON).content("{\"limit\":1}"))
                .andExpect(jsonPath("$.data.logs[0].id").value(pending.id()))
                .andExpect(jsonPath("$.data.logs[0].status").value("ok"))
                .andExpect(jsonPath("$.data.logs[0].replySummary").value("fixture reply"))
                .andExpect(jsonPath("$.data.logs[0].toolCallCount").value(3))
                .andExpect(jsonPath("$.data.logs[0].durationMs").value(42));
        var beforeClear = store.snapshot(1);
        newest.fail("fixture failure", 50L);
        assertEquals("error", store.snapshot(1).logs().get(0).status());
        mvc.perform(post("/platform/ai/audit/clear").sessionAttr("user", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        assertEquals("pending", pending.status());
        assertNull(pending.replySummary());
        assertEquals(2000, beforeClear.total());
        assertEquals("ok", beforeClear.logs().get(0).status());
        assertEquals("fixture reply", beforeClear.logs().get(0).replySummary());
        mvc.perform(post(logs).sessionAttr("user", admin))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.returned").value(0))
                .andExpect(jsonPath("$.data.logs").isEmpty());
    }
}
