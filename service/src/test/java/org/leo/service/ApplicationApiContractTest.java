package org.leo.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.leo.service.discovery.NetworkProbeOrchestrationService;
import org.leo.service.discovery.NetworkProbeResultRepository;
import org.leo.service.discovery.NetworkProbeWorkflowService;
import org.leo.service.sql.SqlExportArtifacts;
import org.leo.service.sql.SqlExportTaskRepository;
import org.leo.service.ssh.SshConnectionManagementService;
import org.leo.service.ssh.SshConnectionOperations;
import org.leo.service.ssh.SshConnectionProfiles;
import org.leo.service.ssh.SshConnectionSpec;
import org.leo.service.ssh.SshProfileConnectCommand;
import org.leo.service.ssh.SshProfileSaveCommand;
import org.leo.service.ssh.SshTransport;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ApplicationApiContractTest {
    @ParameterizedTest(name = "{0} public API keeps adapter types private")
    @MethodSource("contracts")
    void keepsAdapterAndExecutionDetailsOutOfPublicContracts(
            String domain, List<Class<?>> types, List<String> forbiddenTypes) {
        for (Class<?> type : types) {
            Arrays.stream(type.getDeclaredMethods()).filter(method -> Modifier.isPublic(method.getModifiers()))
                    .forEach(method -> {
                        String signature = method.toGenericString();
                        for (String forbidden : forbiddenTypes) {
                            assertFalse(signature.contains(forbidden), signature);
                        }
                    });
        }
    }

    private static Stream<Arguments> contracts() {
        return Stream.of(
                Arguments.of("discovery", List.of(NetworkProbeWorkflowService.class,
                        NetworkProbeOrchestrationService.class, NetworkProbeResultRepository.class),
                        List.of("org.leo.web.", "jakarta.servlet.", "java.sql.", "javax.sql.")),
                Arguments.of("SQL export", List.of(SqlExportArtifacts.class, SqlExportTaskRepository.class),
                        List.of("org.leo.web.", "SqlCapable", "ServiceTaskExecutor", "TaskControl")),
                Arguments.of("SSH", List.of(SshConnectionOperations.class, SshConnectionProfiles.class,
                        SshConnectionManagementService.class, SshTransport.class,
                        SshProfileSaveCommand.class, SshProfileConnectCommand.class, SshConnectionSpec.class),
                        List.of("org.leo.web.", "net.schmizz.", "jakarta.servlet.")));
    }
}
