#!/usr/bin/env python3
"""Enforce the backend module DAG and keep application code independent of HTTP adapters."""

import argparse
from collections import defaultdict
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
IDENTIFIER = r'[A-Za-z_$][\w$]*'
QUALIFIED_NAME = IDENTIFIER + r'(?:\s*\.\s*' + IDENTIFIER + r')*'
PACKAGE = re.compile(r'\bpackage\s+(' + QUALIFIED_NAME + r')\s*;')
IMPORT = re.compile(r'\bimport\s+(?:static\s+)?(' + QUALIFIED_NAME + r')(\s*\.\s*\*)?\s*;')
REFERENCE = re.compile(r'(?<![\w$])' + IDENTIFIER + r'(?:\s*\.\s*' + IDENTIFIER + r')+(?![\w$])')
TYPE_TOKEN = re.compile(r'([{}])|\b(?:class|interface|enum|record)\s+(' + IDENTIFIER + r')')


def mask(match):
    """Ignore non-code without merging Java tokens or changing source locations."""
    return re.sub(r'[^\n]', ' ', match[0])


def qualified_name(value):
    return re.sub(r'\s+', '', value)


def source_index(root):
    """Index physical ownership, including known modules outside a partial reactor."""
    sources = defaultdict(list)
    definitions = defaultdict(list)
    packages = defaultdict(set)
    for module in ALLOWED_DEPENDENCIES:
        for source in sorted((root / module / 'src/main/java').rglob('*.java')):
            code = NON_CODE.sub(mask, source.read_text(encoding='utf-8'))
            sources[module].append((source, code))
            declaration = PACKAGE.search(code)
            package = qualified_name(declaration[1]) if declaration else ''
            depth = 0
            for token in TYPE_TOKEN.finditer(code):
                if token[1]:
                    depth += 1 if token[1] == '{' else -1
                elif depth == 0:
                    name = package + '.' + token[2] if package else token[2]
                    definitions[name].append((module, source.relative_to(root).as_posix()))
                    packages[package].add(module)
    owners = {name: {module for module, _ in entries}
              for name, entries in definitions.items()}
    issues = ['Duplicate production type: ' + name + ': '
              + ', '.join(path for _, path in entries)
              for name, entries in sorted(definitions.items()) if len(entries) > 1]
    return sources, owners, packages, issues


def type_modules(name, owners):
    # Static members and nested types inherit their enclosing top-level type's module.
    while name:
        if name in owners:
            return owners[name]
        name = name.rpartition('.')[0]
    return set()


def import_modules(name, wildcard, owners, packages):
    targets = type_modules(name, owners)
    if not targets and wildcard:
        # Java package wildcards are not recursive. Mixed packages are checked
        # conservatively; explicit contract imports avoid runtime coupling.
        targets = packages.get(name, set())
    if targets:
        return targets
    # Keep namespace checks when a partial checkout has no source for an import.
    parts = name.split('.')
    if len(parts) > 2 and parts[:2] == ['org', 'leo'] and parts[2] in ALLOWED_DEPENDENCIES:
        return {parts[2]}
    return set()


def matches_boundary(name, prefix):
    direct = name.startswith(prefix) if prefix.endswith('.') else name == prefix or name.startswith(prefix + '.')
    return direct or (name.endswith('.*') and name[:-2] == prefix.rpartition('.')[0])


def boundary_reason(module, relative_source, name, targets, kind):
    forbidden = targets - {module} - ALLOWED_DEPENDENCIES[module]
    reason = None
    if forbidden:
        reason = f'forbidden upward {kind}: {name} (module: {", ".join(sorted(forbidden))})'
    if module == 'service' and any(matches_boundary(name, prefix) for prefix in
                                   ('jakarta.servlet.', 'javax.servlet.', 'org.springframework.web.bind.annotation.')):
        reason = 'application service imports HTTP server API: ' + name
    if any(relative_source.startswith(package) and any(matches_boundary(name, prefix) for prefix in prefixes)
           for package, prefixes in ADAPTER_IMPORTS.items()):
        reason = 'application service imports adapter implementation: ' + name
    return reason


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
    sources, owners, packages, source_issues = source_index(root)
    issues.extend(source_issues)
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
        for source, code in sources[module]:
            relative_source = source.relative_to(root).as_posix()
            for match in IMPORT.finditer(code):
                name = qualified_name(match[1])
                targets = import_modules(name, bool(match[2]), owners, packages)
                reason = boundary_reason(module, relative_source,
                                         name + '.*' if match[2] else name, targets, 'import')
                if reason:
                    line = code.count('\n', 0, match.start()) + 1
                    issues.append(f'{relative_source}:{line}: {reason}')
            references = IMPORT.sub(mask, PACKAGE.sub(mask, code))
            for match in REFERENCE.finditer(references):
                name = qualified_name(match[0])
                reason = boundary_reason(module, relative_source, name,
                                         type_modules(name, owners), 'reference')
                if reason:
                    line = code.count('\n', 0, match.start()) + 1
                    issues.append(f'{relative_source}:{line}: {reason}')
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
