package org.leo.web.controller.agentnode.command;

import org.junit.jupiter.api.Test;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.agent.capability.ComponentInvokeCapable;
import org.leo.core.agent.capability.LocalForwardCapable;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.runtime.CapabilitySet;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.service.agentnode.AgentService;
import org.leo.service.ssh.SshConnectionException;
import org.leo.service.ssh.SshConnectionProfiles;
import org.leo.service.ssh.SshConnectionSpec;
import org.leo.service.ssh.SshProfileConnectCommand;
import org.leo.service.ssh.SshProfileSaveCommand;
import org.leo.service.ssh.SshTransport;
import org.leo.service.user.UserService;
import org.leo.web.adapter.ssh.AgentSshService;
import org.leo.web.dto.SessionIdRequest;
import org.leo.web.dto.agentnode.command.CommandExecRequest;
import org.leo.web.dto.agentnode.command.SshProfileConnectRequest;
import org.leo.web.dto.agentnode.command.SshProfileRequest;
import org.leo.web.dto.agentnode.command.SshProfileSaveRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.security.PermissionService;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SshTerminalControllerTest {
    SshConnectionSpec request() {
        return new SshConnectionSpec("process", "host", 22, "user", "password", "secret",
                null, null, "SHA256:" + "A".repeat(43));
    }
    @Test void validatesSavedProfileInputBeforeParentLookup() {
        var ssh = mock(AgentSshService.class); var profiles = mock(SshConnectionProfiles.class);
        var controller = new SshTerminalController(ssh, profiles);
        try (var util = mockStatic(ControllerUtil.class)) {
            for (String invalidHost : List.of("", "bad/path", "bad host")) {
                var invalid = new SshProfileSaveRequest("parent", null, null, "name", invalidHost, 22,
                        "user", "password", "secret", null, null);
                assertThrows(SshConnectionException.class, () -> controller.saveProfile(invalid, new MockHttpServletRequest()));
            }
            for (int invalidPort : List.of(0, 65536)) {
                var invalid = new SshProfileSaveRequest("parent", null, null, "name", "host", invalidPort,
                        "user", "password", "secret", null, null);
                assertThrows(SshConnectionException.class, () -> controller.saveProfile(invalid, new MockHttpServletRequest()));
            }
            assertThrows(ApiException.class, () -> controller.saveProfile(null, new MockHttpServletRequest()));
            assertThrows(ApiException.class, () -> controller.connectProfile(null, new MockHttpServletRequest()));
            assertThrows(SshConnectionException.class, () -> controller.profiles(new SessionIdRequest("bad:id"), new MockHttpServletRequest()));
            for (String session : java.util.Arrays.asList(null, "", " bad", "bad:id")) {
                var save = new SshProfileSaveRequest(session, null, null, "name", "host", 22,
                        "user", "password", "secret", null, null);
                var connect = new SshProfileConnectRequest(session, "process", java.util.UUID.randomUUID().toString(),
                        1L, request().fingerprint());
                assertEquals(SshConnectionException.Kind.VALIDATION, assertThrows(SshConnectionException.class,
                        () -> controller.saveProfile(save, new MockHttpServletRequest())).getKind());
                assertEquals(SshConnectionException.Kind.VALIDATION, assertThrows(SshConnectionException.class,
                        () -> controller.connectProfile(connect, new MockHttpServletRequest())).getKind());
            }
            util.verifyNoInteractions(); verifyNoInteractions(ssh, profiles);
        }
    }
    @Test void deniesParentAccessBeforeAnySshOrProfileOperation() {
        var ssh = mock(AgentSshService.class); var profiles = mock(SshConnectionProfiles.class);
        var controller = new SshTerminalController(ssh, profiles);
        try (var util = mockStatic(ControllerUtil.class)) {
            util.when(() -> ControllerUtil.getAgentNodeSession("parent")).thenThrow(ApiException.forbidden("no access"));
            assertThrows(ApiException.class, () -> controller.profiles(new SessionIdRequest("parent"), new MockHttpServletRequest()));
            String id = java.util.UUID.randomUUID().toString();
            assertThrows(ApiException.class, () -> controller.getProfile(new SshProfileRequest("parent", id, null), new MockHttpServletRequest()));
            assertThrows(ApiException.class, () -> controller.deleteProfile(new SshProfileRequest("parent", id, 1L), new MockHttpServletRequest()));
            assertThrows(ApiException.class, () -> controller.saveProfile(new SshProfileSaveRequest("parent", null, null, "fixture", "host", 22,
                    "user", "password", "secret", null, null), new MockHttpServletRequest()));
            assertThrows(ApiException.class, () -> controller.connectProfile(new SshProfileConnectRequest("parent", "child", id, 1L,
                    request().fingerprint()), new MockHttpServletRequest()));
            verifyNoInteractions(ssh, profiles);
        }
    }
    @Test void sharedProfileVisibilityFollowsCurrentPrivateTeamPublicAndAdminAgentPermissions() {
        var ssh = mock(AgentSshService.class); var profiles = mock(SshConnectionProfiles.class);
        var controller = new SshTerminalController(ssh, profiles);
        var agents = mock(AgentService.class); var users = mock(UserService.class);
        var permissions = new PermissionService(agents, users);
        Agent agent = new Agent(); agent.setAgentId("agent"); agent.setParentAgentId("root");
        agent.setCreateByUserId("owner"); agent.setTeamId("team-a");
        when(agents.findAgentById("agent")).thenReturn(agent);
        var previousRequest = RequestContextHolder.getRequestAttributes();
        try {
            for (String permission : List.of("private", "team", "public")) {
                agent.setPermission(permission);
                for (String caller : List.of("owner", "member", "outsider", "admin")) {
                    clearInvocations(profiles);
                    User user = new User(); user.setUserId(caller); user.setStatus(1); user.setPassword("fixture-hash");
                    user.setPrivilege(caller.equals("admin") ? "admin" : "normal");
                    user.setTeamId(caller.equals("outsider") ? "team-b" : "team-a");
                    when(users.getUserById(caller)).thenReturn(user);
                    var node = mock(AbstractAgentNode.class, withSettings().extraInterfaces(LocalForwardCapable.class, ComponentInvokeCapable.class));
                    when(node.getAgent()).thenReturn(agent); when(node.getCapabilitySet()).thenReturn(CapabilitySet.empty());
                    String id = "ssh-visibility-" + java.util.UUID.randomUUID();
                    var parent = mock(AgentNodeSession.class);
                    when(parent.getSessionId()).thenReturn(id); when(parent.resolveAgentId()).thenReturn("agent");
                    when(parent.getCreateByUser()).thenReturn(caller); when(parent.getAgentNode()).thenReturn(node);
                    AgentNodeSessionContainer.addSession(id, parent);
                    var http = new MockHttpServletRequest(); http.getSession().setAttribute("user", user);
                    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(http));
                    permissions.requireLogin(http);
                    boolean allowed = caller.equals("owner") || caller.equals("admin") || permission.equals("public")
                            || permission.equals("team") && caller.equals("member");
                    when(profiles.list(caller, parent)).thenReturn(List.of(Map.of("name", "shared")));
                    try {
                        if (allowed) {
                            assertEquals(List.of(Map.of("name", "shared")), controller.profiles(new SessionIdRequest(id), http).get("data"));
                            verify(profiles).list(caller, parent);
                        } else {
                            assertEquals(403, assertThrows(ApiException.class,
                                    () -> controller.profiles(new SessionIdRequest(id), http)).getCode());
                            verifyNoInteractions(profiles);
                        }
                    } finally {
                        http.getSession().invalidate();
                        AgentNodeSessionContainer.removeSession(id);
                    }
                }
            }
        } finally {
            RequestContextHolder.setRequestAttributes(previousRequest);
        }
    }
    @Test void routesProfileCrudThroughTheAuthenticatedOwnerAndParent() throws Exception {
        var ssh = mock(AgentSshService.class); var profiles = mock(SshConnectionProfiles.class);
        var controller = new SshTerminalController(ssh, profiles);
        var http = new MockHttpServletRequest(); http.getSession();
        User owner = new User(); owner.setUserId("owner");
        AgentNodeSession parent = mock(AgentNodeSession.class);
        String id = java.util.UUID.randomUUID().toString();
        Map<String, Object> metadata = Map.of("profileId", id, "version", 1L, "host", "host", "hasPassword", true);
        when(profiles.get("owner", parent, id)).thenReturn(metadata);
        when(profiles.save(eq("owner"), eq(parent), any(SshProfileSaveCommand.class))).thenReturn(metadata);
        try (var util = mockStatic(ControllerUtil.class)) {
            util.when(() -> ControllerUtil.getAgentNodeSession("parent")).thenReturn(parent);
            util.when(() -> ControllerUtil.getCurrentUser(http)).thenReturn(owner);
            assertSame(metadata, controller.getProfile(new SshProfileRequest("parent", id, null), http).get("data"));
            assertSame(metadata, controller.saveProfile(new SshProfileSaveRequest("parent", null, null, "fixture", "host", 22,
                    "user", "password", "secret", null, null), http).get("data"));
            controller.deleteProfile(new SshProfileRequest("parent", id, 1L), http);
            verify(profiles).delete("owner", parent, id, 1L);
            when(profiles.get("owner", parent, id)).thenThrow(
                    new SshConnectionException(SshConnectionException.Kind.NOT_FOUND, "fixture missing"));
            assertEquals(SshConnectionException.Kind.NOT_FOUND, assertThrows(SshConnectionException.class,
                    () -> controller.getProfile(new SshProfileRequest("parent", id, null), http)).getKind());
            when(profiles.save(eq("owner"), eq(parent), any(SshProfileSaveCommand.class))).thenThrow(
                    new SshConnectionException(SshConnectionException.Kind.CAPACITY, "fixture limit"));
            assertEquals(SshConnectionException.Kind.CAPACITY, assertThrows(SshConnectionException.class,
                    () -> controller.saveProfile(new SshProfileSaveRequest("parent", null, null, "fixture", "host", 22,
                            "user", "password", "secret", null, null), http)).getKind());
            doThrow(new SshConnectionException(SshConnectionException.Kind.CONFLICT, "fixture version"))
                    .when(profiles).delete("owner", parent, id, 1L);
            assertEquals(SshConnectionException.Kind.CONFLICT, assertThrows(SshConnectionException.class,
                    () -> controller.deleteProfile(new SshProfileRequest("parent", id, 1L), http)).getKind());
            verifyNoInteractions(ssh);
        }
    }
    @Test void storedConnectionsResolveSecretsOnlyOnTheServerAndSurviveAFailedPinWriteback() throws Exception {
        var ssh = mock(AgentSshService.class); var profiles = mock(SshConnectionProfiles.class);
        var controller = new SshTerminalController(ssh, profiles);
        var http = new MockHttpServletRequest(); String browser = http.getSession().getId();
        User owner = new User(); owner.setUserId("owner");
        AgentNodeSession parent = mock(AgentNodeSession.class);
        ComponentInvokeCapable node = mock(ComponentInvokeCapable.class);
        String id = java.util.UUID.randomUUID().toString();
        var request = new SshProfileConnectRequest("parent", "process", id, 1L, request().fingerprint());
        when(profiles.getForConnection("owner", parent, request.toCommand())).thenReturn(Map.of("host", "host", "port", 22, "fingerprint", request.fingerprint()));
        when(profiles.resolveConnection("owner", parent, request.toCommand())).thenReturn(request());
        doThrow(new SshConnectionException(SshConnectionException.Kind.NOT_FOUND, "removed during connect"))
                .when(profiles).recordFingerprint("owner", parent, id, 1L, request.fingerprint(), request.fingerprint());
        try (var util = mockStatic(ControllerUtil.class)) {
            util.when(() -> ControllerUtil.getAgentNodeSession("parent")).thenReturn(parent);
            util.when(() -> ControllerUtil.getCurrentUser(http)).thenReturn(owner);
            util.when(() -> ControllerUtil.requireCapability(parent, ComponentInvokeCapable.class)).thenReturn(node);
            Map<?, ?> result = (Map<?, ?>) controller.connectProfile(request, http).get("data");
            verify(ssh).connect("owner", browser, parent, node, request());
            var command = org.mockito.ArgumentCaptor.forClass(SshProfileConnectCommand.class);
            verify(profiles).getForConnection(eq("owner"), same(parent), command.capture());
            verify(profiles).resolveConnection(eq("owner"), same(parent), same(command.getValue()));
            assertEquals(Map.of("profileSaved", false), result);
            when(profiles.resolveConnection("owner", parent, request.toCommand())).thenThrow(new IllegalStateException("sensitive fixture value"));
            assertFalse(assertThrows(ApiException.class, () -> controller.connectProfile(request, http)).getMessage().contains("sensitive"));
            verify(ssh, times(1)).connect(any(), any(), any(), any(), any());
        }
    }
    @Test void savedPinConnectsDirectlyAndOnlyMissingOrChangedKeysRequireConfirmation() throws Exception {
        var ssh = mock(AgentSshService.class); var profiles = mock(SshConnectionProfiles.class);
        var controller = new SshTerminalController(ssh, profiles);
        var http = new MockHttpServletRequest(); String browser = http.getSession().getId();
        User owner = new User(); owner.setUserId("owner");
        AgentNodeSession parent = mock(AgentNodeSession.class);
        ComponentInvokeCapable node = mock(ComponentInvokeCapable.class);
        String id = java.util.UUID.randomUUID().toString();
        var request = new SshProfileConnectRequest("parent", "process", id, 1L, null);
        Map<String, Object> known = Map.of("host", "host", "port", 22, "fingerprint", request().fingerprint());
        when(profiles.getForConnection("owner", parent, request.toCommand())).thenReturn(known);
        when(profiles.resolveConnection("owner", parent, request.toCommand())).thenReturn(request());
        try (var util = mockStatic(ControllerUtil.class)) {
            util.when(() -> ControllerUtil.getAgentNodeSession("parent")).thenReturn(parent);
            util.when(() -> ControllerUtil.getCurrentUser(http)).thenReturn(owner);
            util.when(() -> ControllerUtil.requireCapability(parent, ComponentInvokeCapable.class)).thenReturn(node);
            controller.connectProfile(request, http);
            verify(ssh).connect("owner", browser, parent, node, request());
            verify(ssh, never()).probeFingerprint(any(), any(), any(), any(), anyString(), anyInt());
            verify(profiles).recordFingerprint("owner", parent, id, 1L, request().fingerprint(), request().fingerprint());

            clearInvocations(ssh, profiles);
            when(profiles.getForConnection("owner", parent, request.toCommand())).thenReturn(Map.of("host", "host", "port", 22, "fingerprint", ""));
            when(ssh.probeFingerprint("owner", browser, parent, node, "host", 22)).thenReturn(request().fingerprint());
            ApiException first = assertThrows(ApiException.class, () -> controller.connectProfile(request, http));
            assertEquals(409, first.getCode());
            assertEquals("SSH_HOST_KEY_CONFIRMATION_REQUIRED", first.getDetails().get("errorCode"));
            assertEquals("", first.getDetails().get("previousFingerprint"));
            verify(profiles, never()).resolveConnection(any(), any(), any());
            verify(ssh, never()).connect(any(), any(), any(), any(), any());

            clearInvocations(ssh, profiles);
            when(profiles.getForConnection("owner", parent, request.toCommand())).thenReturn(known);
            var changed = mock(SshTransport.HostKeyChangedException.class);
            String observed = "SHA256:" + "B".repeat(43);
            when(changed.fingerprint()).thenReturn(observed);
            doThrow(changed).when(ssh).connect(any(), any(), any(), any(), any());
            ApiException mismatch = assertThrows(ApiException.class, () -> controller.connectProfile(request, http));
            assertEquals(409, mismatch.getCode());
            assertEquals(observed, mismatch.getDetails().get("fingerprint"));
            assertEquals(request().fingerprint(), mismatch.getDetails().get("previousFingerprint"));
            verify(ssh, times(1)).connect(any(), any(), any(), any(), any());
            verify(ssh, never()).probeFingerprint(any(), any(), any(), any(), anyString(), anyInt());
            verify(profiles, never()).recordFingerprint(any(), any(), any(), anyLong(), any(), any());
        }
    }
    @Test void bindsSshResourcesToTheAuthenticatedBrowserAndReleasesThemOnLogout() throws Exception {
        var ssh = mock(AgentSshService.class); var profiles = mock(SshConnectionProfiles.class);
        var controller = new SshTerminalController(ssh, profiles);
        var http = new MockHttpServletRequest(); String browser = http.getSession().getId();
        User owner = new User(); owner.setUserId("owner");
        AgentNodeSession parent = mock(AgentNodeSession.class);
        ComponentInvokeCapable node = mock(ComponentInvokeCapable.class);
        when(profiles.list("owner", parent)).thenReturn(List.of());
        try (var util = mockStatic(ControllerUtil.class)) {
            util.when(() -> ControllerUtil.getAgentNodeSession("parent")).thenReturn(parent);
            util.when(() -> ControllerUtil.getCurrentUser(http)).thenReturn(owner);
            util.when(() -> ControllerUtil.requireCapability(parent, ComponentInvokeCapable.class)).thenReturn(node);
            controller.profiles(new SessionIdRequest("parent"), http);
            http.getSession().invalidate(); verify(ssh).closeBrowser(browser);
        }
    }
    @Test void exposesOnlySavedProfileAndTerminalCommandEndpoints() {
        var routes = java.util.Arrays.stream(SshTerminalController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(mapping -> java.util.Arrays.stream(mapping.value()))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of("/profiles", "/profiles/get", "/profiles/save", "/profiles/delete",
                "/profiles/connect", "/command"), routes);
    }
    @Test void auditsOnlyLifecycleMetadataAndNeverTerminalBytesOrCredentials() throws Exception {
        var ssh = mock(AgentSshService.class); var profiles = mock(SshConnectionProfiles.class);
        var controller = new SshTerminalController(ssh, profiles);
        var http = new MockHttpServletRequest(); http.getSession();
        User owner = new User(); owner.setUserId("owner");
        AgentNodeSession parent = mock(AgentNodeSession.class);
        AbstractAgentNode node = mock(AbstractAgentNode.class);
        when(parent.getAgentNode()).thenReturn(node); when(parent.getSessionId()).thenReturn("parent");
        when(ssh.command(any(), any(), any(), any())).thenReturn(Map.of("code", 200));
        String id = java.util.UUID.randomUUID().toString();
        var savedRequest = new SshProfileConnectRequest("parent", "process", id, 1L, null);
        when(profiles.getForConnection("owner", parent, savedRequest.toCommand())).thenReturn(Map.of("host", "host", "port", 22, "fingerprint", request().fingerprint()));
        when(profiles.resolveConnection("owner", parent, savedRequest.toCommand())).thenReturn(request());
        try (var util = mockStatic(ControllerUtil.class); var audit = mockStatic(AuditLogUtil.class)) {
            util.when(() -> ControllerUtil.getAgentNodeSession("parent")).thenReturn(parent);
            util.when(() -> ControllerUtil.getCurrentUser(http)).thenReturn(owner);
            controller.connectProfile(savedRequest, http);
            audit.verify(() -> AuditLogUtil.logSuccess(eq(node), eq("COMMAND_INIT"), eq("SSH 终端"), eq("process"),
                    argThat(params -> params.size() == 8 && params.get("connectionType").equals("ssh") && !params.toString().contains("secret")),
                    eq(200), eq("操作成功"), isNull()));
            audit.clearInvocations();
            controller.command(new CommandExecRequest("parent", "typed-password", "write", "process"), http);
            audit.verifyNoInteractions();
            controller.command(new CommandExecRequest("parent", "", "stop", "process"), http);
            audit.verify(() -> AuditLogUtil.logSuccess(eq(node), eq("COMMAND_STOP"), eq("SSH 终端"), eq("process"),
                    argThat(params -> params.size() == 3 && !params.toString().contains("typed-password")),
                    eq(200), eq("操作成功"), isNull()));
        }
    }
}
