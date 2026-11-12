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

# Package-wide boundaries only; class internals are reviewed and tested by behavior.
ADAPTER_IMPORTS = {
    'service/src/main/java/org/leo/service/ssh/': ('net.schmizz.', 'org.apache.sshd.'),
    'service/src/main/java/org/leo/service/discovery/': (
        'java.sql.', 'javax.sql.', 'org.springframework.jdbc.',
        'org.leo.core.session.AgentNodeSessionContainer'),
}
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
                if any(relative_source.startswith(package) and name.startswith(prefixes)
                       for package, prefixes in ADAPTER_IMPORTS.items()):
                    reason = 'application service imports adapter implementation: ' + name
                if reason:
                    line = code.count('\n', 0, match.start()) + 1
                    issues.append(f'{source.relative_to(root)}:{line}: {reason}')
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
