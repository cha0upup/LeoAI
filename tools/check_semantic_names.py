#!/usr/bin/env python3
"""Check established first-party semantic names without rewriting source."""

import argparse
from pathlib import Path
import re

MODULES = ('core', 'dao', 'service', 'ai', 'web', 'javacore', 'phpcore', 'jmg')
COMMON_NAMES = {
    'getAllAgent': 'getAllAgents',
    'findAgentByParentAgentId': 'findAgentsByParentAgentId',
    'findAgentByPermission': 'findAgentsByPermission',
    'getAllUser': 'getAllUsers',
    'findUserByTeamId': 'findUsersByTeamId',
    'getUserByTeamId': 'getUsersByTeamId',
    'getAllTeam': 'getAllTeams',
    'getAllSession': 'getAllSessions',
    'getAllController': 'getAllControllers',
    'getAllMappedInterceptor': 'getAllMappedInterceptors',
    'getAllFilter': 'getAllFilters',
    'getAllServlet': 'getAllServlets',
    'getAllValve': 'getAllValves',
    'getAllListener': 'getAllListeners',
    'findTeamByLeader': 'findTeamsByLeaderId',
    'getPluginAsList': 'getPlugins',
    'getPluginAsListByType': 'getPluginsByType',
    'getDisguiseAsList': 'getDisguises',
    'AgentConnService': 'AgentConnectionService',
    'TestConnService': 'ConnectionTestService',
    'AgentNodeSQLController': 'AgentNodeSqlController',
    'PARAM_AGENT_ID_LOWER': 'PARAM_AGENT_ID',
    'isWindowsAgent': 'isWindowsHost',
}
FRONTEND_NAMES = {
    'useAiAgentConfigTest': 'useAgentConnectionTest',
    'testAiAgentConfigApi': 'testAgentConnectionConfigApi',
    'testAgentConnApi': 'testAgentConnectionApi',
    'AgentEntity': 'AgentConsoleView',
    'AgentEntityParams': 'agentConsoleParams',
    'agentEntityParams': 'agentConsoleParams',
    'addAgentEntity': 'openAgentConsole / addAgentConsoleTab',
    'allAgent': 'allAgents',
    'parasiticAgent': 'openAddChildAgent',
    'parasite': 'add-child-agent / childAgent',
    'hostgen': 'script-generator',
    'GenShell': 'generateArtifact',
    'getAllDisguise': 'loadDisguises',
}
AI_NAMES = {
    'MainAiAgentConfig': 'AiExecutionSettings',
    'AgentNodeConfig': 'NodeAiSettings',
    'agentProps': 'aiAgentProperties',
}


def source_files(root, frontend=False):
    roots = [root / 'src'] if frontend else [root / module / 'src' for module in MODULES]
    for base in roots:
        for path in sorted(base.rglob('*')):
            if not path.is_file():
                continue
            relative = path.relative_to(root).as_posix()
            if '/static/' in relative or relative.startswith('jmg/src/main/java/com/'):
                continue
            if path.suffix in {'.java', '.php', '.js', '.vue', '.ts'} or path.name.endswith('.php.txt'):
                yield path


def audit(root, frontend=False):
    root = Path(root)
    count, issues = 0, []
    for path in source_files(root, frontend):
        count += 1
        relative = path.relative_to(root).as_posix()
        names = {**COMMON_NAMES, **(FRONTEND_NAMES if frontend else {})}
        if not frontend and relative.startswith('ai/src/'):
            names.update(AI_NAMES)
        if path.name in {'NodeAiTurnService.java', 'NodeAiDelegationService.java', 'PlatformAiTurnService.java'}:
            names['agentRuntime'] = 'aiRuntime'
        pattern = re.compile(r'\b(?:' + '|'.join(re.escape(name) for name in names) + r')\b')
        for line_number, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
            for match in pattern.finditer(line):
                old = match.group()
                issues.append((relative, line_number, old, names[old]))
    return count, issues


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--frontend', type=Path)
    args = parser.parse_args()
    targets = [('backend', args.backend, False)]
    if args.frontend is not None:
        targets.append(('frontend', args.frontend, True))
    issue_count = 0
    for label, root, frontend in targets:
        if not root.is_dir():
            parser.error('Project directory does not exist: ' + str(root))
        count, issues = audit(root, frontend)
        print(label + ': ' + str(count) + ' source files, ' + str(len(issues)) + ' naming issues')
        for path, line, old, new in issues:
            print('  ' + path + ':' + str(line) + ' ' + old + ' -> ' + new)
        issue_count += len(issues)
    return 1 if issue_count else 0


if __name__ == '__main__':
    raise SystemExit(main())
