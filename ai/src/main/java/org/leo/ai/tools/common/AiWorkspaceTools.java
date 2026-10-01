package org.leo.ai.tools.common;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolKind;
import org.leo.ai.agent.AiToolOperation;
import org.leo.ai.agent.AiToolPolicy;
import org.leo.ai.service.workspace.AiWorkspaceService;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Platform AI/Node AI 共用的任务级本地工作空间工具。 */
@Component
public class AiWorkspaceTools {

    private final AiWorkspaceService service;

    public AiWorkspaceTools(AiWorkspaceService service) {
        this.service = service;
    }

    @Tool("列出当前 Agent 任务工作空间中的文件。这里是平台侧持久化草稿区，不是 Agent 目标主机文件系统。")
    @AiToolPolicy(kind = AiToolKind.CONTEXT, operation = AiToolOperation.READ_ONLY,
            parallelizable = true)
    public Map<String, Object> workspaceList(
            @P("相对工作空间的目录，根目录用空字符串") String path,
            @P("递归深度，1-8") int depth,
            @P("最多返回条目数，最大1000") int maxEntries) {
        var workspace = service.workspaceFromContext();
        return service.list(workspace, path, depth, maxEntries);
    }

    @Tool("按行读取当前 AI 工作空间中的 UTF-8 文本，同时返回 size、sha256 和路径元数据。适合修改前校验并只把相关片段送入上下文。")
    @AiToolPolicy(kind = AiToolKind.CONTEXT, operation = AiToolOperation.READ_ONLY,
            parallelizable = true)
    public Map<String, Object> workspaceReadText(
            @P("相对工作空间文件路径") String path,
            @P("起始行，从1开始") int startLine,
            @P("最多行数，最大2000，仍受字符上限约束") int maxLines) {
        var workspace = service.workspaceFromContext();
        return service.readText(workspace, path, startLine, maxLines);
    }

    @Tool("在当前 AI 工作空间中搜索文本。先搜索定位，再分段读取和打补丁，避免把整个大文件放入上下文。")
    @AiToolPolicy(kind = AiToolKind.CONTEXT, operation = AiToolOperation.READ_ONLY,
            parallelizable = true)
    public Map<String, Object> workspaceSearch(
            @P("普通字符串或正则表达式") String query,
            @P("搜索起始相对目录；根目录用空字符串") String path,
            @P("可选 glob，例如 **/*.java；不用时传空字符串") String glob,
            @P("query 是否为正则") boolean regex,
            @P("最多返回结果数") int maxResults) {
        var workspace = service.workspaceFromContext();
        return service.search(workspace, query, path, glob, regex, maxResults);
    }

    @Tool("新建或小范围覆写当前 AI 工作空间 UTF-8 文件。覆盖现有文件必须传 workspaceReadText 返回的 sha256；单次大内容请改用脚本或补丁。")
    @AiToolPolicy(kind = AiToolKind.ARTIFACT, operation = AiToolOperation.WRITE,
            business = false)
    public Map<String, Object> workspaceWriteText(
            @P("相对工作空间文件路径") String path,
            @P("完整 UTF-8 内容") String content,
            @P("覆盖时的旧文件 sha256；新建传空字符串") String expectedSha256) {
        var workspace = service.workspaceFromContext();
        return service.writeText(workspace, path, content, expectedSha256);
    }

    @Tool("给当前 AI 工作空间中的现有 UTF-8 文件应用单文件 unified diff。必须提供读取时的 sha256，以防并发覆盖。")
    @AiToolPolicy(kind = AiToolKind.ARTIFACT, operation = AiToolOperation.WRITE,
            business = false)
    public Map<String, Object> workspaceApplyPatch(
            @P("相对工作空间文件路径") String path,
            @P("包含 @@ hunk 的单文件 unified diff") String patch,
            @P("修改前文件 sha256") String expectedSha256) {
        var workspace = service.workspaceFromContext();
        return service.applyPatch(workspace, path, patch, expectedSha256);
    }

    @Tool("将当前 AI 工作空间中的文件复制到 output 目录作为最终制品，返回用户文件空间路径。")
    @AiToolPolicy(kind = AiToolKind.ARTIFACT, operation = AiToolOperation.WRITE,
            business = false)
    public Map<String, Object> workspacePromote(
            @P("源文件相对路径") String sourcePath,
            @P("output 下的相对路径；留空沿用文件名") String outputPath) {
        var workspace = service.workspaceFromContext();
        return service.promote(workspace, sourcePath, outputPath);
    }

    @Tool("把当前 Agent 隔离工作空间路径移入可恢复回收区。删除普通文件必须提供当前 sha256；"
            + "这是可恢复的内部清理，不影响 Agent 业务状态，不需要用户确认。")
    @AiToolPolicy(kind = AiToolKind.ARTIFACT, operation = AiToolOperation.DESTRUCTIVE,
            exclusive = true, business = false)
    public Map<String, Object> workspaceDelete(
            @P("相对工作空间路径") String path,
            @P("普通文件当前 sha256；目录传空字符串") String expectedSha256) {
        var workspace = service.workspaceFromContext();
        return service.delete(workspace, path, expectedSha256);
    }
}
