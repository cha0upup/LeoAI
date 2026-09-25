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
        assertTrue(configs.capabilitiesForModel("test-model").supportsFunctionCalling());
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
                .filter(row -> row.getModelName().equals("mimo-v2.5")).findFirst().orElseThrow();
        manual.setContextWindowTokens(65_536);
        configs.updateCapability(manual.getModelName(), manual);
        initializeSchema();
        assertEquals(1_000_000, configs.capabilitiesForModel("deepseek-flash").contextWindowTokens());
        assertEquals(65_536, configs.capabilitiesForModel("mimo-v2.5").contextWindowTokens());
        assertEquals("manual", configs.capabilitiesForModel("mimo-v2.5").source());
        assertFalse(configs.capabilitiesForModel("gpt5.5").recognized());
    }

    private void initializeSchema() {
        new ResourceDatabasePopulator(new ClassPathResource("sql/schema.sql")).execute(dataSource);
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
