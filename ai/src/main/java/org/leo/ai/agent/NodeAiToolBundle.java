package org.leo.ai.agent;

import org.leo.ai.tools.platform.SkillActivationTools;
import org.leo.ai.tools.common.UserInputTools;
import org.leo.ai.tools.common.AiWorkspaceCommandTools;
import org.leo.ai.tools.common.AiWorkspaceTools;
import org.leo.ai.tools.common.WebResearchTools;
import org.leo.ai.tools.agentnode.BasicInfoTools;
import org.leo.ai.tools.agentnode.CommandTools;
import org.leo.ai.tools.agentnode.CredentialHarvestTools;
import org.leo.ai.tools.agentnode.DatabaseConnectionTools;
import org.leo.ai.tools.agentnode.FileTools;
import org.leo.ai.tools.agentnode.HttpRequestTools;
import org.leo.ai.tools.agentnode.JavaPluginTools;
import org.leo.ai.tools.common.PlanTools;
import org.leo.ai.tools.agentnode.ResourceTools;
import org.leo.ai.tools.agentnode.ReverseTunnelTools;
import org.leo.ai.tools.agentnode.ScanTools;
import org.leo.ai.tools.agentnode.ScriptTools;
import org.leo.ai.tools.agentnode.SqlTools;
import org.leo.ai.tools.agentnode.WebRuntimeTools;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/** Agent Node Agent 的完整、不可变工具配置。 */
@Component
public class NodeAiToolBundle {

    private final List<Object> tools;

    public NodeAiToolBundle(
            CommandTools commandTools,
            BasicInfoTools basicInfoTools,
            ReverseTunnelTools reverseTunnelTools,
            FileTools fileTools,
            ScanTools scanTools,
            CredentialHarvestTools credentialHarvestTools,
            DatabaseConnectionTools databaseConnectionTools,
            WebRuntimeTools webRuntimeTools,
            JavaPluginTools javaPluginTools,
            HttpRequestTools httpRequestTools,
            ScriptTools scriptTools,
            SqlTools sqlTools,
            ResourceTools resourceTools,
            AiWorkspaceTools aiWorkspaceTools,
            AiWorkspaceCommandTools aiWorkspaceCommandTools,
            WebResearchTools webResearchTools,
            PlanTools planTools,
            UserInputTools userInputTools,
            @Qualifier("agentNodeSkillActivationTools") SkillActivationTools skillActivationTools) {
        this.tools = List.of(
                commandTools, basicInfoTools, reverseTunnelTools,
                fileTools, scanTools,
                credentialHarvestTools, databaseConnectionTools,
                webRuntimeTools, javaPluginTools,
                httpRequestTools, scriptTools, sqlTools, resourceTools,
                aiWorkspaceTools, aiWorkspaceCommandTools,
                webResearchTools, planTools, userInputTools,
                skillActivationTools);
    }

    public List<Object> tools() {
        return tools;
    }
}
