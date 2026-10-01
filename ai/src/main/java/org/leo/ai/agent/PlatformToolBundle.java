package org.leo.ai.agent;

import org.leo.ai.tools.platform.DisguiseTools;
import org.leo.ai.tools.platform.FingerprintTools;
import org.leo.ai.tools.platform.PluginTools;
import org.leo.ai.tools.platform.AgentTools;
import org.leo.ai.tools.platform.ShellGeneratorTools;
import org.leo.ai.tools.platform.SkillActivationTools;
import org.leo.ai.tools.platform.TeamTools;
import org.leo.ai.tools.platform.UserTools;
import org.leo.ai.tools.common.UserInputTools;
import org.leo.ai.tools.common.PlanTools;
import org.leo.ai.tools.common.AiWorkspaceCommandTools;
import org.leo.ai.tools.common.AiWorkspaceTools;
import org.leo.ai.tools.common.WebResearchTools;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Platform Agent 的完整、不可变工具配置。 */
@Component
public class PlatformToolBundle {

    private final List<Object> tools;

    public PlatformToolBundle(
            AgentTools agentTools,
            UserTools userTools,
            TeamTools teamTools,
            PluginTools pluginTools,
            FingerprintTools fingerprintTools,
            DisguiseTools disguiseTools,
            ShellGeneratorTools shellGeneratorTools,
            AiWorkspaceTools aiWorkspaceTools,
            AiWorkspaceCommandTools aiWorkspaceCommandTools,
            WebResearchTools webResearchTools,
            PlanTools planTools,
            UserInputTools userInputTools,
            @Qualifier("platformSkillActivationTools") SkillActivationTools skillActivationTools) {
        this.tools = List.of(
                agentTools, userTools, teamTools,
                pluginTools, fingerprintTools, disguiseTools,
                shellGeneratorTools, aiWorkspaceTools, aiWorkspaceCommandTools,
                webResearchTools, planTools, userInputTools,
                skillActivationTools);
    }

    public List<Object> tools() {
        return tools;
    }

    public List<Object> toolsWith(Object additionalTools) {
        if (additionalTools == null) return tools;
        List<Object> combined = new ArrayList<>(tools.size() + 1);
        combined.addAll(tools);
        combined.add(additionalTools);
        return List.copyOf(combined);
    }
}
