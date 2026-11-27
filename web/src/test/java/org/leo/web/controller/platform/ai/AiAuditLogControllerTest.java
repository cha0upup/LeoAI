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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiAuditLogControllerTest {

    @Test
    void readsAndClearsBoundedAuditLogsWithoutChangingTheResponseContract() throws Exception {
        var store = new AiAuditLogStore();
        for (int i = 0; i <= 2000; i++) {
            store.append(AiChatAuditEntry.platform("admin", "admin", "admin", "entry-" + i));
        }
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
                .andExpect(jsonPath("$.data.logs[0].messageSummary").value("entry-2000"));
        mvc.perform(post(logs).sessionAttr("user", admin).contentType(MediaType.APPLICATION_JSON).content("{\"limit\":0}"))
                .andExpect(jsonPath("$.data.returned").value(2000))
                .andExpect(jsonPath("$.data.logs[1999].messageSummary").value("entry-1"));

        var beforeClear = store.snapshot(1);
        mvc.perform(post("/platform/ai/audit/clear").sessionAttr("user", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        assertEquals(2000, beforeClear.total());
        assertEquals("entry-2000", beforeClear.logs().get(0).getMessageSummary());
        mvc.perform(post(logs).sessionAttr("user", admin))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.returned").value(0))
                .andExpect(jsonPath("$.data.logs").isEmpty());
    }
}
