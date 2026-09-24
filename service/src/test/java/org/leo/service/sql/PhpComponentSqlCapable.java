package org.leo.service.sql;

import org.leo.core.puppet.capability.SqlCapable;
import org.leo.core.puppet.database.DatabaseConnectionSpec;
import org.leo.core.puppet.database.SqlCommand;
import org.leo.core.util.json.PortableJsonCodec;
import org.leo.phpcore.database.PhpDatabaseConnectionAdapter;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

final class PhpComponentSqlCapable implements SqlCapable {

    private final Path component;
    private final PhpDatabaseConnectionAdapter adapter = new PhpDatabaseConnectionAdapter();

    private PhpComponentSqlCapable(Path component) {
        this.component = component;
    }

    static PhpComponentSqlCapable create() throws Exception {
        URL resource = Objects.requireNonNull(
                PhpComponentSqlCapable.class.getResource("/components/DatabaseComponent.php"));
        String url = resource.toString();
        if (!url.startsWith("jar:")) {
            return new PhpComponentSqlCapable(Paths.get(resource.toURI()));
        }
        Path component = Files.createTempFile("leo-database-component-", ".php");
        component.toFile().deleteOnExit();
        try (var input = resource.openStream()) {
            Files.copy(input, component, StandardCopyOption.REPLACE_EXISTING);
        }
        return new PhpComponentSqlCapable(component);
    }

    static boolean phpAvailable() {
        return commandSucceeds("php", "-v");
    }

    static boolean pdoDriverAvailable(String driver) {
        String safeDriver = driver == null ? "" : driver.replace("'", "");
        return commandSucceeds("php", "-r",
                "exit(in_array('" + safeDriver + "',PDO::getAvailableDrivers(),true)?0:1);");
    }

    @Override
    public Map<String, Object> executeSql(DatabaseConnectionSpec connection,
                                          SqlCommand command) throws Exception {
        Map<String, Object> params = new LinkedHashMap<String, Object>(adapter.adapt(connection));
        params.put("sql", command.sql());
        if (command.hasParameters()) params.put("parameters", command.parameters());
        return invoke("exec", params);
    }

    @Override
    public Map<String, Object> inspectDatabaseRuntime(Map<String, Object> connection) throws Exception {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        String dialect = String.valueOf(connection.getOrDefault("dialect", ""));
        params.put("requestedDriver", adapter.defaultDriver(dialect));
        return invoke("capabilities", params);
    }

    private Map<String, Object> invoke(String operation, Map<String, Object> params) throws Exception {
        String encoded = Base64.getEncoder().encodeToString(PortableJsonCodec.encode(params));
        String script = "function leo_binary($value){return array('$leoBinary'=>base64_encode($value));}"
                + "$component=require $argv[1];"
                + "$params=json_decode(base64_decode($argv[2]),true);"
                + "echo json_encode(call_user_func($component['handle'],$argv[3],$params));";
        Process process = new ProcessBuilder(
                "php", "-r", script, component.toString(), encoded, operation)
                .redirectErrorStream(true).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("PHP database component timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new IllegalStateException(output);
        return PortableJsonCodec.decode(output.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean commandSucceeds(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
