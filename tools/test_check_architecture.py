from pathlib import Path
import tempfile
import unittest

from check_architecture import audit, dependency_cycles


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

    def test_accepts_layered_dependencies_and_ignores_non_production_code(self):
        self.write('service/src/main/java/Example.java',
                   'import org.leo.core.Example;\n/* import org.leo.web.X; */\n'
                   'class Example { String text = "import org.leo.web.X;"; '
                   'String block = """\nimport org.leo.web.X;\n"""; }')
        self.write('service/src/test/java/ExampleTest.java', 'import org.leo.web.X;')
        self.assertEqual([], audit(self.root))
        self.assertEqual([], dependency_cycles({'web': {'service', 'core'},
                                               'service': {'core'}, 'core': set()}))

    def test_rejects_upward_dependencies_and_cycles(self):
        self.module('service', [('web', 'compile')])
        issues = audit(self.root)
        self.assertTrue(any('forbidden compile dependency on web' in issue for issue in issues))
        self.assertTrue(any('cycle:' in issue for issue in issues))

    def test_partial_reactors_keep_module_and_import_boundaries(self):
        self.write('pom.xml', '<project><modules><module>service</module></modules></project>')
        self.module('service', [('web', 'compile')])
        self.write('service/src/main/java/Example.java',
                   'import\norg.leo.web.Example;\nimport static org.leo.web.Example.run;')
        issues = audit(self.root)
        self.assertEqual(3, len(issues))
        self.assertEqual(2, sum('forbidden upward import' in issue for issue in issues))

    def test_application_adapters_stay_in_web(self):
        cases = {
            '': ('jakarta.servlet.http.HttpServletRequest', 'javax.servlet.Servlet',
                 'org.springframework.web.bind.annotation.RestController'),
            'org/leo/service/ssh/': ('net.schmizz.sshj.SSHClient', 'org.apache.sshd.client.SshClient'),
            'org/leo/service/discovery/': ('java.sql.Connection', 'javax.sql.DataSource',
                 'org.springframework.jdbc.core.JdbcTemplate', 'org.leo.core.session.AgentNodeSessionContainer'),
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

    def test_runtime_fixtures_are_test_only_dependencies(self):
        self.module('service', [('javacore', 'test'), ('phpcore', 'test')])
        self.assertEqual([], audit(self.root))
        self.module('service', [('javacore', 'compile')])
        self.assertTrue(any('forbidden compile' in issue for issue in audit(self.root)))

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
