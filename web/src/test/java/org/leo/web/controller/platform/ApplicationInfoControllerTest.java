package org.leo.web.controller.platform;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.User;
import org.leo.web.interceptor.LoginInterceptor;
import org.leo.web.security.PermissionService;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Properties;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApplicationInfoControllerTest {
    private MockMvc mvc(Properties metadata, boolean loggedIn) {
        var beans = new StaticListableBeanFactory();
        if (metadata != null) beans.addBean("buildProperties", new BuildProperties(metadata));
        var permissions = mock(PermissionService.class);
        if (loggedIn) {
            var user = new User();
            user.setPrivilege("normal");
            when(permissions.getCurrentUser(any())).thenReturn(user);
        }
        return MockMvcBuilders.standaloneSetup(new ApplicationInfoController(beans.getBeanProvider(BuildProperties.class)))
                .addInterceptors(new LoginInterceptor(permissions)).build();
    }

    @Test
    void returnsTheRunningBuildToAnyLoggedInUserWithoutCaching() throws Exception {
        var metadata = new Properties();
        metadata.setProperty("version", "4.2.1-rc.2+fixture");
        metadata.setProperty("time", "2026-10-16T01:02:03Z");
        mvc(metadata, true).perform(get("/platform/application/info"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.version").value("4.2.1-rc.2+fixture"))
                .andExpect(jsonPath("$.data.buildTime").value("2026-10-16T01:02:03Z"));
    }

    @Test
    void leavesMissingDevelopmentMetadataUnknown() throws Exception {
        mvc(null, true).perform(get("/platform/application/info"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").isEmpty())
                .andExpect(jsonPath("$.data.buildTime").isEmpty());
    }

    @Test
    void requiresLogin() throws Exception {
        mvc(null, false).perform(get("/platform/application/info"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
    }
}
