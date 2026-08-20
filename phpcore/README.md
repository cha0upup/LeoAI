# phpcore

`phpcore` is the PHP runtime implementation of the shared Puppet contracts and
an equal-status sibling of `javacore`.

The shared runtime, capability, plugin and generator contracts live in `core`.
This module provides the platform-side `PhpPuppetNode`, the Java-compatible
runtime-neutral RPC client, PHP disguise validation, single-file endpoint generation
and target-side PHP components. Neither runtime module depends on or adapts the
other one.

## Delivered surface

- PHP 5.6+ minimal single-file HTTP bootstrap generation
- Minimal test/load/invoke/forward target core
- Basic info and one-shot command execution
- Process inspection/termination, network topology collection and mounted-disk enumeration
- Active network-connection inspection, persistent port scans, reachability probes,
  service management and scheduled-task management
- Event-log source discovery/query/aggregation, firewall inspection/management,
  Windows registry operations, and user/group identity inspection
- File management, chunked upload/download, ZIP compression/decompression
- PHP script execution and PDO database queries
- Platform-managed PHP source plugins
- Runtime-aware request/response disguise validation

The generator exposes three output modes. `compact` is the default and emits
whitespace-minified PHP without an `eval`/zlib bootstrap. `packed` retains the
smallest DEFLATE + Base64 representation, while `portable` emits line-oriented
plain PHP for inspection and debugging. The outer wrapper only decodes the
request, calls the generated core entry point, and encodes the result. The inner
core mirrors Java's runtime-neutral operation model for endpoint tests, relay,
component loading and component invocation.

Business and runtime inspection logic lives in independently delivered PHP
components. An invocation carries an opaque platform-assigned `componentKey`, so
the target loads only that exact version-addressed cached file without exposing
component names in cache filenames. A cache miss returns
code 424; the platform then sends the matching PHP artifact, the endpoint stores
it atomically under an endpoint-scoped directory in `sys_get_temp_dir()`, and
retries the invocation. The target omits content re-hashing while keeping
platform-assigned cache version selection. Generated core function and local
variable names vary per generation, error display is disabled, and target errors
use numeric status codes. No startup-time component preload, runtime
profile, fixed target allowlist, or remote unload operation is required. ZIP operations
need `ZipArchive`; database connections need the matching PDO driver.

The generated endpoint also derives its component-cache directory, file names,
file suffix and atomic-write prefix from the generation seed. Stateful components
derive their state namespaces and background-worker tokens from their deployed
file path, avoiding stable product names in temporary paths and process arguments.
After the endpoint reports its stable host identifier, the platform derives a
per-endpoint alias and variable-symbol map for every delivered component. Cached
source therefore carries opaque component identifiers and different local names
across endpoints, while the platform maps aliases back to the shared capability IDs.
Stateful components hash logical file roles into opaque on-disk names. Scan
progress is flushed in bounded batches, proxy status is written at most once per
five seconds, and inactive proxy/tunnel trees are reclaimed with state-aware TTLs.
Per-endpoint component variants also split diagnostic literals into seed-derived
PHP expressions while preserving the returned messages. Outbound HTTP defaults
use a deployment-stable profile consistent with the target OS, and caller-supplied
headers continue to take precedence.
On Linux, process lists, process-to-port ownership, socket tables and mounted
filesystems are collected directly from `/proc` plus PHP filesystem functions.
Unix process signaling prefers `posix_kill`; command backends remain for Windows,
macOS and restricted proc mounts.

The platform-side HTTP client derives one transport profile from the endpoint and
host identifiers. User-Agent, language, same-origin Referer, optional header set
and generated route remain stable for that session; explicit operator headers
retain precedence. Body-carrying methods only select text/API-style generated
extensions. Enabled padding uses bounded 1/2/4/8 KiB buckets with derived field
names, and repeated requests use capped exponential backoff with deterministic
jitter while retaining the original RPC request identity.

Long-running state is bounded at both sides. The generated endpoint keeps at
most 48 recently used component artifacts, expires artifacts unused for seven
days and removes abandoned atomic-write files after five minutes. Platform-side
per-endpoint source variants use a 1024-entry LRU. Terminal state writes are
atomic; scan, forward and listener state have explicit task/session caps, queue
files are limited to 8 MiB, closed connection trees are reclaimed, and startup
timeouts signal their worker before returning.

Only `packed` requires PHP's standard `base64_decode` and `gzinflate` functions.
Component source remains outside the generated endpoint and is never part of the
bootstrap payload.

## Verification

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw -pl phpcore,service,web -am test
```

Frontend runtime selection, generation, disguise editing, plugin management and
node information live in the sibling `LeoVueAi` project. Its production `dist/`
is packaged under `web/src/main/resources/static/`.
