# Benchmark results — 2026-11-18

First ladder run of the PMeasure engine on a single Windows machine,
following the benchmark protocol in
[docs/pmeasure/ARCHITECTURE.md](../../ARCHITECTURE.md): the same test
plan at increasing user counts, one variable changed at a time.

## Environment

| Item | Value |
| --- | --- |
| Generator | PMeasure `feat/netty-http-impl` @ `026783c93f`, GUI mode with JConsole attached, Windows 11 |
| Generator heap | 1 GiB for the first 10k capture (observed maximum in JConsole; a 4 GiB configuration is planned — to be confirmed before the ladder continues) |
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
| 20,000 | pending | | | | | | | |
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
