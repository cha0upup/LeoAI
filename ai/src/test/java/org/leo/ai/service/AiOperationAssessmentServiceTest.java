package org.leo.ai.service;

import com.alibaba.fastjson.JSON;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.agent.AiToolCatalog;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolException;
import org.leo.ai.agent.AiToolErrorHandler;
import org.leo.ai.agent.AiToolKind;
import org.leo.ai.agent.AiToolOperation;
import org.leo.ai.agent.AiToolPolicy;
import org.leo.ai.tools.common.OperationAssessmentTools;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiOperationAssessment;
import org.leo.ai.thread.AiOperationAssessmentRepository;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AiOperationAssessmentServiceTest {

    private final AiOperationAssessmentRepository repository = mock(AiOperationAssessmentRepository.class);

    @AfterEach
    void cleanup() {
        AiToolContext.clear();
    }

    @Test
    void assessmentBindsExactArgumentsAndCanOnlyBeConsumedOnce() {
        AiToolCatalog catalog = catalog(new MutationTools());
        AiOperationAssessmentService service =
                new AiOperationAssessmentService(catalog, repository);
        bindContext();

        service.assess("platform-thread", "exec",
                "{\"cmd\":\"whoami\",\"timeout\":0}",
                "LOW", false, "只读取当前用户", null, null);

        ArgumentCaptor<AiOperationAssessment> created = ArgumentCaptor.forClass(AiOperationAssessment.class);
        verify(repository).create(created.capture());
        AiOperationAssessment row = created.getValue();
        assertEquals("user-1", row.getUserId());
        assertEquals("platform-thread", row.getThreadId());
        assertEquals("PENDING", row.getStatus());
        assertEquals("{\"cmd\":\"whoami\",\"timeout\":0}", row.getArgumentsJson());
        when(repository.findPending("user-1", "platform-thread", "exec", row.getArgumentsHash()))
                .thenReturn(row, null);
        when(repository.consume(eq(row.getAssessmentId()), anyLong())).thenReturn(true, false);

        AiOperationAssessmentService.Assessment assessment =
                service.find("platform-thread", "exec",
                        "{\"timeout\":0,\"cmd\":\"whoami\"}");
        assertNotNull(assessment);
        assertFalse(assessment.requiresConfirmation());
        assertNull(service.find("platform-thread", "exec",
                "{\"cmd\":\"systemctl restart app\",\"timeout\":0}"));
        assertTrue(service.consume(assessment));
        assertFalse(service.consume(assessment));
        assertNull(service.find("platform-thread", "exec",
                "{\"cmd\":\"whoami\",\"timeout\":0}"));
        verify(repository, times(2)).consume(eq(row.getAssessmentId()), anyLong());
    }

    @Test
    void refusesAssessmentForReadOnlyAndInternalTools() {
        AiToolCatalog catalog = catalog(new MutationTools(), new ReadTools(),
                new InternalTools());
        AiOperationAssessmentService service =
                new AiOperationAssessmentService(catalog, repository);
        bindContext();

        assertThrows(RuntimeException.class, () -> service.assess(
                "platform-thread", "readFile", "{\"path\":\"/etc/passwd\"}",
                "LOW", false, "只读", null, null));
        assertThrows(RuntimeException.class, () -> service.assess(
                "platform-thread", "updatePlan", "{}",
                "LOW", false, "内部控制", null, null));
    }

    @Test
    void refusesHighRiskAssessmentWithoutConfirmation() {
        AiToolCatalog catalog = catalog(new MutationTools());
        AiOperationAssessmentService service = new AiOperationAssessmentService(catalog, repository);
        bindContext();

        assertThrows(RuntimeException.class, () -> service.assess(
                "platform-thread", "exec", "{\"cmd\":\"rm -f data\"}",
                "CRITICAL", false, "不可逆删除", "数据丢失", null));
    }

    @Test
    void reportsMalformedNestedArgumentsJsonAsModelCorrectable() {
        AiToolCatalog catalog = catalog(new MutationTools());
        AiOperationAssessmentService service = new AiOperationAssessmentService(catalog, repository);
        bindContext();

        AiToolException error = assertThrows(AiToolException.class, () -> service.assess(
                "platform-thread", "exec", "{\"cmd\":\"grep -vE '\\. |#'\"}",
                "LOW", false, "只读命令", null, null));

        assertEquals("INVALID_OPERATION_ARGUMENTS_JSON", error.code());
        assertEquals(AiToolException.Recovery.MODEL, error.recovery());
    }

    @Test
    void malformedNestedArgumentsJsonReturnsRetryableToolResult() {
        AiToolCatalog catalog = catalog(new MutationTools());
        AiOperationAssessmentService service = new AiOperationAssessmentService(catalog, repository);
        bindContext();
        AiServiceTool tool = ToolService.findTools(new OperationAssessmentTools(service)).get(0);
        String outerArguments = JSON.toJSONString(java.util.Map.of(
                "toolName", "exec",
                "argumentsJson", "{\"cmd\":\"grep -vE '\\. |#'\"}",
                "riskLevel", "LOW",
                "requiresConfirmation", false,
                "reason", "只读命令",
                "impact", "无",
                "rollback", "不适用"));
        AiToolErrorHandler errors = new AiToolErrorHandler();

        ToolExecutionResult result = ToolService.executeWithErrorHandling(
                ToolExecutionRequest.builder().id("call-assess").name("assess_operation")
                        .arguments(outerArguments).build(),
                tool.toolExecutor(),
                InvocationContext.builder().chatMemoryId("platform-thread").build(),
                errors::handleArguments, errors::handleExecution);

        assertTrue(result.isError());
        assertTrue(result.resultText().contains("\"code\":\"INVALID_OPERATION_ARGUMENTS_JSON\""));
        assertTrue(result.resultText().contains("\"retryable\":true"));
    }

    private static AiToolCatalog catalog(Object... sources) {
        AiToolCatalog catalog = new AiToolCatalog();
        for (Object source : sources) {
            ToolService.findTools(source).forEach(tool -> catalog.register(source, tool));
        }
        return catalog;
    }

    private static void bindContext() {
        AiToolContext.setFromMemoryId("platform-thread");
        AiExecutionPolicy policy = new AiExecutionPolicy();
        policy.setUserId("user-1");
        policy.setPrivilege("normal");
        AiToolContext.setExecutionPolicy(policy);
    }

    private static class MutationTools {
        @Tool
        @AiToolPolicy(kind = AiToolKind.COMMAND, operation = AiToolOperation.WRITE)
        public String exec(String cmd, int timeout) {
            return cmd;
        }
    }

    private static class ReadTools {
        @Tool
        @AiToolPolicy(kind = AiToolKind.QUERY, operation = AiToolOperation.READ_ONLY)
        public String readFile(String path) {
            return path;
        }
    }

    private static class InternalTools {
        @Tool
        @AiToolPolicy(kind = AiToolKind.CONTROL, operation = AiToolOperation.WRITE,
                business = false)
        public String updatePlan() {
            return "ok";
        }
    }
}
