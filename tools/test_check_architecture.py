from pathlib import Path
import tempfile
import unittest

from check_architecture import audit, dependency_cycles


class ArchitectureAuditTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.write('pom.xml', '<project><modules><module>core</module><module>service</module><module>web</module></modules></project>')
        self.module('core')
        self.module('service', [('core', 'compile')])
        self.module('web', [('service', 'compile')])

    def write(self, name, source):
        target = self.root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source, encoding='utf-8')

    def module(self, name, dependencies=()):
        xml = ''.join(f'<dependency><groupId>org.leo</groupId><artifactId>{target}</artifactId><scope>{scope}</scope></dependency>' for target, scope in dependencies)
        self.write(name + '/pom.xml', '<project><dependencies>' + xml + '</dependencies></project>')

    def test_accepts_an_acyclic_dependency_direction(self):
        self.assertEqual([], audit(self.root))

    def test_rejects_upward_dependencies_and_cycles(self):
        self.module('service', [('web', 'compile')])
        issues = audit(self.root)
        self.assertTrue(any('forbidden compile dependency on web' in issue for issue in issues))
        self.assertTrue(any('cycle:' in issue for issue in issues))

    def test_cycles_do_not_reject_shared_dependencies(self):
        self.assertEqual([], dependency_cycles({'web': {'service', 'core'}, 'service': {'core'}, 'core': set()}))

    def test_rejects_multiline_and_static_upward_imports(self):
        self.write('service/src/main/java/Example.java', 'import\norg.leo.web.Example;\nimport static org.leo.web.Example.run;')
        issues = audit(self.root)
        self.assertEqual(2, len(issues))
        self.assertTrue(all('forbidden upward import' in issue for issue in issues))

    def test_partial_reactors_keep_the_same_dependency_boundaries(self):
        self.write('pom.xml', '<project><modules><module>service</module></modules></project>')
        self.module('service', [('web', 'compile')])
        self.write('service/src/main/java/Example.java', 'import org.leo.web.Example;')
        issues = audit(self.root)
        self.assertTrue(any('forbidden compile dependency on web' in issue for issue in issues))
        self.assertTrue(any('forbidden upward import' in issue for issue in issues))

    def test_ignores_documentation_strings_and_test_sources(self):
        self.write('service/src/main/java/Example.java', '/* import org.leo.web.X; */\nclass Example { String text = "import org.leo.web.X;"; String block = """\nimport org.leo.web.X;\n"""; }')
        self.write('service/src/test/java/ExampleTest.java', 'import org.leo.web.X;')
        self.assertEqual([], audit(self.root))

    def test_rejects_http_server_types_inside_application_services(self):
        self.write('service/src/main/java/Example.java', 'import jakarta.servlet.http.HttpServletRequest;\nimport org.springframework.web.bind.annotation.RestController;')
        self.assertEqual(2, len(audit(self.root)))

    def test_ssh_transport_libraries_belong_to_adapters(self):
        self.write('service/src/main/java/org/leo/service/ssh/Example.java', 'import net.schmizz.sshj.SSHClient;')
        self.write('web/src/main/java/Example.java', 'import net.schmizz.sshj.SSHClient;')
        self.assertEqual(1, len(audit(self.root)))

    def test_discovery_application_uses_ports_instead_of_jdbc_or_global_sessions(self):
        self.write('service/src/main/java/org/leo/service/discovery/Example.java',
                   'import java.sql.Connection;\nimport javax.sql.DataSource;\n'
                   'import org.springframework.jdbc.core.JdbcTemplate;\n'
                   'import org.leo.core.session.AgentNodeSessionContainer;')
        self.write('web/src/main/java/Example.java',
                   'import java.sql.Connection;\nimport org.leo.core.session.AgentNodeSessionContainer;')
        issues = audit(self.root)
        self.assertEqual(4, len(issues))
        self.assertTrue(any('global session registry' in issue for issue in issues))

    def test_export_lifecycle_and_csv_content_do_not_import_file_or_task_implementations(self):
        self.write('service/src/main/java/org/leo/service/sql/SqlExportService.java',
                   'import java.nio.file.Files;\nimport org.leo.core.util.json.JsonUtil;\n'
                   'import org.leo.service.sql.FileSqlExportArtifacts;')
        self.write('service/src/main/java/org/leo/service/sql/SqlTableCsvExporter.java',
                   'import org.leo.service.concurrent.ServiceTaskExecutor;')
        self.write('service/src/main/java/org/leo/service/sql/FileSqlExportArtifacts.java',
                   'import java.nio.file.Files;')
        issues = audit(self.root)
        self.assertEqual(4, len(issues))
        self.assertTrue(any('task scheduling' in issue for issue in issues))

    def test_runtime_fixtures_are_test_only_dependencies(self):
        self.write('pom.xml', '<project><modules><module>core</module><module>service</module><module>javacore</module></modules></project>')
        self.module('javacore', [('core', 'compile')])
        self.module('service', [('javacore', 'test')])
        self.assertEqual([], audit(self.root))
        self.module('service', [('javacore', 'compile')])
        self.assertTrue(any('forbidden compile' in issue for issue in audit(self.root)))

    def test_missing_descriptors_are_reported(self):
        (self.root / 'service/pom.xml').unlink()
        self.assertTrue(any('invalid module descriptor' in issue for issue in audit(self.root)))


if __name__ == '__main__':
    unittest.main()
