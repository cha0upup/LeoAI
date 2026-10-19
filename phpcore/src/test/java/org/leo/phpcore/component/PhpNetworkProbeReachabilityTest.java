package org.leo.phpcore.component;

import org.junit.jupiter.api.Test;
import org.leo.phpcore.PhpTestSupport;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PhpNetworkProbeReachabilityTest {
    @Test
    void discoveryUsesOneHostTargetAndStopsAfterTheFirstAnswer() throws Exception {
        assumeTrue(PhpTestSupport.phpAvailable());
        Path component = Path.of(getClass().getResource("/components/NetworkProbeComponent.php").toURI());
        try (ServerSocket first = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
             ServerSocket second = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
            Map<String, Object> response = PhpTestSupport.runJson(10, "php", "-r", """
                    $component=require $argv[1];
                    $started=$handle('startTask',['plan'=>['targets'=>[
                        ['host'=>'127.0.0.1','targetId'=>'127.0.0.1','stage'=>'host-reachability',
                         'ports'=>[(int)$argv[2],(int)$argv[3]],'javaReachability'=>false]],
                        'stages'=>['host-reachability'],'limits'=>['threads'=>1,'timeout'=>100]]]);
                    if($started['code']!==200) { echo json_encode($started); exit; }
                    $id=$started['taskId'];
                    try {
                        for($i=0;$i<20;$i++) {
                            $runTurn([$id,0]); $result=$handle('queryTask',['taskId'=>$id]);
                            if(($result['result']['status']??'')==='STOPPED') break;
                        }
                        echo json_encode($result);
                    } finally { $handle('stopTask',['taskId'=>$id]); $handle('releaseTask',['taskId'=>$id]); }
                    """, component.toString(), String.valueOf(first.getLocalPort()), String.valueOf(second.getLocalPort()));
            assertEquals(200, response.get("code"), response.toString());
            var result = (Map<?, ?>) response.get("result");
            assertEquals("COMPLETED", result.get("outcome")); assertEquals(1, result.get("completed"));
            var rows = (List<?>) result.get("observations"); assertEquals(1, rows.size());
            assertEquals(true, ((Map<?, ?>) rows.get(0)).get("hostComplete"));
            assertEquals(first.getLocalPort(), ((Map<?, ?>) rows.get(0)).get("port"));
            second.setSoTimeout(30);
            assertThrows(java.net.SocketTimeoutException.class, second::accept);
        }
    }
}
