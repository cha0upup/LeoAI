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

    def test_upload_boundaries_reject_policy_io_and_scheduler_leaks(self):
        self.write('service/src/main/java/org/leo/service/transfer/UploadEngineService.java',
                   'import org.leo.service.vfs.VfsFileAccess;\nimport java.io.InputStream;\n'
                   'class UploadEngineService { private static class UploadTask {} }')
        self.write('service/src/main/java/org/leo/service/transfer/UploadTask.java',
                   'import org.leo.service.concurrent.ServiceTaskExecutor;\n'
                   'import java.util.concurrent.Executors;')
        self.write('service/src/main/java/org/leo/service/vfs/VfsFileAccess.java',
                   'import org.leo.service.transfer.UploadEngineService;')
        issues = audit(self.root)
        self.assertEqual(6, len(issues))
        self.assertTrue(any('not the coordinator' in issue for issue in issues))
        self.assertTrue(any('VFS access depends on transfer' in issue for issue in issues))

    def test_upload_task_accepts_capabilities_and_cancellation_without_a_scheduler(self):
        self.write('service/src/main/java/org/leo/service/transfer/UploadTask.java',
                   'import org.leo.core.agent.capability.FileCapable;\n'
                   'import java.util.concurrent.Future;\nimport java.util.function.Consumer;')
        self.write('service/src/main/java/org/leo/service/transfer/UploadEngineService.java',
                   'import org.leo.service.concurrent.ServiceTaskExecutor;')
        self.write('service/src/main/java/org/leo/service/vfs/VfsFileAccess.java',
                   'import org.leo.core.config.LeoConfig;')
        self.assertEqual([], audit(self.root))

    def test_discovery_models_reject_same_package_execution_and_external_io(self):
        self.write('service/src/main/java/org/leo/service/discovery/NetworkProbeWorkflowPlan.java',
                   'package org.leo.service.discovery;\n'
                   'class NetworkProbeWorkflowPlan { NetworkProbeScheduler scheduler; }')
        self.write('service/src/main/java/org/leo/service/discovery/NetworkProbeStageResults.java',
                   'import org.leo.core.agent.capability.NetworkProbeCapable;\n'
                   'import java.util.concurrent.ExecutorService;\n'
                   'class NetworkProbeStageResults { NetworkProbeResultRepository repository; }')
        issues = audit(self.root)
        self.assertEqual(4, len(issues))
        self.assertTrue(all('execution infrastructure' in issue for issue in issues))

    def test_discovery_models_accept_domain_dependencies_and_ignore_documentation(self):
        self.write('service/src/main/java/org/leo/service/discovery/NetworkProbeWorkflowPlan.java',
                   'import org.leo.service.discovery.ScanPlanService.ScanPlan;\n'
                   'import static org.leo.service.discovery.NetworkProbePayloads.copyMap;\n'
                   '// NetworkProbeScheduler\n'
                   'class NetworkProbeWorkflowPlan { String label = "NetworkProbeResultRepository"; }')
        self.write('service/src/main/java/org/leo/service/discovery/NetworkProbeStageResults.java',
                   'import java.util.LinkedHashMap;\n'
                   'class NetworkProbeStageResults { void apply() { ServiceIdentificationPolicy.httpCandidates(null); } }')
        self.assertEqual([], audit(self.root))

    def test_missing_descriptors_are_reported(self):
        (self.root / 'service/pom.xml').unlink()
        self.assertTrue(any('invalid module descriptor' in issue for issue in audit(self.root)))

    def test_download_roles_reject_filesystem_and_execution_leaks(self):
        self.write('service/src/main/java/org/leo/service/transfer/DownloadEngineService.java',
                   'import java.io.File;\nimport org.leo.core.util.json.JsonUtil;')
        self.write('service/src/main/java/org/leo/service/downloadengine/DownloadTask.java',
                   'import java.nio.channels.FileChannel;\n'
                   'class DownloadTask { void commit() { java.nio.file.Files.move(null, null); } }')
        self.write('service/src/main/java/org/leo/service/downloadengine/DownloadStore.java',
                   'import org.leo.core.agent.capability.FileCapable;\n'
                   'class DownloadStore { DownloadTask task; }')
        issues = audit(self.root)
        self.assertEqual(6, len(issues))
        self.assertTrue(any('workspace depends on task execution' in issue for issue in issues))

    def test_download_roles_accept_workspace_and_state_dependencies(self):
        self.write('service/src/main/java/org/leo/service/transfer/DownloadEngineService.java',
                   'import org.leo.service.downloadengine.DownloadStore;')
        self.write('service/src/main/java/org/leo/service/downloadengine/DownloadTask.java',
                   'import java.io.File;\nimport org.leo.core.agent.capability.FileCapable;\n'
                   'class DownloadTask { void commit() { store.commitContent(target); } }')
        self.write('service/src/main/java/org/leo/service/downloadengine/DownloadStore.java',
                   'import java.nio.file.Files;\nimport org.leo.service.transfer.TransferTaskState;\n'
                   '// DownloadTask\nclass DownloadStore { String label = "DownloadTask"; }')
        self.assertEqual([], audit(self.root))

    def test_sql_export_task_rejects_execution_dependencies_and_parallel_ownership(self):
        self.write('service/src/main/java/org/leo/service/sql/SqlExportTask.java',
                   'import java.io.BufferedWriter;\nimport org.leo.service.concurrent.ServiceTaskExecutor;\n'
                   'class SqlExportTask { SqlExportArtifacts artifacts; AgentNodeSqlService sql; }')
        self.write('service/src/main/java/org/leo/service/sql/SqlExportService.java',
                   'class SqlExportService { Map liveControls; static class TaskControl {} }')
        issues = audit(self.root)
        self.assertEqual(6, len(issues))
        self.assertTrue(any('parallel control registry' in issue for issue in issues))

    def test_sql_export_task_accepts_value_types_and_the_metadata_port(self):
        self.write('service/src/main/java/org/leo/service/sql/SqlExportTask.java',
                   'import java.nio.file.Path;\nimport java.util.LinkedHashMap;\n'
                   '// SqlExportService\n'
                   'class SqlExportTask { SqlExportTaskRepository repository; SqlObjectRef table; }')
        self.write('service/src/main/java/org/leo/service/sql/SqlExportService.java',
                   'class SqlExportService { SqlExportTask task; }')
        self.assertEqual([], audit(self.root))

    def test_probe_result_buffer_rejects_execution_and_split_accounting(self):
        self.write('service/src/main/java/org/leo/service/discovery/NetworkProbeResultBuffer.java',
                   'import org.leo.core.agent.capability.NetworkProbeCapable;\n'
                   'import java.util.concurrent.ExecutorService;\n'
                   'class NetworkProbeResultBuffer { NetworkProbeScheduler scheduler; }')
        self.write('service/src/main/java/org/leo/service/discovery/NetworkProbeOrchestrationService.java',
                   'class NetworkProbeOrchestrationService { int observationOffset; int errorOffset; }')
        issues = audit(self.root)
        self.assertEqual(5, len(issues))
        self.assertTrue(any('accounting belongs to the buffer' in issue for issue in issues))

    def test_probe_result_buffer_accepts_value_operations_and_ignores_documentation(self):
        self.write('service/src/main/java/org/leo/service/discovery/NetworkProbeResultBuffer.java',
                   'import java.util.LinkedHashMap;\n'
                   'import static org.leo.service.discovery.NetworkProbePayloads.copyMap;\n'
                   '// NetworkProbeScheduler\nclass NetworkProbeResultBuffer {}')
        self.write('service/src/main/java/org/leo/service/discovery/NetworkProbeOrchestrationService.java',
                   'class NetworkProbeOrchestrationService { NetworkProbeResultBuffer results;\n'
                   'String note = "observationOffset"; }')
        self.assertEqual([], audit(self.root))

    def test_database_view_policy_rejects_storage_and_split_disclosure_rules(self):
        self.write('service/src/main/java/org/leo/service/sql/DatabaseConnectionViewPolicy.java',
                   'import org.leo.dao.mapper.Example;\nimport org.springframework.stereotype.Component;\n'
                   'class DatabaseConnectionViewPolicy { AgentDatabaseConnectionService repository; }')
        self.write('service/src/main/java/org/leo/service/sql/AgentDatabaseConnectionService.java',
                   'class AgentDatabaseConnectionService { String INLINE_SECRET; }')
        issues = audit(self.root)
        self.assertEqual(4, len(issues))
        self.assertTrue(any('disclosure rules belong to the view policy' in issue for issue in issues))

    def test_database_view_policy_accepts_value_rules_and_ignores_documentation(self):
        self.write('service/src/main/java/org/leo/service/sql/DatabaseConnectionViewPolicy.java',
                   'import java.util.Locale;\nimport java.util.regex.Pattern;\n'
                   '// DatabaseCredentialCryptoService\nclass DatabaseConnectionViewPolicy {}')
        self.write('service/src/main/java/org/leo/service/sql/AgentDatabaseConnectionService.java',
                   'class AgentDatabaseConnectionService { void view() { DatabaseConnectionViewPolicy.publicValues(null); } }')
        self.assertEqual([], audit(self.root))

    def test_http_fingerprint_evaluation_rejects_execution_and_split_evidence_rules(self):
        self.write('service/src/main/java/org/leo/service/discovery/HttpFingerprintEvaluator.java',
                   'import org.leo.service.fingerprint.FingerprintManageService;\n'
                   'import org.leo.core.agent.capability.NetworkProbeCapable;\n'
                   'class HttpFingerprintEvaluator { NetworkProbeAnalysisService lifecycle; }')
        self.write('service/src/main/java/org/leo/service/discovery/NetworkProbeAnalysisService.java',
                   'class NetworkProbeAnalysisService { String VERSION_TOKEN; }')
        issues = audit(self.root)
        self.assertEqual(4, len(issues))
        self.assertTrue(any('evidence semantics belong to the evaluator' in issue for issue in issues))

    def test_http_fingerprint_evaluator_accepts_value_rules_and_ignores_documentation(self):
        self.write('service/src/main/java/org/leo/service/discovery/HttpFingerprintEvaluator.java',
                   'import java.util.regex.Pattern;\n'
                   'import static org.leo.service.discovery.NetworkProbePayloads.copyMap;\n'
                   '// NetworkProbeAnalysisService\nclass HttpFingerprintEvaluator {}')
        self.write('service/src/main/java/org/leo/service/discovery/NetworkProbeAnalysisService.java',
                   'class NetworkProbeAnalysisService { HttpFingerprintEvaluator.Decision decision; }')
        self.assertEqual([], audit(self.root))


if __name__ == '__main__':
    unittest.main()
