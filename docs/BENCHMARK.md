# Benchmark methodology

The question is narrow: **does a known transaction binding survive an acknowledged write followed by SIGKILL and process restart?**

## Implementations

1. Naive baseline: Node Map, no persistence.
2. Upstream default: actual, unmodified `InMemoryPendingSettlementStore` from x402 core 2.31.0, revision recorded in `benchmarks/upstream/provenance.json`. Node executes its TypeScript directly. This deliberately tests the default in-memory component, not the full SDK. Its documented shared-store injection point can support durable backends; those are not compared here.
3. Recover402: forced local journal append before acknowledgement.

For each implementation, repeat 30 times: create a fresh writer process, store the same synthetic hash, wait for `READY`, SIGKILL it, start a new reader process, check hash retention. A zero for an in-memory implementation is expected by design and is **not a security vulnerability**.

Restart/query latency includes process startup; cross-language latency comparisons are not meaningful. Recovery rate is about acknowledged state, not settled money, duplicate charges, successful API delivery, or end-to-end SDK behavior. No transactions are sent.

## Other checks

- Concurrent claims from 100 tasks using 16 threads must produce a single reservation winner and no duplicate records.
- ID/terms conflict, transaction replacement, transaction reuse across attempts, a second journal owner, malformed JSON, invalid amounts and a corrupt complete journal record must be rejected.
- An incomplete journal tail must preserve earlier acknowledged records.
- 14 fixture RPC scenarios check a successful transfer, absent receipt, unavailable RPC, wrong chain, unfinalized block, changed canonical hash, wrong transaction, token, sender, recipient, amount, removed log, duplicate log and reverted transaction.
- A successful transfer must leave delivery unknown. Caller-observed delivery is separately persisted.
- HTTP sidecar claim is acknowledged, the process is killed, and a CLI reader verifies it survived.

The Java checks time 1,000 unique durable claims after 100 warmup claims. Percentiles use sorted samples (p50 index 499, p95 949, p99 989). This is a single local run, not JMH, a production capacity forecast, or a comparison of equivalent memory and durable stores. Filesystem details are recorded because the environment may acknowledge flushes much faster than physical storage.

Raw JSON captures environment, results and SHA-256 of the Java source used. CI uploads a fresh result rather than treating this run's timings as universal.
