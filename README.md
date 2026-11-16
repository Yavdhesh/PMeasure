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
