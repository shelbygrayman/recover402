# Recover402 benchmark results

Run date: 17 November 2026. Experimental Java 21 prototype.

## Crash retention

Writer acknowledges the recorded transaction hash, receives SIGKILL, and a fresh process tries to recover it. Thirty independent trials per implementation.

| Implementation | Transaction bindings recovered |
|---|---:|
| naive | 0/30 |
| upstream_default_store | 0/30 |
| recover402 | 30/30 |

The upstream row executes its actual unmodified default in-memory store from x402 core 2.31.0, commit `911709c32e62641f7bd4df345b2029ebe84968c8`. Losing process-local state after termination is expected. This does not compare the full SDK or an SDK configured with durable storage.

## Correctness checks

- 14/14 synthetic RPC scenarios passed.
- 100 concurrent claims produced one new reservation and no duplicate journal records.
- Restart, incomplete-tail recovery, corruption detection, transaction-binding conflicts and exclusive journal ownership passed.
- Loopback API write survived a process kill and was readable from the CLI.

## Local write timing

1,000 unique forced journal appends after 100 warmup operations:

| Metric | Microseconds |
|---|---:|
| p50 | 55.453 |
| p95 | 108.401 |
| p99 | 164.875 |

Measured on `overlayfs` in a Linux container. These timings are environment-specific; they do not measure physical-disk power-loss durability or production throughput.

## What this proves

The tested implementation retains acknowledged local attempt state across process termination and handles the listed synthetic receipt conditions conservatively.

## What remains unproven

No live payment, full signed x402 flow, mainnet/testnet settlement, cross-process exactly-once payment execution, automatic delivery recovery, or protection against a dishonest RPC has been demonstrated. A crash between broadcast and binding the hash remains unresolved. Recover402 never creates a replacement payment.

Reproduce with `bash scripts/test.sh`. Full environment, raw results and source hashes are in `benchmarks/results.json`.
