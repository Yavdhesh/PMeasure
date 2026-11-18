# PMeasure

Load and performance testing with a re-engineered engine.

PMeasure keeps the Apache JMeter test-plan model, GUI and .jmx format, and
rebuilds the internals for maximum load-generation performance:

- **Virtual threads** for user threads — one virtual thread per user
  instead of one OS thread per user
- **Netty transport** for HTTP sampling — non-blocking I/O behind the
  HTTP sampler's existing Implementation dropdown
- **Histogram-based result pipeline** — percentiles computed without
  storing every sample

Baseline: **JDK 25 (LTS)**. Design, definitions and the benchmark
protocol live in
[docs/pmeasure/ARCHITECTURE.md](docs/pmeasure/ARCHITECTURE.md).

## Configuration

PMeasure's engine features are opt-in and configured with ordinary
JMeter properties, so existing test plans run unchanged until a feature
is switched on.

### Virtual threads

By default, every simulated user runs on its own OS (platform) thread,
exactly as in Apache JMeter. Setting `jmeterthread.use_virtual_threads`
runs each user on a virtual thread instead, so tens of thousands of
users no longer need tens of thousands of OS threads.

| Property | Default | Effect |
| --- | --- | --- |
| `jmeterthread.use_virtual_threads` | `false` | `true` runs user threads on virtual threads (JDK 21+ runtime; on an older JVM the property is ignored and platform threads are used) |

Enable it for one launch with the `-J` command-line option:

```
jmeter -Jjmeterthread.use_virtual_threads=true
```

Or enable it permanently by adding the property to `bin/user.properties`:

```
jmeterthread.use_virtual_threads=true
```

To verify it is active, watch the GUI's thread indicator (top right)
during a run: it counts simulated users, while the process's OS thread
count — for example the Threads column in Windows Task Manager —
stays in the hundreds instead of growing one-per-user.

### Netty HTTP implementation

The HTTP sampler gains a fourth entry in its **Implementation**
dropdown: **Netty** (the others remain HttpClient4, HttpClient5 and
Java). The selection is per sampler and is stored in the plan as the
`HTTPSampler.implementation` property. An empty Implementation field
means "use the default implementation", which is HttpClient4 unless
the default is changed with the `jmeter.httpsampler` property:

| Property | Default | Effect |
| --- | --- | --- |
| `jmeter.httpsampler` | `HttpClient4` | Implementation used by samplers whose Implementation field is empty; one of `HttpClient4`, `HttpClient5`, `Java`, `Netty` |

For example, adding this line to `bin/user.properties` makes Netty the
default for every HTTP sampler that does not name an implementation:

```
jmeter.httpsampler=Netty
```

Current scope of the Netty implementation: HTTP/1.1, GET and POST,
form parameters, raw request bodies and a single file as the body.
Multipart bodies, proxies and authentication managers are not yet
supported and fail the sample with an explanatory error.

### Netty tuning

One Netty event loop group and one channel pool per target are shared
by all users. Channels are pooled and reused (keep-alive); a new
channel is opened whenever all pooled channels are busy, so the pool
never throttles a test. Connect and response timeouts are taken from
the sampler's own timeout fields — there are no separate Netty timeout
properties.

| Property | Default | Effect |
| --- | --- | --- |
| `pmeasure.netty.eventloops` | number of available processors | Number of Netty event loop threads; unset or `0` means one per processor |

For example, to run with eight event loops, add to
`bin/user.properties`:

```
pmeasure.netty.eventloops=8
```

Whether one loop per processor or two is faster at scale is a
benchmark question; the architecture document defines how it is
measured.

For diagnostic runs, Netty's buffer leak detector can be raised to
its strictest level. Leaks, if any, are then reported as `LEAK:`
entries in the log. This is a checking mode, not a run setting —
it adds overhead and should not be used for measured runs:

```
# PowerShell, before launching
$env:JVM_ARGS = "-Dio.netty.leakDetection.level=PARANOID"
```

### Load generator memory

The launcher's default heap (1 GB) is sized for ordinary JMeter use.
Large PMeasure runs — many thousands of users — need more. The
launcher reads its heap settings from the `HEAP` environment variable;
setting it replaces the default wholesale, so keep the metaspace part:

```
# PowerShell, for the current session
$env:HEAP = "-Xms2g -Xmx4g -XX:MaxMetaspaceSize=256m"
```

## Status

Engine work in progress. The `master` branch is the upstream base plus
the PMeasure project documents; active engine development happens on the
`engine` branch. Performance claims are published only as measured
results produced by the benchmark protocol in the architecture document
(stock vs virtual threads + HttpClient4 vs virtual threads + Netty).

## Provenance and license

PMeasure builds on the Apache JMeter codebase (Apache License 2.0); see
NOTICE. Apache JMeter is a trademark of the Apache Software Foundation.
PMeasure is a modified derivative, is not affiliated with or endorsed by
the ASF, and is developed independently. The original code and its
LICENSE and NOTICE files are retained unchanged.

## Author

PMeasure is authored and maintained by **Yavdhesh Sanchihar**
(YuvonAI and Tech Inc.).
