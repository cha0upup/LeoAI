package org.leo.ai.channel;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.entity.AiModelCapability;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiProvider;
import org.leo.dao.mapper.AiModelCapabilityMapper;
import org.leo.dao.mapper.AiModelConfigMapper;
import org.leo.dao.mapper.AiProviderMapper;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class AiModelConfigurationIntegrationTest {
    @TempDir Path temp;
    private AnnotationConfigApplicationContext context;
    private DataSource dataSource;
    private com.zaxxer.hikari.HikariDataSource pool;
    private JdbcTemplate jdbc;
    private AiModelConfigService configs;
    private DelegatingChatModel chat;
    private DataSourceTransactionManager transactions;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        sqlite.setBusyTimeout(5000);
        SQLiteDataSource ds = new SQLiteDataSource(sqlite);
        ds.setUrl("jdbc:sqlite:" + temp.resolve("models.db"));
        pool = new com.zaxxer.hikari.HikariDataSource();
        pool.setDataSource(ds);
        pool.setMaximumPoolSize(1);
        pool.setConnectionTimeout(1000);
        dataSource = pool;
        initializeSchema();
        jdbc = new JdbcTemplate(dataSource);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        org.apache.ibatis.session.Configuration mybatis = new org.apache.ibatis.session.Configuration();
        mybatis.setMapUnderscoreToCamelCase(true);
        mybatis.addMapper(AiProviderMapper.class);
        mybatis.addMapper(AiModelConfigMapper.class);
        mybatis.addMapper(AiModelCapabilityMapper.class);
        factory.setConfiguration(mybatis);
        SqlSessionFactory sessionFactory = factory.getObject();
        SqlSessionTemplate template = new SqlSessionTemplate(sessionFactory);
        transactions = new DataSourceTransactionManager(dataSource);
        context = new AnnotationConfigApplicationContext();
        context.register(TxConfig.class);
        context.registerBean(DataSourceTransactionManager.class, () -> transactions);
        context.registerBean(AiProviderMapper.class, () -> template.getMapper(AiProviderMapper.class));
        context.registerBean(AiModelConfigMapper.class, () -> template.getMapper(AiModelConfigMapper.class));
        context.registerBean(AiModelCapabilityMapper.class, () -> template.getMapper(AiModelCapabilityMapper.class));
        context.registerBean(AiSecretCryptoService.class, () -> new AiSecretCryptoService("test-master", "unused"));
        context.register(AiModelConfigService.class, DynamicModelProvider.class,
                DelegatingChatModel.class);
        context.refresh();
        configs = context.getBean(AiModelConfigService.class);
        chat = context.getBean(DelegatingChatModel.class);
    }

    @AfterEach
    void tearDown() {
        if (context != null) context.close();
        if (pool != null) pool.close();
    }

    @Test
    void failedDefaultCreationRollsBackBothRowsAndRuntime() {
        AiProvider provider = provider("primary");
        AiModelConfig first = model(provider, "first");
        Object delegate = chat.getDelegate();
        assertNotNull(delegate);
        AiModelConfig conflicting = draft(provider, "first");
        conflicting.setIsActive(1);
        assertThrows(RuntimeException.class, () -> configs.create(conflicting));
        assertEquals(first.getId(), configs.getActive().getId());
        assertSame(delegate, chat.getDelegate());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_model_configs", Integer.class));
    }

    @Test
    void runtimeChangesOnlyAfterCommitAndDeletingTheDefaultClearsDelegates() {
        AiProvider provider = provider("primary");
        AiModelConfig first = model(provider, "first");
        AiModelConfig second = model(provider, "second");
        Object delegate = chat.getDelegate();
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            configs.activate(second.getId());
            assertSame(delegate, chat.getDelegate());
            status.setRollbackOnly();
        });
        assertEquals(first.getId(), configs.getActive().getId());
        assertSame(delegate, chat.getDelegate());
        transaction.executeWithoutResult(status -> {
            configs.activate(second.getId());
            assertSame(delegate, chat.getDelegate());
        });
        assertNotSame(delegate, chat.getDelegate());
        assertEquals(second.getId(), configs.getActive().getId());
        configs.deleteById(second.getId());
        assertNull(configs.getActive());
        assertNull(chat.getDelegate());
    }

    @Test
    void providerAndModelCredentialsRollBackTogetherWhenSnapshotWriteFails() {
        AiProvider provider = provider("primary");
        AiModelConfig model = model(provider, "first");
        Object delegate = chat.getDelegate();
        jdbc.execute("CREATE TRIGGER fail_snapshot BEFORE UPDATE ON ai_model_configs BEGIN SELECT RAISE(ABORT, 'snapshot rejected'); END");
        AiProvider patch = new AiProvider();
        patch.setApiKey("replacement-key");
        assertThrows(RuntimeException.class, () -> configs.updateProvider(provider.getId(), patch));
        assertEquals("test-key", configs.findProviderById(provider.getId()).getApiKey());
        assertEquals("test-key", configs.findById(model.getId()).getApiKey());
        assertSame(delegate, chat.getDelegate());
    }

    @Test
    void probesAreLocalToConnectionAndPreserveUnmeasuredBaselineFlags() {
        AiModelCapability baseline = new AiModelCapability();
        baseline.setModelName("test-model");
        baseline.setContextWindowTokens(65536);
        baseline.setMaxOutputTokens(4096);
        baseline.setSupportsFunctionCalling(1);
        configs.createCapability(baseline);
        AiProvider firstProvider = provider("first-provider");
        AiModelConfig first = model(firstProvider, "first");
        AiModelConfig second = model(provider("second-provider"), "second");
        configs.applyProbeResult(first, Map.of("functionCalling", false));
        assertFalse(configs.capabilitiesForModel(first).supportsFunctionCalling());
        assertTrue(configs.capabilitiesForModel(second).supportsFunctionCalling());
        assertTrue(configs.capabilitiesForModel(null, "test-model").supportsFunctionCalling());
        assertEquals(65536, configs.capabilitiesForModel(first).contextWindowTokens());
        assertNull(jdbc.queryForObject("SELECT supports_streaming FROM ai_model_capability_observations", Integer.class));
        configs.applyProbeResult(first, Map.of("streaming", true));
        assertFalse(configs.capabilitiesForModel(first).supportsFunctionCalling());

        AiProvider patch = new AiProvider();
        patch.setApiKey("new-connection-key");
        configs.updateProvider(firstProvider.getId(), patch);
        AiModelConfig refreshed = configs.findById(first.getId());
        assertTrue(configs.capabilitiesForModel(refreshed).supportsFunctionCalling());
        configs.applyProbeResult(refreshed, Map.of("streaming", true));
        assertTrue(configs.capabilitiesForModel(refreshed).supportsFunctionCalling());
    }

    @Test
    void disablingStreamingClearsRuntimeButDoesNotPreventARecoveryProbe() {
        AiModelConfig model = model(provider("primary"), "first");
        assertNotNull(chat.getDelegate());
        configs.applyProbeResult(model, Map.of("streaming", false));
        assertNull(chat.getDelegate());
        assertDoesNotThrow(() -> context.getBean(DynamicModelProvider.class).buildProbeRuntime(model, false));
        configs.applyProbeResult(model, Map.of("streaming", true));
        assertNotNull(chat.getDelegate());
    }

    @Test
    void schemaIsIdempotentAndRejectsDuplicateDefaults() {
        AiProvider provider = provider("primary");
        AiModelConfig first = model(provider, "first");
        AiModelConfig second = model(provider, "second");
        initializeSchema();
        initializeSchema();
        assertEquals(first.getId(), configs.getActive().getId());
        assertThrows(RuntimeException.class,
                () -> jdbc.update("UPDATE ai_model_configs SET is_active=1 WHERE id=?", second.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_model_configs WHERE is_active=1", Integer.class));
    }

    @Test
    void catalogRefreshUpdatesSystemLimitsAndPreservesManualOverrides() {
        jdbc.update("UPDATE ai_model_capabilities SET context_window_tokens=1 WHERE model_name='deepseek-flash'");
        AiModelCapability manual = configs.listModelCapabilities().stream()
                .filter(row -> row.getModelName().equals("mimo-v2.6-pro")).findFirst().orElseThrow();
        manual.setContextWindowTokens(65_536);
        configs.updateCapability(manual.getModelName(), manual);
        initializeSchema();
        assertEquals(1_000_000, configs.capabilitiesForModel(null, "deepseek-flash").contextWindowTokens());
        assertEquals(65_536, configs.capabilitiesForModel(null, "mimo-v2.6-pro").contextWindowTokens());
        assertEquals("manual", configs.capabilitiesForModel(null, "mimo-v2.6-pro").source());
        assertFalse(configs.capabilitiesForModel(null, "gpt5.5").recognized());
    }

    private void initializeSchema() {
        new ResourceDatabasePopulator(new ClassPathResource("sql/schema.sql")).execute(dataSource);
    }

    @Test
    void refreshedCatalogCoversCurrentChatModelsAndDropsRetiredMimoSystemRows() {
        Set<String> expected = Set.of(
                "gpt-6-astra", "gpt-6.1-sol", "gpt-6.1-terra", "gpt-6-luna",
                "deepseek-flash", "deepseek-v4-pro",
                "kimi-k3.1-pro", "kimi-k3.1-pro-highspeed", "kimi-k3", "kimi-k2.7-code", "kimi-k2.7-code-highspeed", "kimi-k2.6",
                "qwen3.8-max", "qwen3.8-flash", "qwen3.7-plus",
                "glm-5.3", "glm-5.3-flash", "glm-5.3-flashx", "glm-5.3-flashx-legal",
                "gemini-4-argon", "gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite",
                "mimo-v2.6-pro", "mimo-v2.6-flash", "mimo-v2.6-pro-ultraspeed");
        jdbc.update("INSERT INTO ai_model_capabilities(model_name,source,context_window_tokens,max_output_tokens) VALUES('mimo-v2.5','system',1000000,128000)");
        initializeSchema();
        assertEquals(expected, configs.listModelCapabilities().stream()
                .map(AiModelCapability::getModelName).collect(Collectors.toSet()));
        for (String name : expected) {
            var caps = configs.capabilitiesForModel(null, name);
            assertTrue(caps.recognized(), name);
            assertTrue(caps.supportsStreaming(), name);
            assertTrue(caps.supportsFunctionCalling(), name);
            assertTrue(caps.maxOutputTokens() > 0, name);
        }
        assertFalse(configs.capabilitiesForModel(null, "mimo-v2.5").recognized());
        assertFalse(configs.capabilitiesForModel(null, "mimo-v2.5-pro").recognized());
        assertEquals(1_050_000, configs.capabilitiesForModel("openai", "gpt-6.1-sol").contextWindowTokens());
        AiModelConfig inputBudget = new AiModelConfig();
        inputBudget.setProviderKey("openai");
        inputBudget.setModel("gpt-6.1-sol");
        assertEquals(922_000, configs.getContextWindowTokens(inputBudget));
        inputBudget.setContextWindowTokens(65_536);
        assertEquals(65_536, configs.getContextWindowTokens(inputBudget));
        assertEquals(65_536, configs.capabilitiesForModel("gemini", "gemini-4-argon").maxOutputTokens());
    }

    @Test
    void kimiCatalogResolvesBothProviderPrefixesAndCreatesAnActiveModel() {
        Map<String, Integer> windows = Map.of(
                "kimi-k3.1-pro", 1_048_576, "kimi-k3.1-pro-highspeed", 1_048_576, "kimi-k3", 1_048_576,
                "kimi-k2.7-code", 262_144, "kimi-k2.7-code-highspeed", 262_144, "kimi-k2.6", 262_144);
        windows.forEach((model, window) -> {
            var caps = configs.capabilitiesForModel("kimi", model);
            assertTrue(caps.recognized(), model);
            assertTrue(caps.supportsReasoning(), model);
            assertTrue(caps.supportsStreaming(), model);
            assertTrue(caps.supportsFunctionCalling(), model);
            assertEquals(window.intValue(), caps.contextWindowTokens(), model);
            assertEquals(model.startsWith("kimi-k3") ? 1_048_576 : 32_768, caps.maxOutputTokens(), model);
            assertEquals(caps, configs.capabilitiesForModel("custom", "kimi/" + model));
            assertEquals(caps, configs.capabilitiesForModel("custom", "moonshot/" + model));
        });
        AiProvider provider = new AiProvider();
        provider.setName("Kimi");
        provider.setProviderKey("kimi");
        provider.setBaseUrl("https://api.moonshot.cn/v1");
        provider.setApiKey("test-key");
        provider.setProtocol("chat_completions");
        provider.setCompletionsPath("/chat/completions");
        provider = configs.createProvider(provider);
        AiModelConfig draft = draft(provider, "Kimi K3.1 Pro");
        draft.setModel("kimi-k3.1-pro");
        AiModelConfig saved = configs.create(draft);

        assertEquals(saved.getId(), configs.getActive().getId());
        assertEquals("kimi", saved.getProviderKey());
        assertEquals("https://api.moonshot.cn/v1", saved.getBaseUrl());
        assertNotNull(chat.getDelegate());
    }

    private AiProvider provider(String name) {
        AiProvider provider = new AiProvider();
        provider.setName(name);
        provider.setProviderKey("custom");
        provider.setBaseUrl("https://example.test/v1");
        provider.setApiKey("test-key");
        provider.setProtocol("chat_completions");
        return configs.createProvider(provider);
    }

    private AiModelConfig model(AiProvider provider, String name) {
        return configs.create(draft(provider, name));
    }

    private AiModelConfig draft(AiProvider provider, String name) {
        AiModelConfig model = new AiModelConfig();
        model.setProviderId(provider.getId());
        model.setName(name);
        model.setModel("test-model");
        return model;
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TxConfig {}
}
