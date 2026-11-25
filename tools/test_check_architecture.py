from pathlib import Path
import tempfile
import unittest

from check_architecture import audit


class ArchitectureAuditTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.write('pom.xml', '<project><modules><module>core</module>'
                   '<module>service</module><module>web</module></modules></project>')
        self.module('core')
        self.module('service', [('core', 'compile')])
        self.module('web', [('service', 'compile')])

    def write(self, name, source):
        target = self.root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source, encoding='utf-8')

    def module(self, name, dependencies=()):
        xml = ''.join(f'<dependency><groupId>org.leo</groupId><artifactId>{target}</artifactId>'
                      f'<scope>{scope}</scope></dependency>' for target, scope in dependencies)
        self.write(name + '/pom.xml', '<project><dependencies>' + xml + '</dependencies></project>')

    def production_type(self, module, name, body=''):
        package, _, simple_name = name.rpartition('.')
        self.write(f'{module}/src/main/java/{name.replace(".", "/")}.java',
                   f'package {package};\npublic class {simple_name} {{ {body} }}')

    def test_accepts_layered_dependencies_and_ignores_non_production_code(self):
        name = 'org.leo.web.Example'
        self.production_type('web', name)
        self.write('service/src/main/java/Example.java',
                   f'import org.leo.core.Example;\n/* new {name}(); */\n'
                   f'class Example {{ String text = "{name}"; '
                   f'String block = """\nimport {name};\nnew {name}();\n"""; }}')
        self.write('service/src/test/java/ExampleTest.java', f'import {name};')
        self.assertEqual([], audit(self.root))

    def test_rejects_upward_dependencies_and_cycles(self):
        self.module('service', [('web', 'compile')])
        issues = audit(self.root)
        self.assertTrue(any('forbidden compile dependency on web' in issue for issue in issues))
        self.assertTrue(any('cycle:' in issue for issue in issues))
        for module in ('service', 'ai'):
            with self.subTest(module=module):
                self.write('pom.xml', f'<project><modules><module>{module}</module></modules></project>')
                self.module(module, [('jmg', 'compile')])
                self.write(f'{module}/src/main/java/Example.java',
                           'import org.leo.jmg.generation.CoreArtifact;')
                issues = audit(self.root)
                self.assertEqual(2, len(issues), issues)
                self.assertTrue(any('forbidden compile dependency on jmg' in issue for issue in issues))
                self.assertTrue(any('forbidden upward import' in issue for issue in issues))

    def test_partial_reactors_keep_module_and_import_boundaries(self):
        self.write('pom.xml', '<project><modules><module>service</module></modules></project>')
        self.module('service', [('web', 'compile')])
        self.write('service/src/main/java/Example.java',
                   'import\norg.leo.web.Example;\nimport static org.leo.web.Example.run;')
        issues = audit(self.root)
        self.assertEqual(3, len(issues))
        self.assertEqual(2, sum('forbidden upward import' in issue for issue in issues))

    def test_service_adapter_implementations_stay_out(self):
        cases = {
            'org/leo/service/ssh/': ('net.schmizz.sshj.SSHClient', 'org.apache.sshd.client.SshClient',
                                    'net.schmizz.*', 'org.apache.sshd.*'),
            'org/leo/service/discovery/': ('java.sql.Connection', 'javax.sql.DataSource',
                 'org.springframework.jdbc.core.JdbcTemplate', 'org.leo.core.session.AgentNodeSessionContainer',
                 'java.sql.*', 'javax.sql.*', 'org.springframework.jdbc.*', 'org.leo.core.session.*'),
        }
        for package, imports in cases.items():
            for name in imports:
                with self.subTest(package=package, name=name):
                    source = f'import {name};'
                    path = 'service/src/main/java/' + package + 'Example.java'
                    self.write(path, source)
                    self.write('web/src/main/java/Example.java', source)
                    issues = audit(self.root)
                    self.assertEqual(1, len(issues), issues)
                    self.assertIn('application service imports', issues[0])
                    (self.root / path).unlink()

    def test_business_modules_keep_http_types_out_and_accept_stream_sources(self):
        cases = {
            'service': ('import org.springframework.web.multipart.MultipartFile;',
                        'import org.springframework.web.*;',
                        'class Example { jakarta.servlet.http.HttpServletRequest value; }'),
            'ai': ('import org.springframework.http.HttpStatus;', 'import javax.servlet.*;'),
        }
        for module, bodies in cases.items():
            self.write('pom.xml', f'<project><modules><module>{module}</module>'
                       '<module>web</module></modules></project>')
            self.module(module)
            for body in bodies:
                with self.subTest(module=module, body=body):
                    self.write(f'{module}/src/main/java/Example.java', f'package org.leo.{module};\n' + body)
                    self.write('web/src/main/java/Example.java', 'package org.leo.web;\n' + body)
                    issues = audit(self.root)
                    self.assertEqual(1, len(issues), issues)
                    self.assertIn('HTTP adapter API', issues[0])
            self.write(f'{module}/src/main/java/Example.java',
                       'import org.springframework.core.io.InputStreamSource;')
            self.assertEqual([], audit(self.root))

    def test_fully_qualified_adapter_references_observe_the_same_boundaries(self):
        cases = (
            ('', 'jakarta.servlet.http.HttpServletRequest'),
            ('org/leo/service/ssh/', 'net.schmizz.sshj.SSHClient'),
            ('org/leo/service/discovery/', 'org.springframework.jdbc.core.JdbcTemplate'),
            ('org/leo/service/discovery/', 'org.leo.core.session.AgentNodeSessionContainer'),
        )
        for package, name in cases:
            with self.subTest(package=package, name=name):
                path = 'service/src/main/java/' + package + 'Example.java'
                self.write(path, f'class Example {{ {name} value; }}')
                issues = audit(self.root)
                self.assertEqual(1, len(issues), issues)
                self.assertIn('application service imports', issues[0])
                (self.root / path).unlink()

    def test_adapter_boundaries_do_not_match_a_similarly_named_contract(self):
        name = 'org.leo.core.session.AgentNodeSessionContainerView'
        self.production_type('core', name)
        self.write('service/src/main/java/org/leo/service/discovery/Example.java',
                   f'import {name};\nclass Example {{ {name} value; }}')
        self.assertEqual([], audit(self.root))

    def test_web_application_keeps_adapter_and_composition_dependencies_out(self):
        bodies = ('import jakarta.servlet.http.HttpSession;',
                  'import org.springframework.web.*;', 'import org.springframework.http.HttpStatus;',
                  'import org.leo.web.controller.platform.ApplicationInfoController;',
                  'class Example { org.leo.web.util.ControllerUtil value; }',
                  'import org.leo.web.adapter.*;', 'import org.leo.web.config.*;',
                  'import org.leo.web.dto.platform.project.ProjectDtos;',
                  'import org.leo.web.exception.ApiException;')
        for body in bodies:
            with self.subTest(body=body):
                # Package boundaries follow the declaration, not the filename.
                self.write('web/src/main/java/Example.java', 'package org.leo.web.application.ai;\n' + body)
                issues = audit(self.root)
                self.assertEqual(1, len(issues), issues)
                self.assertIn('web application references adapter or composition', issues[0])
                self.write('web/src/main/java/Example.java', 'package org.leo.web.adapter.ai;\n' + body)
                self.assertEqual([], audit(self.root))

    def test_core_contracts_keep_infrastructure_out(self):
        body = 'import org.leo.core.infrastructure.transport.HttpCommunication;'
        for package in ('net', 'agent.capability', 'agent.database', 'runtime',
                        'generator', 'component.runtime', 'rpc'):
            with self.subTest(package=package):
                self.write('core/src/main/java/Example.java', f'package org.leo.core.{package};\n' + body)
                issues = audit(self.root)
                self.assertEqual(1, len(issues), issues)
                self.assertIn('core contract references infrastructure', issues[0])
        self.write('core/src/main/java/Example.java', 'package org.leo.core.infrastructure.persistence;\n' + body)
        self.assertEqual([], audit(self.root))

    def test_java_implementations_use_their_own_namespace(self):
        self.write('pom.xml', '<project><modules><module>javacore</module></modules></project>')
        self.module('javacore', [('core', 'compile')])
        self.write('javacore/src/main/java/Example.java',
                   'package org.leo.core.agent.impl; class Example {}')
        issues = audit(self.root)
        self.assertEqual(1, len(issues), issues)
        self.assertIn('belongs in org.leo.javacore', issues[0])
        self.write('javacore/src/main/java/Example.java',
                   'package org.leo.javacore.agent.impl; class Example {}')
        self.assertEqual([], audit(self.root))

    def test_runtime_fixtures_are_test_only_dependencies(self):
        self.module('service', [('javacore', 'test'), ('phpcore', 'test')])
        name = 'org.leo.shared.agent.impl.JavaAgentNode'
        self.production_type('javacore', name)
        self.write('service/src/test/java/ExampleTest.java', f'import {name};')
        self.assertEqual([], audit(self.root))
        self.write('service/src/main/java/Example.java', f'import {name};')
        self.assertTrue(any('forbidden upward import' in issue for issue in audit(self.root)))
        (self.root / 'service/src/main/java/Example.java').unlink()
        self.module('service', [('javacore', 'compile')])
        self.assertTrue(any('forbidden compile' in issue for issue in audit(self.root)))

    def test_imports_resolve_to_the_source_module_instead_of_the_package_prefix(self):
        name = 'org.leo.shared.agent.impl.JavaAgentNode'
        self.production_type('javacore', name, 'public static final Object INSTANCE = null;')
        cases = (
            f'import {name};',
            f'import static {name}.*;',
            f'import {name}.*;',
            'import org.leo.shared.agent.impl.*;',
            'import/* separator */ static org . leo . shared . agent . impl . JavaAgentNode . INSTANCE;',
        )
        for statement in cases:
            with self.subTest(statement=statement):
                self.write('service/src/main/java/Example.java', '// header\n' + statement)
                issues = audit(self.root)
                self.assertEqual(1, len(issues), issues)
                self.assertIn('Example.java:2:', issues[0])
                self.assertIn('forbidden upward import', issues[0])
                self.assertIn('javacore', issues[0])

    def test_mixed_packages_keep_explicit_type_ownership_separate(self):
        self.production_type('core', 'org.leo.shared.agent.database.SqlCommand')
        source = 'service/src/main/java/Example.java'

        self.production_type('javacore', 'org.leo.shared.agent.database.impl.Adapter')
        self.write(source, 'import org.leo.shared.agent.database.*;')
        self.assertEqual([], audit(self.root))  # Java wildcards do not import subpackages.

        self.production_type('javacore', 'org.leo.shared.agent.database.JavaDatabaseConnectionAdapter')
        self.write(source, 'import org.leo.shared.agent.database.SqlCommand;')
        self.assertEqual([], audit(self.root))

        # A wildcard exposes the runtime implementation as well. Explicit
        # contract imports keep shared-package consumers independent of it.
        self.write(source, 'import org.leo.shared.agent.database.*;')
        issues = audit(self.root)
        self.assertEqual(1, len(issues), issues)
        self.assertIn('javacore', issues[0])

    def test_physical_ownership_also_applies_to_non_leo_namespaces(self):
        name = 'com.vendor.bridge.Adapter'
        self.production_type('jmg', name)
        self.write('core/src/main/java/Example.java', f'import {name};')
        self.write('web/src/main/java/Example.java', f'import {name};')

        issues = audit(self.root)

        self.assertEqual(1, len(issues), issues)
        self.assertIn('core/src/main/java/Example.java:1:', issues[0])
        self.assertIn('jmg', issues[0])

    def test_fully_qualified_references_observe_module_boundaries(self):
        name = 'org.leo.shared.agent.impl.JavaAgentNode'
        self.production_type('javacore', name)
        for expression in (f'new {name}()', f'{name}.class', f'{name}.INSTANCE'):
            with self.subTest(expression=expression):
                self.production_type('service', 'org.leo.service.Example',
                                     f'Object value = {expression};')
                issues = audit(self.root)
                self.assertEqual(1, len(issues), issues)
                self.assertIn('forbidden upward reference', issues[0])
                self.assertIn('javacore', issues[0])

    def test_dollar_identifiers_keep_their_full_type_name(self):
        for simple_name in ('Runtime$', '$Runtime'):
            with self.subTest(simple_name=simple_name):
                name = 'org.leo.shared.runtimeimpl.' + simple_name
                self.production_type('javacore', name)
                self.production_type('service', 'org.leo.service.Example',
                                     f'Object value = new {name}();')
                issues = audit(self.root)
                self.assertEqual(1, len(issues), issues)
                self.assertIn(name, issues[0])
                self.assertIn('javacore', issues[0])

    def test_indexes_top_level_declarations_and_resolves_nested_type_imports(self):
        prefix = 'org.leo.shared.runtimeimpl'
        self.write('javacore/src/main/java/Holder.java',
                   f'package {prefix};\npublic class Holder {{ class Nested {{}} }}\n'
                   'class Secondary {}\ninterface Contract {}\nenum Mode { DEFAULT }\n'
                   'record Settings(int value) {}\n@interface Marker {}')
        for name in ('Holder.Nested', 'Secondary', 'Contract', 'Mode', 'Settings', 'Marker'):
            with self.subTest(name=name):
                self.write('service/src/main/java/Example.java', f'import {prefix}.{name};')
                issues = audit(self.root)
                self.assertEqual(1, len(issues), issues)
                self.assertIn('javacore', issues[0])

    def test_same_module_and_downward_runtime_references_remain_allowed(self):
        self.write('pom.xml', '<project><modules><module>core</module><module>javacore</module>'
                   '<module>service</module><module>web</module></modules></project>')
        self.module('javacore', [('core', 'compile')])
        self.production_type('core', 'org.leo.core.agent.AbstractAgentNode')
        self.production_type('javacore', 'org.leo.shared.agent.impl.JavaAgentNode',
                             'org.leo.core.agent.AbstractAgentNode contract;')
        self.production_type('javacore', 'org.leo.shared.agent.impl.OtherNode',
                             'org.leo.shared.agent.impl.JavaAgentNode node;')
        self.write('web/src/main/java/Example.java', 'import org.leo.shared.agent.impl.JavaAgentNode;')
        self.assertEqual([], audit(self.root))

    def test_duplicate_production_types_are_reported_instead_of_choosing_an_owner(self):
        name = 'org.leo.shared.Shared'
        self.production_type('core', name)
        self.production_type('javacore', name)

        issues = audit(self.root)

        self.assertEqual(1, len(issues), issues)
        self.assertIn('Duplicate production type: ' + name, issues[0])
        self.assertIn('core/src/main/java/', issues[0])
        self.assertIn('javacore/src/main/java/', issues[0])

    def test_invalid_descriptors_are_reported(self):
        for name in ('pom.xml', 'service/pom.xml'):
            with self.subTest(name=name):
                path = self.root / name
                original = path.read_text()
                path.write_text('<project>')
                self.assertEqual(1, len(audit(self.root)))
                path.unlink()
                self.assertEqual(1, len(audit(self.root)))
                path.write_text(original)


if __name__ == '__main__':
    unittest.main()
