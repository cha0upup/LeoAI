package org.leo.phpcore.component;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.phpcore.PhpTestSupport;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpNetworkProbeStorageTest {
    @BeforeAll
    static void requirePhp() { assumeTrue(PhpTestSupport.phpAvailable()); }

    @Test
    void controlAndCursorReadsSkipUnrelatedSegments() throws Exception {
        Map<String, Object> result = scenario("""
                $before=$read($statusFile); $manifest=$read($resultPath($statusFile,$before['resultVersion']));
                $dataFile=$segmentPath($statusFile,$manifest['segments'][0]['id']); $hash=hash_file('sha256',$dataFile);
                $first=$handle('queryTask',['taskId'=>$id,'maxItems'=>1,'includeEvidence'=>false]);
                $handle('ackTask',['taskId'=>$id,'cursor'=>0]); $unchanged=$read($statusFile)===$before;
                rename($dataFile,$dataFile.'.held');
                try {
                    $missing=$handle('queryTask',['taskId'=>$id]);
                    $other=$handle('queryTask',['taskId'=>$id,'cursor'=>1,'maxItems'=>1]);
                    $resume=$handle('resumeTask',['taskId'=>$id]); $ready=$eligible([$id,0]);
                    $pause=$handle('pauseTask',['taskId'=>$id]); $stop=$handle('stopTask',['taskId'=>$id]);
                } finally { rename($dataFile.'.held',$dataFile); }
                $preserved=hash_file('sha256',$dataFile)===$hash;
                $handle('ackTask',['taskId'=>$id,'cursor'=>1]); $after=$read($statusFile);
                $handle('ackTask',['taskId'=>$id,'cursor'=>1]);
                $last=$handle('queryTask',['taskId'=>$id,'cursor'=>1,'maxBytes'=>1048576])['result'];
                echo json_encode(['controlBytes'=>filesize($statusFile),'readOnlyUnchanged'=>$unchanged,'preserved'=>$preserved,
                    'missingCode'=>$missing['code'],'otherCode'=>$other['code'],'ready'=>$ready,
                    'resume'=>$resume['code'],'pause'=>$pause['code'],'stop'=>$stop['code'],
                    'evidenceOmitted'=>!isset($first['result']['observations'][0]['evidence']),
                    'ackStable'=>$read($statusFile)===$after,'collected'=>!file_exists($dataFile)&&!file_exists($dataFile.'.idx'),
                    'items'=>$last['pendingItems'],'next'=>$last['nextCursor']]);
                """, "", 400000);
        assertTrue(((Number)result.get("controlBytes")).intValue() < 4096);
        for (String key : new String[]{"readOnlyUnchanged", "preserved", "ready", "evidenceOmitted", "ackStable", "collected"})
            assertEquals(true, result.get(key), key);
        for (String key : new String[]{"resume", "pause", "stop", "otherCode"}) assertEquals(200, result.get(key));
        assertEquals(500, result.get("missingCode"));
        assertEquals(2, result.get("items"));
        assertEquals(3, result.get("next"));
    }

    @Test
    void withinSegmentSeeksAndPartialAcksNeverReadOrRewriteAcknowledgedBodies() throws Exception {
        Map<String, Object> result = scenario("""
                $before=$read($statusFile); $manifest=$read($resultPath($statusFile,$before['resultVersion']));
                $file=$segmentPath($statusFile,$manifest['segments'][0]['id']);
                $stream=fopen($file,'r+b'); fwrite($stream,'!'); fclose($stream); $hash=hash_file('sha256',$file);
                $unrelated=$handle('queryTask',['taskId'=>$id,'cursor'=>1,'maxItems'=>1]);
                $bad=$handle('queryTask',['taskId'=>$id,'cursor'=>0,'maxItems'=>1]);
                $ack=$handle('ackTask',['taskId'=>$id,'cursor'=>1]); $after=$read($statusFile);
                $handle('ackTask',['taskId'=>$id,'cursor'=>1]);
                $stable=$after===$read($statusFile) && $before['resultVersion']===$after['resultVersion'];
                $unmodified=hash_file('sha256',$file)===$hash;
                $handle('ackTask',['taskId'=>$id,'cursor'=>3]);
                echo json_encode(['pageCode'=>$unrelated['code'],'badCode'=>$bad['code'],'cursor'=>$ack['cursor'],
                    'stable'=>$stable,'unmodified'=>$unmodified,'files'=>count(glob($segmentPath($statusFile,'*')))]);
                """, "", 1024);
        assertEquals(200, result.get("pageCode"));
        assertEquals(500, result.get("badCode"));
        assertEquals(1, result.get("cursor"));
        assertEquals(true, result.get("stable"));
        assertEquals(true, result.get("unmodified"));
        assertEquals(0, result.get("files"));
    }

    @ParameterizedTest @ValueSource(strings = {"results", "control"})
    void failedAckPublicationKeepsThePreviousManifestAndCursor(String failure) throws Exception {
        Map<String, Object> result = scenario("""
                $before=$read($statusFile); $manifestFile=$resultPath($statusFile,$before['resultVersion']);
                $hash=hash_file('sha256',$manifestFile);
                $blocked=$argv[2]==='results'?$resultPath($statusFile,$before['resultVersion']+1):$statusFile.'.'.getmypid().'.tmp';
                mkdir($blocked);
                try {
                    $failed=$handle('ackTask',['taskId'=>$id,'cursor'=>1]);
                    $intact=$read($statusFile)===$before && hash_file('sha256',$manifestFile)===$hash;
                } finally { rmdir($blocked); }
                $retry=$handle('ackTask',['taskId'=>$id,'cursor'=>1]);
                $last=$handle('queryTask',['taskId'=>$id,'cursor'=>1,'maxBytes'=>1048576])['result'];
                echo json_encode(['failureCode'=>$failed['code'],'intact'=>$intact,'retryCode'=>$retry['code'],
                    'cursor'=>$retry['cursor'],'next'=>$last['nextCursor'],'items'=>$last['pendingItems'],
                    'manifests'=>count(glob($resultPath($statusFile,'*')))]);
                """, failure, 400000);
        assertEquals(500, result.get("failureCode"));
        assertEquals(true, result.get("intact"));
        assertEquals(200, result.get("retryCode"));
        assertEquals(1, result.get("cursor"));
        assertEquals(3, result.get("next"));
        assertEquals(2, result.get("items"));
        assertEquals(1, result.get("manifests"));
    }

    @ParameterizedTest @ValueSource(strings = {"tail", "control", "new-segment"})
    void uncommittedDataAndIndexTailsStayInvisibleAndAreTruncatedBeforeRetry(String failure) throws Exception {
        Map<String, Object> result = scenario("""
                $before=$read($statusFile); $manifest=$read($resultPath($statusFile,$before['resultVersion']));
                $file=$segmentPath($statusFile,$manifest['segments'][0]['id']);
                $encoded=json_encode(['token'=>'new','state'=>'open','evidence'=>['body'=>$argv[2]==='new-segment'?str_repeat('x',300000):'']]);
                $publish=static function(&$status) use($statusFile,$appendRows,$encoded) {
                    $appendRows($statusFile,$status,[$encoded]); $status['pendingBytes']+=strlen($encoded); return true;
                };
                if($argv[2]==='tail') { file_put_contents($file,'{"half":',FILE_APPEND); file_put_contents($file.'.idx',"abc",FILE_APPEND); }
                else {
                    $blocked=$statusFile.'.'.getmypid().'.tmp'; mkdir($blocked);
                    try { if($statusUpdate($statusFile,$publish,true)!==false) throw new RuntimeException('commit unexpectedly succeeded'); }
                    finally { rmdir($blocked); }
                }
                $hidden=$handle('queryTask',['taskId'=>$id,'cursor'=>3])['result'];
                $intact=$read($statusFile)===$before;
                if(!$statusUpdate($statusFile,$publish,true)) throw new RuntimeException('retry failed');
                $all=$handle('queryTask',['taskId'=>$id])['result'];
                $after=$read($statusFile); $manifest=$read($resultPath($statusFile,$after['resultVersion'])); clearstatcache();
                $exact=true; $indexBytes=0; foreach($manifest['segments'] as $segment) {
                    $name=$segmentPath($statusFile,$segment['id']); $exact=$exact && filesize($name)===$segment['bytes']; $indexBytes+=filesize($name.'.idx');
                }
                echo json_encode(['hidden'=>count($hidden['observations']),'oldNext'=>$hidden['nextCursor'],'intact'=>$intact,
                    'items'=>$all['pendingItems'],'new'=>count(array_filter($all['observations'],static function($row) { return ($row['token']??'')==='new'; })),
                    'dataExact'=>$exact,'indexBytes'=>$indexBytes,'noOrphans'=>count(glob($segmentPath($statusFile,'*')))===count($manifest['segments'])*2]);
                """, failure, 1024);
        assertEquals(0, result.get("hidden"));
        assertEquals(3, result.get("oldNext"));
        assertEquals(true, result.get("intact"));
        assertEquals(4, result.get("items"));
        assertEquals(1, result.get("new"));
        assertEquals(true, result.get("dataExact"));
        assertEquals(32, result.get("indexBytes"));
        assertEquals(true, result.get("noOrphans"));
    }

    @Test
    void concurrentProducersAndCombinedQueryAcksDeliverEveryRowOnce() throws Exception {
        Map<String, Object> result = scenario("""
                $handle('ackTask',['taskId'=>$id,'cursor'=>3]); $processes=[];
                $producer=<<<'PHP'
                $component=require $argv[1]; $file=$path($argv[2]).DIRECTORY_SEPARATOR.$stateName('status');
                for($batch=0;$batch<24;$batch++) {
                    $rows=[]; for($i=0;$i<8;$i++) $rows[]=json_encode(['token'=>$argv[3].'-'.$batch.'-'.$i,'evidence'=>['body'=>str_repeat('x',1024)]]);
                    if(!$statusUpdate($file,static function(&$status) use($file,$appendRows,$rows) {
                        $appendRows($file,$status,$rows); $status['pendingBytes']+=array_sum(array_map('strlen',$rows)); return true;
                    },true)) exit(2);
                    usleep(500);
                }
                PHP;
                try {
                    for($p=0;$p<3;$p++) {
                        $command=escapeshellarg(PHP_BINARY).' -r '.escapeshellarg($producer).' '.escapeshellarg($argv[1]).' '.escapeshellarg($id).' '.$p;
                        $pipes=[]; $process=proc_open($command,[0=>['pipe','r'],1=>['pipe','w'],2=>['pipe','w']],$pipes);
                        if(!is_resource($process)) throw new RuntimeException('producer unavailable');
                        fclose($pipes[0]); $processes[]=[$process,$pipes];
                    }
                    $cursor=3; $seen=[]; $duplicates=0; $deadline=microtime(true)+10;
                    while($cursor<579 && microtime(true)<$deadline) {
                        $page=$handle('queryTask',['taskId'=>$id,'cursor'=>$cursor,'ackCursor'=>$cursor,'maxItems'=>37,'maxBytes'=>40000]);
                        if($page['code']!==200) throw new RuntimeException('concurrent query failed');
                        foreach($page['result']['observations'] as $row) {
                            if(isset($seen[$row['token']])) $duplicates++; $seen[$row['token']]=true;
                        }
                        $cursor=$page['result']['nextCursor']; usleep(1000);
                    }
                    $handle('ackTask',['taskId'=>$id,'cursor'=>$cursor]);
                    $last=$handle('queryTask',['taskId'=>$id,'cursor'=>$cursor])['result'];
                    $exits=[]; foreach($processes as $pair) { fclose($pair[1][1]); fclose($pair[1][2]); $exits[]=proc_close($pair[0]); } $processes=[];
                    echo json_encode(['seen'=>count($seen),'duplicates'=>$duplicates,'cursor'=>$cursor,'items'=>$last['pendingItems'],
                        'bytes'=>$last['pendingBytes'],'files'=>count(glob($segmentPath($statusFile,'*'))),'exits'=>$exits]);
                } finally { foreach($processes as $pair) { proc_terminate($pair[0]); fclose($pair[1][1]); fclose($pair[1][2]); proc_close($pair[0]); } }
                """, "", 1024);
        assertEquals(576, result.get("seen"));
        assertEquals(0, result.get("duplicates"));
        assertEquals(579, result.get("cursor"));
        for (String key : new String[]{"items", "bytes", "files"}) assertEquals(0, result.get(key));
        assertEquals(java.util.List.of(0, 0, 0), result.get("exits"));
    }

    private Map<String, Object> scenario(String body, String argument, int recordBytes) throws Exception {
        Path component = Path.of(getClass().getResource("/components/NetworkProbeComponent.php").toURI());
        String setup = """
                $component=require $argv[1]; $handle=$component['handle'];
                $id=$handle('startTask',['plan'=>['targets'=>[]]])['taskId'];
                $statusFile=$path($id).DIRECTORY_SEPARATOR.$stateName('status');
                try {
                    if(!$statusUpdate($statusFile,static function(&$status) use($appendRows,$statusFile,$argv) {
                        $row=json_encode(['state'=>'open','evidence'=>['body'=>str_repeat('x',(int)$argv[3])]]);
                        $status['status']='PAUSED'; $status['outcome']='RUNNING'; $status['total']=3; $status['completed']=3;
                        $appendRows($statusFile,$status,array_fill(0,3,$row)); $status['pendingBytes']=3*strlen($row); return true;
                    },true)) throw new RuntimeException('fixture initialization failed');
                """;
        String cleanup = """
                } finally { $handle('stopTask',['taskId'=>$id]); $handle('releaseTask',['taskId'=>$id]); }
                """;
        return PhpTestSupport.runJson(20, "php", "-r", setup + body + cleanup, component.toString(), argument, String.valueOf(recordBytes));
    }
}
