# JMeter Engine — Architecture, Definitions, Benchmark Protocol

Project: keep Apache JMeter's look and feel (GUI, .jmx plans, sampler model)
unchanged; re-engineer the internals for maximum load-generation performance.
Home: dedicated repo **Yavdhesh/PMeasure** (user decision 2026-11-16).
GitHub allows only one fork of apache/jmeter per account and Yavdhesh/jmeter
already exists, so PMeasure is created by pushing a FULL CLONE (complete
history) into a new repo — structurally separate from the PR fork, which
makes PR #6855 isolation absolute. Base content: upstream master, with the
virtual-thread commit 0e7496e as the engine branch's starting point.
Attribution: Apache-2.0 LICENSE and JMeter's NOTICE are retained; README
states PMeasure is a modified derivative of Apache JMeter, not affiliated
with or endorsed by the ASF ("JMeter" is an ASF trademark; the product
name is PMeasure).

Status: DRAFT for user review (2026-11-16). No code yet.

---

## 1. Clear definitions (used identically in code, docs and benchmarks)

- **Virtual user (VU):** one simulated user = one JMeterThread executing the
  plan's sampler loop (with timers/think time) until duration or iterations end.
- **Concurrent users:** VUs started and not yet finished at a moment in time.
  This is the number people quote ("50,000 concurrent users").
- **In-flight requests:** requests sent whose response has not fully arrived.
  In-flight = concurrent_users x (response_time / (response_time + think_time)).
  Example: 50,000 users, 200 ms response, 1 s think time -> ~8,300 in flight.
  Sockets, connections and server load are driven by in-flight, not by VU count.
- **Connection:** one TCP (+TLS) connection to a target host:port. With
  keep-alive, one VU typically holds one connection per target host.
- **Closed workload model:** JMeter's model — a fixed set of VUs looping;
  throughput (req/s) is an *outcome*, not an input. (Gatling/k6 can also run
  open models; we stay closed for JMeter compatibility.)
- **Think time:** delay a VU inserts between samples (timers). Realistic load
  has think time; stress tests often set it to zero.
- **Sustainable users (benchmark gate):** the highest VU count held for
  10 minutes with error rate < 0.1% AND p99 response time within 2x of the
  low-load p99 for the same plan, AND zero samples dropped by result reporting.
- **Sample:** one executed sampler producing one SampleResult.

## 2. Why Netty — direct answer to "10k / 20k / 50k concurrent users?"

Yes: Netty is precisely the layer that makes those numbers plausible, because
at that scale the binding costs are NOT threads (virtual threads already made
users cheap — PR #6855 ran 2,000 users on 30 OS threads). The remaining costs
are, in order:

1. **Per-request CPU and allocation in the HTTP client.** HttpClient4 builds
   and tears down substantial object graphs per request. At ~10k samples/sec
   this becomes generator CPU + GC pressure. Netty's pooled buffers
   (PooledByteBufAllocator) and pipeline design cut per-request allocation
   drastically — allocation-first, per our doctrine.
2. **Connection handling at scale.** Tens of thousands of connections, TLS
   handshakes on churn, half-open detection, precise connect/read timeouts —
   an event-loop client manages all of this on a few threads; a blocking
   client involves a thread in every wait.
3. **Result reporting.** At 10k+ samples/sec, storing every SampleResult
   (stock listeners) exhausts heap and distorts the test. Aggregation into
   histograms is mandatory at scale, independent of the HTTP client.

Netty is **necessary but not sufficient** at 20k–50k. The full stack is:
virtual threads (cheap users) + Netty (cheap I/O) + histogram result pipeline
(cheap reporting) + OS tuning. Hard physical limits to respect:

- **Ephemeral ports:** one generator IP can open at most ~64k connections to
  ONE target IP:port. 50k keep-alive connections to a single endpoint is
  near that edge; beyond it needs multiple source IPs or multiple nodes.
- **File descriptors:** 50k connections = 50k+ fds; ulimit must be raised.
- **Per-VU heap state:** JMeterContext + variables + sampler state per user.
  Even ~50 KB/user x 50k = ~2.5 GB before a single sample is produced.
  VU state must stay lean; this bounds single-node VU count more than I/O does.
- **TLS:** at high connection churn, handshake CPU dominates; session
  resumption and keep-alive discipline matter more than raw client choice.

Realistic single-node targets (one strong generator host, LAN target):
10k concurrent users — solid engineering target. 20k — achievable with the
full stack + tuning. 50k — stretch goal; expect per-VU state and port limits
to be the fight, and distributed generation may be the honest answer.

## 3. Architecture — layers

Everything above the transport line is stock JMeter, unchanged.

```
  GUI / .jmx plan / listeners            (unchanged)
  ------------------------------------------------
  Test element tree: ThreadGroup, samplers, pre/post-processors,
  assertions, timers, config elements    (unchanged semantics)
  ------------------------------------------------
  JMeterThread per VU — on VIRTUAL THREADS (PR #6855, done)
    - runs the procedural sampler loop exactly as today
    - owns per-user session state: cookies, variables, auth, redirects
  ------------------------------------------------
  HTTP sampler facade (per sampler, on the VU's virtual thread)
    - implementation chosen by the existing Implementation dropdown:
      HttpClient4 | Java | Netty (NEW)
    - ALL JMeter semantics live here: HeaderManager, CookieManager,
      auth, redirect following, multipart encoding, proxy config,
      SampleResult construction, error-code mapping
  ------------------------------------------------
  Netty transport (shared singleton, stateless per request)
    - EventLoopGroup (few threads; never blocked)
    - per-target channel pools, keep-alive, TLS (SslHandler),
      connect/read timeouts enforced in the pipeline
    - request spec in -> CompletableFuture<raw response> out
    - the calling virtual thread parks on the future; the event loop
      completes it. No OS thread is held by a waiting request.
  ------------------------------------------------
  Result pipeline
    - SampleResults handed to listeners via a lock-free MPSC queue
    - aggregators update HDR histograms per sampler label
      (percentiles without storing samples)
    - error samples retained; periodic snapshots for CLI/GUI reporting
    - reporting NEVER blocks a user thread (observer must not
      perturb the load); any drop is counted and reported, never silent
```

### The one design rule

**Transport is dumb and shared; semantics are per-user and stay on the
virtual thread.** Netty knows bytes, connections and timeouts. It knows
nothing about cookies, users, or test plans. Every JMeter behaviour a plan
can observe (redirects followed, cookies resent, assertions evaluated,
error strings) is produced by the facade + JMeterThread layers, identically
for all three HTTP implementations — so a plan can mix implementations and
results stay comparable.

### Failure semantics

Netty-side failures are mapped to the same SampleResult response codes and
messages the HttpClient4 implementation produces for the equivalent failure
(connect timeout vs read timeout vs TLS error vs connection reset), so
existing assertions and historical result comparisons remain meaningful.

## 4. Isolation from PR #6855 (hard rule, user 2026-11-16)

This project's work must not impact the open PR #6855 in any way.

- The PR branch `feat/virtual-threads-for-user-threads` is never committed
  to, rebased, or force-pushed by this project. Its head stays at
  0e7496e1633dd4bb2190282daa92d5e431008e21 unless the user himself decides
  to update the PR.
- The engine branch is a separate branch (stacked from the PR branch tip,
  since the virtual-thread layer is its base, or from master), developed
  in a separate clone. Creating a branch FROM the PR tip does not modify
  the PR branch — a branch is just a pointer; the PR's commits, diff and
  checks are computed from its own head only.
- Only the engine branch is ever pushed to the fork, and only with the
  user's approval (same pattern as the Dragonfly work).
- The fork's master is not pushed to either.
- After every engine work session, the PR head SHA is re-verified against
  0e7496e and reported. The hourly PR tracker also watches #6855.

## 5. What we are NOT changing

- **JMeter's architecture principles stay (user, 2026-11-16).** The element
  tree, sampler contracts, thread-group model, listener model and properties
  system are the architecture. Performance work happens strictly inside
  them, through JMeter's own extension points (sampler implementations,
  thread factory, listeners) — no parallel plan model, no broken contracts.
  The goal is performance improvement by huge leaps at massive scale
  (10k–50k concurrent users), proven by the benchmark protocol in section 6,
  not a redesign of the tool.
- GUI, plan format (.jmx), element model, JMeter properties.
- Default behaviour: HttpClient4 remains the default HTTP implementation;
  Netty is opt-in per sampler via the existing dropdown.
- Thread-group semantics, ramp-up, schedulers, distributed mode.
- PR #6855 (virtual threads) is layer 1 and already exists; this project
  builds on it, it does not redo it.

## 6. Benchmark protocol (fixed; deviations must be documented)

**Target:** dedicated stub server on a separate host, capacity proven at
least 2x above the generator ceiling being measured (target CPU < 50% at
peak). Stub profiles: fixed 1 KB body; added fixed latency variants
0 ms / 50 ms / 200 ms.

**Plan:** one .jmx — single HTTP sampler, keep-alive on. Two think-time
profiles: (a) zero think time (stress), (b) 1 s think time (realistic).

**Configurations (same machine, same JDK, same OS tuning, recorded):**
- A: platform threads + HttpClient4 (stock JMeter)
- B: virtual threads + HttpClient4 (PR #6855)
- C: virtual threads + Netty sampler (this project)

**Scale ladder:** 1k -> 5k -> 10k -> 20k -> 50k concurrent users.
At each rung: ramp, hold 10 minutes, record.

**Metrics at each rung:** sustainable-users gate (see definitions),
achieved req/s, p50/p95/p99 response time, error rate, generator CPU %,
RSS, GC time %, OS thread count, samples dropped by reporting (must be 0).

**Rules:** warm-up excluded from measurement; 3 runs per configuration,
report the median; generator and target on separate hosts (1 GbE or better,
LAN latency); ulimit/sysctl settings recorded with results; a configuration
that fails the gate at a rung is reported as failing — no silent re-runs.

**Deliverable:** the ceiling table — for each configuration, the highest
sustainable rung and the resource that bounded it (CPU, heap, GC, ports,
fds). Netty ships only if C beats B on measured numbers.

## 7. Open decisions for review

1. **JDK baseline: DECIDED 2026-11-16 — JDK 25 (LTS).** Rationale: LTS
   (17 -> 21 -> 25 cadence, GA Sep 2025); includes JEP 491 (virtual
   threads no longer pin carrier threads on synchronized blocks — JDK 24),
   which matters because JMeter and HttpClient4 code paths synchronize;
   compact object headers (JEP 519, experimental in 25) are directly
   relevant to per-VU heap state at 50k users. Accepted trade-off: the
   code diverges from upstream JMeter's Java 17 baseline, so upstreaming
   this engine later would need a compatibility pass — the fork is the
   home, so this is acceptable. All benchmark baselines (A/B/C) are
   re-measured on JDK 25; PR #6855's JDK 21 numbers are not reused.
2. **EventLoopGroup sizing:** default = available cores; benchmark at
   cores and 2x cores before fixing.
3. **Single-node 50k vs distributed:** proposal — prove 10k/20k single-node
   first; treat 50k as a stretch rung whose result decides whether a
   distributed milestone exists.
4. **Histogram library:** HdrHistogram (industry standard, tiny) as a new
   dependency — needs an explicit yes, JMeter is dependency-careful.

## 8. Milestones (after architecture approval)

- M1: Netty transport + HTTP sampler facade (GET/POST, keep-alive, TLS,
  timeouts, error mapping). Gate: plan-level equivalence vs HttpClient4.
- M2: Result pipeline (MPSC queue + HDR aggregation + CLI snapshot).
  Gate: 10k samples/sec reporting with 0 drops, user threads unblocked.
- M3: Benchmark ladder A/B/C, ceiling table published in repo.
- M4 (only if numbers justify): per-VU state diet, port/fd tuning guide,
  50k attempt.
