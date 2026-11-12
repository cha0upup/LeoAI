#!/usr/bin/env python3
"""Enforce the backend module DAG and keep application code independent of HTTP adapters."""

import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET


ALLOWED_DEPENDENCIES = {
    'core': set(),
    'dao': {'core'},
    'javacore': {'core'},
    'phpcore': {'core'},
    'jmg': {'core'},
    'service': {'core', 'dao', 'jmg'},
    'ai': {'core', 'dao', 'service', 'jmg'},
    'web': {'core', 'dao', 'javacore', 'phpcore', 'service', 'ai', 'jmg'},
}
TEST_DEPENDENCIES = {'service': {'javacore', 'phpcore'}}

# Package boundaries end in '/'; file boundaries match exactly. Adding a role
# changes policy data, rather than adding another branch to the import scanner.
SOURCE_IMPORT_BOUNDARIES = (
    {
        'sources': ('service/src/main/java/org/leo/service/ssh/',),
        'prefixes': ('net.schmizz.', 'org.apache.sshd.'),
        'message': 'SSH application imports transport implementation',
    },
    {
        'sources': ('service/src/main/java/org/leo/service/discovery/',),
        'prefixes': ('java.sql.', 'javax.sql.', 'org.springframework.jdbc.'),
        'message': 'discovery application imports JDBC implementation',
    },
    {
        'sources': ('service/src/main/java/org/leo/service/discovery/',),
        'names': ('org.leo.core.session.AgentNodeSessionContainer',),
        'message': 'discovery application imports global session registry',
    },
    {
        'sources': ('service/src/main/java/org/leo/service/sql/SqlExportService.java',
                    'service/src/main/java/org/leo/service/sql/SqlTableCsvExporter.java'),
        'prefixes': ('java.nio.file.Files', 'java.io.File', 'java.io.OutputStream', 'java.util.zip.',
                     'org.leo.core.util.json.JsonUtil', 'org.leo.core.util.session.AgentNodeSessionWorkDirUtil',
                     'org.leo.service.sql.FileSqlExport', 'org.leo.service.sql.SqlExportWorkspace'),
        'message': 'SQL export application imports filesystem implementation',
    },
    {
        'sources': ('service/src/main/java/org/leo/service/sql/SqlTableCsvExporter.java',),
        'names': ('org.leo.service.concurrent.ServiceTaskExecutor',),
        'message': 'CSV content exporter imports task scheduling',
    },
    {
        'sources': ('service/src/main/java/org/leo/service/transfer/UploadEngineService.java',),
        'prefixes': ('org.leo.core.config.', 'org.leo.service.vfs.', 'java.io.FileInputStream',
                     'java.io.InputStream', 'org.springframework.util.DigestUtils'),
        'message': 'upload coordinator imports VFS policy or protocol I/O',
    },
    {
        'sources': ('service/src/main/java/org/leo/service/transfer/UploadTask.java',),
        'prefixes': ('org.leo.service.concurrent.', 'org.leo.core.config.', 'org.leo.core.session.',
                     'org.springframework.stereotype.', 'java.util.concurrent.Executor',
                     'java.util.concurrent.ThreadPoolExecutor'),
        'message': 'upload task imports scheduling or application infrastructure',
    },
    {
        'sources': ('service/src/main/java/org/leo/service/vfs/',),
        'prefixes': ('org.leo.service.transfer.',),
        'message': 'VFS access depends on transfer scheduling',
    },
    {
        'sources': ('service/src/main/java/org/leo/service/discovery/NetworkProbeWorkflowPlan.java',
                    'service/src/main/java/org/leo/service/discovery/NetworkProbeStageResults.java'),
        'prefixes': ('org.springframework.', 'java.util.concurrent.', 'java.io.', 'java.nio.file.',
                     'org.leo.core.agent.', 'org.leo.core.session.', 'org.leo.service.concurrent.'),
        'message': 'discovery plan or projection imports execution infrastructure',
    },
)
# Same-package references need no import, so guard them in code as well.
SOURCE_CODE_BOUNDARIES = (
    {
        'sources': ('service/src/main/java/org/leo/service/discovery/NetworkProbeWorkflowPlan.java',
                    'service/src/main/java/org/leo/service/discovery/NetworkProbeStageResults.java'),
        'pattern': r'\bNetworkProbe(?:WorkflowService|OrchestrationService|AnalysisService|Scheduler|ResultRepository)\b',
        'message': 'discovery plan or projection depends on execution infrastructure',
    },
)
NON_CODE = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/')
IMPORT = re.compile(r'\bimport\s+(?:static\s+)?([\w.]+)(?:\.\*)?\s*;')


def dependency_cycles(graph):
    issues, visited, active = [], set(), []

    def visit(module):
        if module in active:
            issues.append('Module dependency cycle: ' + ' -> '.join(active[active.index(module):] + [module]))
            return
        if module in visited:
            return
        visited.add(module)
        active.append(module)
        for target in sorted(graph.get(module, ())):
            visit(target)
        active.pop()

    for module in sorted(graph):
        visit(module)
    return issues


def boundary_applies(boundary, source):
    return any(source == path or (path.endswith('/') and source.startswith(path))
               for path in boundary['sources'])


def source_import_violation(source, name):
    for boundary in SOURCE_IMPORT_BOUNDARIES:
        if boundary_applies(boundary, source) and (name in boundary.get('names', ())
                        or name.startswith(boundary.get('prefixes', ()))):
            return boundary['message'] + ': ' + name
    return None


def audit(root):
    root = Path(root)
    issues = []
    try:
        pom = ET.parse(root / 'pom.xml').getroot()
        modules = [item.text.strip() for item in pom.findall('{*}modules/{*}module')]
    except (OSError, ET.ParseError) as error:
        return ['Invalid root module descriptor: ' + str(error)]
    graph = {module: set() for module in modules}
    for module in modules:
        if module not in ALLOWED_DEPENDENCIES:
            issues.append('Module has no dependency policy: ' + module)
            continue
        try:
            child = ET.parse(root / module / 'pom.xml').getroot()
        except (OSError, ET.ParseError) as error:
            issues.append(module + ': invalid module descriptor: ' + str(error))
            continue
        for dependency in child.findall('{*}dependencies/{*}dependency'):
            if dependency.findtext('{*}groupId') != 'org.leo':
                continue
            target = dependency.findtext('{*}artifactId')
            if target not in ALLOWED_DEPENDENCIES:
                continue
            scope = dependency.findtext('{*}scope') or 'compile'
            graph[module].add(target)
            allowed = ALLOWED_DEPENDENCIES[module]
            if scope == 'test':
                allowed = allowed | TEST_DEPENDENCIES.get(module, set())
            if target not in allowed:
                issues.append(f'{module}/pom.xml: forbidden {scope} dependency on {target}')
        for source in sorted((root / module / 'src/main/java').rglob('*.java')):
            relative_source = source.relative_to(root).as_posix()
            code = NON_CODE.sub(lambda match: '\n' * match[0].count('\n'), source.read_text(encoding='utf-8'))
            for match in IMPORT.finditer(code):
                name = match[1]
                target = name.split('.')[2] if name.startswith('org.leo.') else None
                reason = None
                if target in ALLOWED_DEPENDENCIES and target != module and target not in ALLOWED_DEPENDENCIES[module]:
                    reason = 'forbidden upward import: ' + name
                if module == 'service' and name.startswith(('jakarta.servlet.', 'javax.servlet.', 'org.springframework.web.bind.annotation.')):
                    reason = 'application service imports HTTP server API: ' + name
                reason = source_import_violation(relative_source, name) or reason
                if reason:
                    line = code.count('\n', 0, match.start()) + 1
                    issues.append(f'{source.relative_to(root)}:{line}: {reason}')
            if relative_source == 'service/src/main/java/org/leo/service/transfer/UploadEngineService.java' and re.search(r'\bclass\s+UploadTask\b', code):
                issues.append(f'{source.relative_to(root)}: upload protocol execution belongs in UploadTask, not the coordinator')
            for boundary in SOURCE_CODE_BOUNDARIES:
                if boundary_applies(boundary, relative_source):
                    for match in re.finditer(boundary['pattern'], code):
                        line = code.count('\n', 0, match.start()) + 1
                        issues.append(f"{relative_source}:{line}: {boundary['message']}: {match[0]}")
    return issues + dependency_cycles(graph)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend', type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)
    issues = audit(args.backend.resolve())
    for issue in issues:
        print(issue)
    print(f'Backend architecture: {len(issues)} violations')
    return 1 if issues else 0


if __name__ == '__main__':
    raise SystemExit(main())
