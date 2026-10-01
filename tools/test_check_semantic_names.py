from pathlib import Path
import tempfile
import unittest

from check_semantic_names import audit


class SemanticNameAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, path, text):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)

    def test_detects_singular_collections_without_matching_valid_plural_names(self):
        self.write('service/src/main/java/Example.java', 'getAllAgent(); getAllAgents();\ngetAllSession(); getAllSessions();')
        count, issues = audit(self.root)
        self.assertEqual(1, count)
        self.assertEqual([
            ('service/src/main/java/Example.java', 1, 'getAllAgent', 'getAllAgents'),
            ('service/src/main/java/Example.java', 2, 'getAllSession', 'getAllSessions'),
        ], issues)

    def test_keeps_real_ai_configuration_but_rejects_mislabelled_connection_testing(self):
        self.write('src/example.js', 'useAiAgentConfigTest(); AiAgentConfig;')
        _, issues = audit(self.root, frontend=True)
        self.assertEqual(['useAiAgentConfigTest'], [item[2] for item in issues])

    def test_distinguishes_execution_runtime_from_ai_runtime_by_role(self):
        self.write('src/console.js', 'const agentRuntime = "php"')
        self.write('web/src/main/java/NodeAiTurnService.java', 'AiRuntime agentRuntime;')
        self.assertEqual([], audit(self.root, frontend=True)[1])
        self.assertEqual(['agentRuntime'], [item[2] for item in audit(self.root)[1]])

    def test_skips_bundles_and_vendored_framework_sources(self):
        self.write('web/src/main/resources/static/example.js', 'getAllAgent();')
        self.write('jmg/src/main/java/com/xxl/Example.java', 'getAllUser();')
        self.assertEqual((0, []), audit(self.root))


if __name__ == '__main__':
    unittest.main()
