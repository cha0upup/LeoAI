<?php
$components = $argv[1]; $base = $argv[2]; $case = $argv[3];
chdir($base); $base = realpath($base);
function check($condition, $message) { if (!$condition) throw new RuntimeException($message); }
function leo_binary($data) { return $data; }
function invokeFile($name, $params) {
    global $components;
    $component = require $components . '/' . $name . '.php';
    return $component['handle'](isset($params['action']) ? $params['action'] : '', $params);
}
function rejects($operation) {
    try { $result = $operation(); return isset($result['code']) && $result['code'] !== 200; }
    catch (Exception $error) { return true; }
}
if ($case === 'chunks') {
    $file = $base . '/data'; file_put_contents($file, 'original');
    check(rejects(function () use ($file) { return invokeFile('FileUploadComponent', ['path' => $file, 'offset' => -1, 'data' => 'X']); }), 'negative offset accepted');
    check(file_get_contents($file) === 'original', 'invalid request changed file');
    check(rejects(function () use ($file) { return invokeFile('FileUploadComponent', ['path' => $file, 'offset' => 0, 'data' => str_repeat('x', 1048577)]); }), 'oversized upload accepted');
    $written = invokeFile('FileUploadComponent', ['path' => $file, 'offset' => 1, 'data' => 'abc']);
    check($written['bytesWritten'] === 3 && $written['nextOffset'] === 4, 'write contract');
    $read = invokeFile('FileDownloadComponent', ['path' => $file, 'offset' => 1, 'size' => 3]);
    check($read['data'] === 'abc' && $read['bytesRead'] === 3, 'read contract');
    check(rejects(function () use ($file) { return invokeFile('FileDownloadComponent', ['path' => $file, 'offset' => 99, 'size' => 1]); }), 'out of range accepted');
} elseif ($case === 'short-write') {
    class ShortWriteFixture {
        public $context; private $calls = 0;
        public function stream_open($p, $m, $o, &$opened) { return true; }
        public function stream_lock($operation) { return true; }
        public function stream_seek($o, $w) { return true; }
        public function stream_tell() { return 0; }
        public function stream_write($data) { return $this->calls++ === 0 ? 2 : 0; }
        public function stream_close() {}
    }
    stream_wrapper_register('shortfixture', 'ShortWriteFixture');
    check(rejects(function () { return invokeFile('FileUploadComponent', ['path' => 'shortfixture://file', 'offset' => 0, 'data' => 'abcdef']); }), 'short write succeeded');
} elseif ($case === 'zip-roundtrip') {
    mkdir('source'); mkdir('source/excluded'); mkdir('source/empty');
    file_put_contents('source/keep.txt', 'hello'); file_put_contents('source/drop.log', 'drop'); file_put_contents('source/excluded/child.txt', 'drop');
    $result = invokeFile('CompressComponent', ['src' => 'source', 'des' => 'source/out.zip', 'exclude' => '.*\.log|excluded']);
    check($result['code'] === 200, 'compression failed');
    $archive = new ZipArchive(); check($archive->open('source/out.zip') === true, 'invalid zip');
    check($archive->locateName('keep.txt') !== false && $archive->locateName('drop.log') === false && $archive->locateName('excluded/child.txt') === false, 'exclusion failed');
    check($archive->locateName('out.zip') === false, 'archive included itself'); $archive->close();
    mkdir('output'); file_put_contents('output/keep.txt', 'previous'); chmod('output/keep.txt', 0600);
    $result = invokeFile('DecompressComponent', ['src' => 'source/out.zip', 'des' => 'output', 'format' => 'zip']);
    check($result['code'] === 200 && file_get_contents('output/keep.txt') === 'hello' && is_dir('output/empty'), 'roundtrip failed');
    check((fileperms('output/keep.txt') & 0777) === 0600, 'permissions changed');
    check(rejects(function () { return invokeFile('DecompressComponent', ['src' => 'source/out.zip', 'des' => 'bad', 'format' => 'gzip']); }), 'unsupported format accepted');
} elseif ($case === 'zip-links') {
    mkdir('output'); mkdir('outside'); file_put_contents('outside/keep.txt', 'original'); symlink($base . '/outside', 'output/linked');
    $archive = new ZipArchive(); $archive->open('input.zip', ZipArchive::CREATE); $archive->addFromString('linked/keep.txt', 'overwrite'); $archive->close();
    check(rejects(function () { return invokeFile('DecompressComponent', ['src' => 'input.zip', 'des' => 'output']); }), 'external link followed');
    check(file_get_contents('outside/keep.txt') === 'original', 'external file overwritten');
} elseif ($case === 'zip-limits') {
    $archive = new ZipArchive(); $archive->open('input.zip', ZipArchive::CREATE);
    for ($i = 0; $i < 10001; $i++) $archive->addFromString('file-' . $i, '');
    $archive->close();
    check(rejects(function () { return invokeFile('DecompressComponent', ['src' => 'input.zip', 'des' => 'output']); }), 'entry limit ignored');
    check(count(glob('output/*')) === 0, 'wrote entries before validating limits');
} elseif ($case === 'zip-failure') {
    mkdir('source'); file_put_contents('source/readable.txt', 'fixture');
    if (!function_exists('posix_mkfifo')) { echo "SKIP fifo unavailable\n"; exit(0); }
    posix_mkfifo('source/fifo', 0600); file_put_contents('existing.zip', 'original');
    check(rejects(function () { return invokeFile('CompressComponent', ['src' => 'source', 'des' => 'existing.zip']); }), 'special file accepted');
    check(file_get_contents('existing.zip') === 'original', 'existing archive destroyed');
    check(count(glob('.leo-compress-*')) === 0, 'temporary archive leaked');
} elseif ($case === 'profile') {
    $r = invokeFile('FileComponent', ['action' => 'profile']); $c = $r['capabilities'];
    check($c['rename'] === false && $c['copyDirectory'] === false && $c['extractionFormats'] === ['zip'] && $c['maxUploadChunkBytes'] === 1048576, 'incorrect capabilities');
} else { throw new RuntimeException('unknown case'); }
echo "PASS " . $case . "\n";
