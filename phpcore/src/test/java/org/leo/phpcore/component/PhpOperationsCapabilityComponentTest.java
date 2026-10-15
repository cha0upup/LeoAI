package org.leo.phpcore.component;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.leo.phpcore.PhpTestSupport.code;
import static org.leo.phpcore.PhpTestSupport.invokeComponent;
import static org.leo.phpcore.PhpTestSupport.phpAvailable;

class PhpOperationsCapabilityComponentTest {

    @BeforeAll
    static void requirePhp() {
        Assumptions.assumeTrue(phpAvailable(), "PHP CLI is not installed");
    }

    @Test
    void listsConnectionsAndBuildsSummary() throws Exception {
        Map<String, Object> listed = invokeComponent("NetworkConnectionComponent.php", "list", "array('maxEntries'=>20)");
        assertEquals(200, code(listed));
        assertInstanceOf(List.class, listed.get("connections"));
        assertTrue(listed.containsKey("filtered"));

        Map<String, Object> summary = invokeComponent("NetworkConnectionComponent.php", "summary", "array()");
        assertEquals(200, code(summary));
        assertInstanceOf(Map.class, summary.get("byState"));
        assertInstanceOf(List.class, summary.get("listeningPorts"));
        if (System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            assertTrue(assertInstanceOf(List.class, listed.get("diagnostics")).contains("source=/proc/net"));
        }
    }

    @Test
    void linuxInspectionWorksWithCommandFunctionsDisabled() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("linux"));
        Map<String, Object> processes = invokeComponent("ProcessComponent.php", "list", "array()", true);
        assertEquals(200, code(processes));
        assertFalse(assertInstanceOf(List.class, processes.get("processes")).isEmpty());

        Map<String, Object> connections = invokeComponent(
                "NetworkConnectionComponent.php", "list", "array('maxEntries'=>20)", true);
        assertEquals(200, code(connections));
        assertTrue(assertInstanceOf(List.class, connections.get("diagnostics")).contains("source=/proc/net"));

    }

    @Test
    void listsServicesAndScheduledTasks() throws Exception {
        Map<String, Object> services = invokeComponent("ServiceComponent.php", "list", "array()");
        assertEquals(200, code(services));
        Map<?, ?> serviceData = assertInstanceOf(Map.class, services.get("data"));
        assertInstanceOf(List.class, serviceData.get("services"));

        Map<String, Object> tasks = invokeComponent("ScheduledTaskComponent.php", "list", "array()");
        assertEquals(200, code(tasks));
        Map<?, ?> taskData = assertInstanceOf(Map.class, tasks.get("data"));
        assertInstanceOf(List.class, taskData.get("tasks"));
    }

    @Test
    void runsPersistentNetworkProbeWorker() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Map<String, Object> started = invokeComponent("NetworkProbeComponent.php", "startTask",
                    "array('plan'=>array('targets'=>array(array('host'=>'127.0.0.1','port'=>" + port + ")),'stages'=>array('tcp-connect')))");
            assertEquals(200, code(started));
            String taskId = String.valueOf(started.get("taskId"));
            assertFalse(taskId.isBlank());

            Map<?, ?> info = awaitNetworkProbeTask(taskId);
            assertEquals("STOPPED", info.get("status"));
            assertTrue(assertInstanceOf(List.class, info.get("observations")).stream()
                    .anyMatch(value -> "open".equals(((Map<?, ?>) value).get("state"))));
            assertEquals(200, code(invokeComponent("NetworkProbeComponent.php", "releaseTask",
                    "array('taskId'=>'" + taskId + "')")));
            assertEquals(404, code(invokeComponent("NetworkProbeComponent.php", "queryTask",
                    "array('taskId'=>'" + taskId + "','cursor'=>0,'maxItems'=>128,'maxBytes'=>524288,'includeEvidence'=>true)")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http-head", "http-request"})
    void returnsHttpEvidenceForBothStages(String stage) throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<String> requestLine = new CompletableFuture<>();
            Thread responder = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    requestLine.complete(reader.readLine());
                    while (!reader.readLine().isEmpty()) { }
                    socket.getOutputStream().write((
                            "HTTP/1.1 200 OK\r\n" +
                            "Server: php-probe-test\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Content-Length: 24\r\n\r\n" +
                            "<title>Console</title>ok").getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {
                    // The worker may be stopped while the test is cleaning up.
                }
            }, "php-network-probe-responder");
            responder.start();

            Map<String, Object> started = invokeComponent("NetworkProbeComponent.php", "startTask",
                    "array('plan'=>array('targets'=>array(array('host'=>'127.0.0.1','port'=>" + server.getLocalPort()
                            + ",'protocol'=>'http','baseUrl'=>'http://127.0.0.1:" + server.getLocalPort()
                            + "/console?view=1','httpRequest'=>array('method'=>'GET','path'=>'/console?view=1'))),'stages'=>array('" + stage + "')))");
            assertEquals(200, code(started));
            String taskId = String.valueOf(started.get("taskId"));

            Map<?, ?> info = awaitNetworkProbeTask(taskId);
            responder.join(5000);
            assertEquals("STOPPED", info.get("status"));
            Map<?, ?> observation = assertInstanceOf(Map.class,
                    assertInstanceOf(List.class, info.get("observations")).get(0));
            Map<?, ?> evidence = assertInstanceOf(Map.class, observation.get("evidence"));
            assertEquals(200, evidence.get("statusCode"));
            assertEquals("GET /console?view=1 HTTP/1.1", requestLine.get(2, TimeUnit.SECONDS));
            assertEquals("Console", evidence.get("title"));
            assertEquals(24, evidence.get("bodyLength"));
            if ("http-request".equals(stage)) {
                assertTrue(String.valueOf(evidence.get("headers")).contains("Server: php-probe-test"));
                assertEquals("<title>Console</title>ok", evidence.get("body"));
            } else {
                assertFalse(evidence.containsKey("headers"));
                assertFalse(evidence.containsKey("body"));
            }
            assertEquals(200, code(invokeComponent("NetworkProbeComponent.php", "releaseTask",
                    "array('taskId'=>'" + taskId + "')")));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"plain", "chunked", "gzip"})
    void preservesVirtualHostAndRuleBodiesBeyondBannerLimit(String encoding) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<String> request = new CompletableFuture<>();
            String body = "x".repeat(5000) + "component-marker";
            Thread responder = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    StringBuilder headers = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) headers.append(line).append("\n");
                    request.complete(headers.toString());
                    byte[] encoded = body.getBytes(StandardCharsets.US_ASCII);
                    String framing = "Content-Length: " + encoded.length;
                    if ("gzip".equals(encoding)) {
                        var compressed = new java.io.ByteArrayOutputStream();
                        try (var gzip = new java.util.zip.GZIPOutputStream(compressed)) { gzip.write(encoded); }
                        encoded = compressed.toByteArray();
                        framing = "Content-Encoding: gzip\r\nContent-Length: " + encoded.length;
                    } else if ("chunked".equals(encoding)) {
                        encoded = (Integer.toHexString(encoded.length) + "\r\n" + body + "\r\n0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                        framing = "Transfer-Encoding: chunked";
                    }
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\n" + framing
                            + "\r\nX-Latin1: é\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().write(encoded);
                    socket.getOutputStream().flush();
                } catch (IOException error) { request.completeExceptionally(error); }
            });
            responder.start();
            String taskId = "";
            try {
                var started = invokeComponent("NetworkProbeComponent.php", "startTask",
                        "array('plan'=>array('targets'=>array(array('host'=>'127.0.0.1','port'=>" + server.getLocalPort()
                        + ",'protocol'=>'http','baseUrl'=>'http://virtual.example.invalid:" + server.getLocalPort()
                        + "/','httpRequest'=>array('method'=>'GET','path'=>'/app/probe'))),'stages'=>array('http-request'),"
                        + "'limits'=>array('maxReadBytes'=>8192)))");
                assertEquals(200, code(started));
                taskId = String.valueOf(started.get("taskId"));
                Map<?, ?> info = awaitNetworkProbeTask(taskId);
                Map<?, ?> observation = (Map<?, ?>) ((List<?>) info.get("observations")).get(0);
                Map<?, ?> evidence = (Map<?, ?>) observation.get("evidence");
                assertEquals(body, evidence.get("body"));
                assertEquals(false, evidence.get("truncated"));
                assertEquals("é", ((Map<?, ?>) evidence.get("responseHeaders")).get("x-latin1"));
                assertTrue(request.get(2, TimeUnit.SECONDS).contains("Host: virtual.example.invalid:" + server.getLocalPort()));
                assertTrue(request.get().startsWith("GET /app/probe HTTP/1.1"));
            } finally {
                if (!taskId.isEmpty()) invokeComponent("NetworkProbeComponent.php", "releaseTask", "array('taskId'=>'" + taskId + "')");
                responder.join(3000);
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"items", "bytes", "stop"})
    void phpBackpressureLeavesAckAndStopAvailable(String mode) throws Exception {
        var component = java.nio.file.Path.of(getClass().getResource("/components/NetworkProbeComponent.php").toURI());
        String script = """
                $component=require $argv[1]; $mode=$argv[2]; $handle=$component['handle'];
                $id='pressure'.bin2hex(random_bytes(8)); $directory=$path($id); mkdir($directory,0700,true);
                $statusFile=$directory.DIRECTORY_SEPARATOR.$stateName('status');
                $n=$mode==='bytes'?2:3072;
                $row=['state'=>'open','evidence'=>$mode==='bytes'?str_repeat('x',1600000):''];
                $size=strlen(json_encode($row));
                $write($directory.DIRECTORY_SEPARATOR.$stateName('config'),[
                    'targets'=>[['host'=>'127.0.0.1','port'=>1]],'stages'=>['fixture-stage'],'workers'=>1, 'timeout'=>1000, 'maxReadBytes'=>8192]);
                $write($statusFile,['taskId'=>$id,'status'=>'RUNNING','outcome'=>'RUNNING','total'=>1,'completed'=>0,
                    'resultVersion'=>0,'pendingItems'=>0,
                    'observationOffset'=>0,'pendingBytes'=>0,'backpressured'=>true,
                    'workers'=>1,'workersDone'=>0]);
                $write($resultPath($statusFile,0),$emptyResults);
                $statusUpdate($statusFile,static function(&$status) use($appendRows,$statusFile,$n,$row,$size) {
                    $appendRows($statusFile,$status,array_fill(0,$n,json_encode($row))); $status['pendingBytes']=$n*$size; return true;
                },true);
                try {
                    if(!$pump()) throw new RuntimeException('worker scheduling failed');
                    usleep(200000);
                    $initial=$handle('queryTask',['taskId'=>$id,'maxItems'=>1])['result'];
                    if($mode==='stop') $handle('stopTask',['taskId'=>$id]);
                    else $handle('queryTask',['taskId'=>$id,'cursor'=>$n,'ackCursor'=>$n,'maxItems'=>1]);
                    $deadline=microtime(true)+5;
                    do {
                        $final=$handle('queryTask',['taskId'=>$id,'cursor'=>$mode==='stop'?0:$n,'maxItems'=>1])['result'];
                        if($final['status']==='STOPPED') break;
                        usleep(20000);
                    } while(microtime(true)<$deadline);
                    echo json_encode(['initialStatus'=>$initial['status'],'initialCompleted'=>$initial['completed'],
                        'initialPressure'=>$initial['backpressured'],'initialBytes'=>$initial['pendingBytes'],
                        'outcome'=>$final['outcome'],'pressure'=>$final['backpressured'],
                        'cursor'=>$final['nextCursor'],'items'=>$final['pendingItems']]);
                } finally { $handle('stopTask',['taskId'=>$id]); $handle('releaseTask',['taskId'=>$id]); }
                """;
        Map<String, Object> result = org.leo.phpcore.PhpTestSupport.runJson(10,
                "php", "-r", script, component.toString(), mode);
        assertEquals("RUNNING", result.get("initialStatus"));
        assertEquals(0, ((Number)result.get("initialCompleted")).intValue());
        assertEquals(true, result.get("initialPressure"));
        assertTrue(((Number)result.get("initialBytes")).longValue() <= 4194304);
        assertEquals("stop".equals(mode) ? "CANCELLED" : "COMPLETED", result.get("outcome"));
        assertEquals(false, result.get("pressure"));
        assertEquals("stop".equals(mode) ? 3072 : 1, ((Number)result.get("items")).intValue());
        if (!"stop".equals(mode)) assertEquals("bytes".equals(mode) ? 3 : 3073, ((Number)result.get("cursor")).intValue());
    }

    @ParameterizedTest @ValueSource(strings = {"pause", "pressure", "budget"})
    void phpPoolParksCheckpointsAndSharesCapacityWithoutReplayingProbes(String mode) throws Exception {
        var component = java.nio.file.Path.of(getClass().getResource("/components/NetworkProbeComponent.php").toURI());
        String script = """
                $component=require $argv[1]; $handle=$component['handle']; $mode=$argv[2];
                $server=stream_socket_server('tcp://127.0.0.1:0');
                $port=(int)substr(strrchr(stream_socket_get_name($server,false),':'),1);
                $width=$mode==='budget'?16:1; $ids=[]; $connections=[];
                $await=static function($condition) {
                    $deadline=microtime(true)+5;
                    do { if($condition()) return; usleep(10000); } while(microtime(true)<$deadline);
                    throw new RuntimeException('condition timed out');
                };
                $accept=static function($count) use($server,$await) {
                    $peers=[]; $await(static function() use($server,$count,&$peers) {
                        $peer=@stream_socket_accept($server,0); if(is_resource($peer)) $peers[]=$peer;
                        return count($peers)===$count;
                    }); return $peers;
                };
                try {
                    $targets=array_fill(0,$width,['host'=>'127.0.0.1','port'=>$port]);
                    $id=$handle('startTask',['plan'=>['targets'=>$targets,'stages'=>$mode==='pressure'?['tcp-exchange']:['tcp-exchange','tcp-exchange'],
                        'limits'=>['threads'=>64,'timeout'=>3000]]])['taskId']; $ids[]=$id;
                    $connections=$accept($width);
                    if($mode==='pressure') {
                        $statusFile=$path($id).DIRECTORY_SEPARATOR.$stateName('status');
                        $statusUpdate($statusFile,static function(&$status) use($statusFile,$appendRows) {
                            $row=['state'=>'open']; $size=strlen(json_encode($row));
                            $appendRows($statusFile,$status,array_fill(0,3072,json_encode($row)));
                            $status['pendingBytes']=3072*$size; $status['backpressured']=true; return true;
                        },true);
                    }
                    if($mode==='budget') {
                        $small=$handle('startTask',['plan'=>['targets'=>array_fill(0,16,['host'=>'127.0.0.1','port'=>1]),'stages'=>['fixture-stage']]])['taskId'];
                        $ids[]=$small; $queued=$handle('queryTask',['taskId'=>$small])['result'];
                        if($queued['activeProcesses']!==16 || $queued['completed']!==0) throw new RuntimeException('node budget exceeded');
                    }
                    if($mode!=='pressure') $handle('pauseTask',['taskId'=>$id]);
                    foreach($connections as $peer) { fwrite($peer,'first'); fclose($peer); } $connections=[];
                    if($mode!=='budget') {
                        $small=$handle('startTask',['plan'=>['targets'=>[['host'=>'127.0.0.1','port'=>1]],'stages'=>['fixture-stage']]])['taskId']; $ids[]=$small;
                    }
                    $await(static function() use($handle,$small) { return $handle('queryTask',['taskId'=>$small])['result']['status']==='STOPPED'; });
                    $parked=null; $await(static function() use($handle,$id,&$parked) {
                        $parked=$handle('queryTask',['taskId'=>$id])['result']; return $parked['activeProcesses']===0;
                    });
                    if($mode==='pressure') $handle('ackTask',['taskId'=>$id,'cursor'=>3072]);
                    else {
                        $handle('resumeTask',['taskId'=>$id]); $connections=$accept($width);
                        foreach($connections as $peer) { fwrite($peer,'second'); fclose($peer); } $connections=[];
                    }
                    $final=null; $await(static function() use($handle,$id,$mode,&$final) {
                        $final=$handle('queryTask',['taskId'=>$id,'cursor'=>$mode==='pressure'?3072:0])['result']; return $final['status']==='STOPPED';
                    });
                    $banners=array_map(static function($row) { return $row['evidence']['banner']; },$final['observations']);
                    // No third connection: resumed work must never replay the completed first step.
                    echo json_encode(['parkedStatus'=>$parked['status'],'parkedProcesses'=>$parked['activeProcesses'],
                        'workers'=>$final['workers'],'completed'=>$final['completed'],'outcome'=>$final['outcome'],
                        'first'=>count(array_filter($banners,static function($banner) { return $banner==='first'; })),
                        'second'=>count(array_filter($banners,static function($banner) { return $banner==='second'; })),
                        'extraConnection'=>is_resource(@stream_socket_accept($server,0))]);
                } finally {
                    foreach($ids as $id) $handle('stopTask',['taskId'=>$id]);
                    foreach($connections as $peer) if(is_resource($peer)) fclose($peer);
                    foreach($ids as $id) $handle('releaseTask',['taskId'=>$id]); fclose($server);
                }
                """;
        Map<String, Object> result = org.leo.phpcore.PhpTestSupport.runJson(25, "php", "-r", script, component.toString(), mode);
        int width = "budget".equals(mode) ? 16 : 1;
        assertEquals("pressure".equals(mode) ? "RUNNING" : "PAUSED", result.get("parkedStatus"));
        assertEquals(0, ((Number)result.get("parkedProcesses")).intValue());
        assertEquals(width, ((Number)result.get("workers")).intValue());
        assertEquals(width, ((Number)result.get("completed")).intValue());
        assertEquals("COMPLETED", result.get("outcome"));
        assertEquals(width, ((Number)result.get("first")).intValue());
        assertEquals("pressure".equals(mode) ? 0 : width, ((Number)result.get("second")).intValue());
        assertEquals(false, result.get("extraConnection"));
    }

    @ParameterizedTest @ValueSource(strings = {"dead", "startup", "launch"})
    void phpPoolReclaimsFailedLeasesWithoutReplayingUnknownIo(String mode) throws Exception {
        var component = java.nio.file.Path.of(getClass().getResource("/components/NetworkProbeComponent.php").toURI());
        String script = """
                $component=require $argv[1]; $handle=$component['handle']; $mode=$argv[2];
                $id='lease'.bin2hex(random_bytes(8)); $directory=$path($id); mkdir($directory,0700,true);
                $write($directory.DIRECTORY_SEPARATOR.$stateName('config'),['targets'=>[['host'=>'127.0.0.1','port'=>1]],'stages'=>['fixture-stage'],'workers'=>1]);
                $write($directory.DIRECTORY_SEPARATOR.$stateName('status'),['taskId'=>$id,'status'=>'RUNNING','outcome'=>'RUNNING','total'=>1,'completed'=>0,
                    'workers'=>1,'resultVersion'=>0,'observationOffset'=>0,'pendingItems'=>0,'pendingBytes'=>0]);
                $write($resultPath($directory.DIRECTORY_SEPARATOR.$stateName('status'),0),$emptyResults);
                try {
                    if($mode!=='launch') $poolUpdate(static function(&$pool) use($id,$mode) {
                        $pool['slots'][0]=['token'=>'stale','job'=>[$id,0],'claimed'=>$mode==='dead','deadline'=>0]; return true;
                    });
                    $handle('queryTask',['taskId'=>$id]); // Reap the dead/startup lease, or fail launching a replacement.
                    $result=$handle('queryTask',['taskId'=>$id])['result'];
                    echo json_encode(['outcome'=>$result['outcome'],'error'=>$result['error'],'processes'=>$result['activeProcesses'],'rows'=>count($result['observations'])]);
                } finally { $handle('stopTask',['taskId'=>$id]); $handle('releaseTask',['taskId'=>$id]); }
                """;
        Map<String, Object> result = org.leo.phpcore.PhpTestSupport.runJson(10,
                "php", "-d", "disable_functions=exec,shell_exec,popen", "-r", script, component.toString(), mode);
        assertEquals("FAILED", result.get("outcome"));
        assertTrue(String.valueOf(result.get("error")).contains("worker"));
        assertEquals(0, ((Number)result.get("processes")).intValue());
        assertEquals(0, ((Number)result.get("rows")).intValue());
    }

    @Test
    void persistsBinaryBannerAndKeepsAcknowledgementMonotonic() throws Exception {
        ExecutorService responder = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0)) {
            Future<?> response = responder.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.getOutputStream().write(new byte[]{0, (byte) 255, 'X'});
                }
                return null;
            });
            Map<String, Object> started = invokeComponent("NetworkProbeComponent.php", "startTask",
                    "array('plan'=>array('targets'=>array(array('host'=>'127.0.0.1','port'=>"
                            + server.getLocalPort() + ")),'stages'=>array('tcp-exchange'),'limits'=>array('threads'=>1)))");
            assertEquals(200, code(started));
            String taskId = String.valueOf(started.get("taskId"));
            try {
                Map<?, ?> info = awaitNetworkProbeTask(taskId);
                response.get(2, TimeUnit.SECONDS);
                Map<?, ?> observation = (Map<?, ?>) ((List<?>) info.get("observations")).get(0);
                Map<?, ?> evidence = (Map<?, ?>) observation.get("evidence");
                assertEquals(3, evidence.get("bytes"));
                assertEquals("ÿX", evidence.get("banner"));
                assertEquals(200, code(invokeComponent("NetworkProbeComponent.php", "queryTask",
                        "array('taskId'=>'" + taskId + "','cursor'=>1,'ackCursor'=>1)")));
                Map<String, Object> oldAck = invokeComponent("NetworkProbeComponent.php", "ackTask",
                        "array('taskId'=>'" + taskId + "','cursor'=>0)");
                assertEquals(1, oldAck.get("cursor"));
                Map<?, ?> drained = awaitNetworkProbeTask(taskId);
                assertEquals(List.of(), drained.get("observations"));
                assertEquals(1, drained.get("nextCursor"));
            } finally {
                invokeComponent("NetworkProbeComponent.php", "stopTask", "array('taskId'=>'" + taskId + "')");
                invokeComponent("NetworkProbeComponent.php", "releaseTask", "array('taskId'=>'" + taskId + "')");
            }
        } finally {
            responder.shutdownNow();
        }
    }

    @Test
    void releasedWorkerDoesNotProbeRemainingTargets() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(3000);
            String target = "array('host'=>'127.0.0.1','port'=>" + server.getLocalPort() + ")";
            Map<String, Object> started = invokeComponent("NetworkProbeComponent.php", "startTask",
                    "array('plan'=>array('targets'=>array(" + target + "," + target
                            + "),'stages'=>array('tcp-exchange'),'limits'=>array('threads'=>1,'timeout'=>3000)))");
            assertEquals(200, code(started));
            String taskId = String.valueOf(started.get("taskId"));
            try (Socket socket = server.accept()) {
                assertEquals(200, code(invokeComponent("NetworkProbeComponent.php", "stopTask", "array('taskId'=>'" + taskId + "')")));
                assertEquals(200, code(invokeComponent("NetworkProbeComponent.php", "releaseTask", "array('taskId'=>'" + taskId + "')")));
                socket.shutdownOutput();
                server.setSoTimeout(1000);
                assertThrows(SocketTimeoutException.class, () -> {
                    try (Socket unexpected = server.accept()) {
                        throw new AssertionError("released worker opened another connection");
                    }
                });
                assertEquals(404, code(invokeComponent("NetworkProbeComponent.php", "queryTask", "array('taskId'=>'" + taskId + "')")));
            } finally {
                invokeComponent("NetworkProbeComponent.php", "stopTask", "array('taskId'=>'" + taskId + "')");
                invokeComponent("NetworkProbeComponent.php", "releaseTask", "array('taskId'=>'" + taskId + "')");
            }
        }
    }

    @Test
    void emptyPlanCompletesWithoutLaunchingAProcess() throws Exception {
        Map<String, Object> started = invokeComponent("NetworkProbeComponent.php", "startTask",
                "array('plan'=>array('targets'=>array()))", true);
        assertEquals(200, code(started));
        String taskId = String.valueOf(started.get("taskId"));
        try {
            Map<?, ?> info = awaitNetworkProbeTask(taskId);
            assertEquals("COMPLETED", info.get("outcome"));
            assertEquals(0, info.get("completed"));
        } finally {
            invokeComponent("NetworkProbeComponent.php", "releaseTask", "array('taskId'=>'" + taskId + "')");
        }
    }

    private Map<?, ?> awaitNetworkProbeTask(String taskId) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            Map<String, Object> queried = invokeComponent("NetworkProbeComponent.php", "queryTask",
                    "array('taskId'=>'" + taskId + "','cursor'=>0,'maxItems'=>128,'maxBytes'=>524288,'includeEvidence'=>true)");
            assertEquals(200, code(queried));
            Map<?, ?> info = assertInstanceOf(Map.class, queried.get("result"));
            if ("STOPPED".equals(info.get("status"))) return info;
            Thread.sleep(50);
        }
        throw new AssertionError("network probe task did not finish: " + taskId);
    }
}
