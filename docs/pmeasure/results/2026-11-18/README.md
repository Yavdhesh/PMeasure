# Benchmark results — 2026-11-18

First ladder run of the PMeasure engine on a single Windows machine,
following the benchmark protocol in
[docs/pmeasure/ARCHITECTURE.md](../../ARCHITECTURE.md): the same test
plan at increasing user counts, one variable changed at a time.

## Environment

| Item | Value |
| --- | --- |
| Generator | PMeasure `feat/netty-http-impl` @ `026783c93f`, GUI mode with JConsole attached, Windows 11 |
| Generator heap | 1 GiB for the 10k captures; 4 GiB for the first 20k attempt (ended in OutOfMemoryError); 8 GiB from the 20k re-run onward |
| Target application | JvmPerformanceLab (Spring Boot), Tomcat with a virtual-thread executor for requests |
| Target heap | 2 GiB |
| Test plan | 10 s ramp-up, 300 s duration (scheduler), loop forever |
| Generator runtime | 32 processors available to the JVM |

## Series B — virtual threads + HttpClient4

Configuration: `jmeterthread.use_virtual_threads=true`, HTTP
implementation HttpClient4. Screenshots per rung are stored in
`b-vt-httpclient4/<users>/`.

| Users | Throughput (req/s) | Avg (ms) | Max (ms) | Error % | Generator heap used | Generator CPU | Virtual threads (mounted / queued) | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 10,000 | not yet recorded | – | – | – | ~820 MB of 1 GiB | ~63% | 32 / 9,934 | Target old generation full at 1.502 GiB; last target GC cause "G1 Humongous Allocation" |
| 20,000 | invalid — see notes | – | – | – | – | – | – | First attempt capped at ~16k concurrent requests and the target had been restarted without virtual threads; re-run required |
| 30,000 | pending | | | | | | | |
| 40,000 | pending | | | | | | | |
| 50,000 | pending | | | | | | | |

Throughput, latency and error columns come from the plan's Summary
Report and are filled in as they are recorded; the remaining columns
are read from the JConsole screenshots filed for each rung.

### 10,000 users — evidence

Generator (JMeter process):

- [JConsole Monitor](b-vt-httpclient4/10k/generator-jconsole-monitor.jpg) —
  heap size/max 1 GiB, used ~820 MB; CPU usage 63.5%, GC activity 0.1%;
  virtual threads: parallelism 32, mounted 32, queued 9,934
- [JConsole Visual GC](b-vt-httpclient4/10k/generator-jconsole-visualgc.jpg) —
  4,401 collections, 31.212 s total GC time (cumulative, see method
  notes); old generation 851 MB used; last cause G1 Evacuation Pause

Target application:

- [JConsole Monitor](b-vt-httpclient4/10k/app-jconsole-monitor.jpg) —
  heap size/max 2 GiB, used ~1.54 GiB at capture (idle); application
  virtual threads queue to ~5,000 at run peaks
- [JConsole Visual GC](b-vt-httpclient4/10k/app-jconsole-visualgc.jpg) —
  67,957 collections, 2 min 36.684 s total GC time (cumulative);
  old generation 1.502 GiB used of 1.502 GiB capacity; last cause
  G1 Humongous Allocation

### 20,000 users — first attempt (invalid, to be re-run)

Two defects make this attempt unusable as a rung:

1. **The target was restarted without its virtual-thread executor.**
   The application process changed (pid 129724 → 162408), and in the
   new process the Virtual Threads panel stays at zero through the
   load windows, where the previous process queued ~5,000 virtual
   threads at run peaks. Tomcat therefore appears to have fallen back
   to its default platform-thread pool, making the target itself a
   bottleneck. Evidence:
   [application JConsole Monitor](b-vt-httpclient4/20k/app-jconsole-monitor.jpg).
2. **Concurrent requests from the generator capped at ~16k** instead
   of reaching 20,000. Prime suspect: the Windows dynamic (ephemeral)
   port range, 49152–65535 by default — exactly 16,384 ports, one
   consumed per keep-alive connection from the generator to the
   target for the whole run. Alternative suspect: generator heap
   (the 10k rung already used ~890 MB of the 1 GiB maximum).
   Discriminating checks: search `jmeter.log` for
   `OutOfMemoryError` versus "Cannot assign requested address", and
   `netsh int ipv4 show dynamicport tcp` on the generator.
3. **Generator heap exhausted.** The 20k attempt ran with a 4 GiB
   generator heap and ended in OutOfMemoryError — with the target
   throttled to 200 platform threads, in-flight samples piled up on
   the generator. The generator heap was raised to 8 GiB for the
   re-run. The ephemeral-port ceiling above remains a separate,
   still-standing limit for the higher rungs.

After `spring.threads.virtual.enabled=true` was added to the
target's `application.properties` and the target restarted
(pid 66648), its Virtual Threads panel shows activity under load
again — queued virtual threads spiking to ~660 in the first load
burst, where the defective process showed a flat zero:
[application JConsole Monitor, VT check](b-vt-httpclient4/20k/app-jconsole-monitor-vt-check.jpg).
Queue depth measures backlog, not usage; the earlier process queued
~5,000 because it was drowning, so a smaller queue on a draining
target is the healthy picture.

Implication for the ladder: on a single Windows generator talking to
a single target endpoint, concurrent connections are bounded by the
ephemeral port range (~16k by default, ~64k maximum if the range is
widened), independently of threads or heap. The 30k–50k rungs need
the range widened and/or additional source addresses — or a second
generator.

## Series C — virtual threads + Netty

Same ladder with the HTTP implementation set to Netty. Pending;
screenshots will be stored in `c-vt-netty/<users>/` and the table
above extended with the matching rows.

## Method notes

- GUI mode with JConsole attached adds monitoring overhead to the
  generator. These runs verify engine behaviour and locate the
  scaling knee; publishable numbers come from non-GUI runs, per the
  benchmark protocol.
- GC counters on the Visual GC tab are cumulative since the profiled
  process started (the generator process had 50 minutes of uptime at
  the 10k capture and had served several runs). Per-rung GC
  attribution therefore uses deltas between captures, or GC logs,
  not the raw counters.
- A rung is counted as held when the error rate stays below 0.1%
  and p99 latency stays within 2× of the low-load p99 for the full
  300 s, per the architecture document's gate.
