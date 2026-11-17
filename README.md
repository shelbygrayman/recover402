# Recover402

**Keep the evidence when a paid API call loses its response.**

Java 21 library, CLI and loopback HTTP sidecar for durable payment-attempt tracking and read-only EVM transfer reconciliation. Zero runtime dependencies.

**Status: experimental prototype.** The benchmark uses local RPC fixtures and real process kills. No live x402 settlement has been exercised. This is an application recovery building block, not a payment SDK, facilitator, or guarantee against double charging.

## The problem

A facilitator broadcasts a transaction; the application crashes before it records the outcome. A fresh payment could be unnecessary, but a missing API response does not tell you whether settlement happened or the resource was delivered.

Recover402 persists attempt terms, binds a known transaction, and tracks transfer evidence independently from caller-observed delivery. It never signs, broadcasts, or authorizes a replacement payment.

## Quick start

Requires a **JDK 21+** (not just a JRE). Python 3.10+ and Node 22.18+ are needed only for tests and benchmarks. Linux local filesystems are the supported prototype environment.

```bash
bash scripts/build.sh
java -jar build/recover402.jar /tmp/recovery.journal claim demo eip155:8453 \
  0x1111111111111111111111111111111111111111 \
  0x2222222222222222222222222222222222222222 \
  0x3333333333333333333333333333333333333333 \
  1000 7595216e55c4bd365f526e17048716aa18561764cbfcf96efcb28f53eecc1a53
java -jar build/recover402.jar /tmp/recovery.journal inspect demo
java -jar build/recover402.jar /tmp/recovery.journal timeline
```

The addresses and request digest above are illustrative. Use the digest of your canonical request including method, URL, body and any identity or pricing context that changes its meaning. Amounts are integer token units, never floating point dollars.

A new claim returns `newlyCreated: true`; identical subsequent claims return `false`. Different terms for the same ID are rejected. A lost acknowledgement is treated as uncertain: inspect/reconcile rather than sending again. Use a stable ID per logical operation. Different IDs are different attempts; callers must not manufacture a new ID on retry.

## Connect to an x402 application

1. Call `Journal.reserve(...)` **before** entering the existing payment flow. Only the caller receiving `newlyCreated=true` may begin that one initial attempt under its own payment policy. A duplicate must return to recovery, not dispatch again.
2. When the facilitator provides a broadcast hash (including a `settlement_pending` response), call `bind(id, transaction)`. The network must match the original attempt.
3. If a response is lost, retain the same logical ID. Run `reconcile` with an explicitly configured RPC for that network.
4. Record `delivered` only when your application observes successful resource delivery. This is an application assertion, not cryptographic proof.

```bash
java -jar build/recover402.jar recovery.journal bind demo 0x4444444444444444444444444444444444444444444444444444444444444444
java -jar build/recover402.jar recovery.journal reconcile demo https://YOUR_BASE_RPC
```

**Critical gap:** a crash after broadcast but before `bind` leaves the transaction unknown. Recover402 cannot reconstruct a lost hash, prevent an external sender from rebroadcasting, or make a remote payment and a local journal append atomic. The sending/facilitator integration must durably expose transaction identity. An unknown attempt remains unresolved.

## HTTP sidecar

```bash
java -cp build/recover402.jar dev.recover402.Api recovery.journal 8080
```

Binds loopback only. `GET /attempts` lists records. `POST /attempts` accepts `claim`, `bind`, or `delivered`; claim fields are `id`, `network`, `asset`, `payer`, `payTo`, `amount`, `requestDigest`. Bind fields are `id`, `transaction`. There is no HTTP endpoint accepting arbitrary RPC targets. The CLI can reconcile while the sidecar is stopped; the journal allows one owning process at a time. Java callers can use `EvmReconciler` in the owning process.

Do not expose this unauthenticated sidecar on a public interface. The local caller is trusted. All commands emit JSON; CLI errors exit 2.

## Evidence states

| Settlement | Meaning |
|---|---|
| `UNRESOLVED` | No sufficient evidence. Includes absent receipt, unavailable RPC, insufficient finality or mismatching transfer. |
| `TRANSFER_FINALIZED` | Trusted RPC reports a successful canonical finalized transaction with exactly one matching ERC-20 Transfer log. |
| `TRANSACTION_REVERTED` | Trusted RPC reports that the bound transaction reverted in a canonical finalized block. |

Delivery is independently `UNKNOWN` or `OBSERVED`. `automaticPaymentAllowed` is always false: the tool never grants permission to issue another payment.

## Scope and trust boundaries

- Supports one exact ERC-20 transfer per transaction on EVM networks. Multiple logical attempts cannot bind the same transaction on the same network in one journal.
- Checks RPC chain ID, receipt transaction hash, canonical block hash/height, finalized height, status, token address, sender, recipient, integer amount and log identity. Duplicate matching logs stay unresolved.
- A matched Transfer event is **not proof of x402 authorization, receipt authenticity, resource identity, merchant honesty or delivery**. Nonstandard tokens may emit misleading events. Configure trusted token addresses and RPC operators.
- RPC reads are sequential and are not a proof against a lying node or catastrophic finalized-chain reorganization. Terminal results are retained; automatic rollback/revalidation is not implemented.
- No x402 challenge parser, signature verification, wallet handling, Solana, Permit2 semantic validation, batch settlement, refunds, resource-response cache, or distributed locking.
- The journal uses append-only checksum records, `force(true)`, and a lifetime file lock. Incomplete final records are truncated on restart; complete corrupt records fail closed. CRC detects accidental corruption, not malicious tampering. Back up journals and protect local access. Local storage only, 256 MiB startup replay limit; no compaction or encryption.
- Power-loss guarantees depend on filesystem and hardware honoring flushes. SIGKILL tests do not simulate hardware power loss.

## Reproduce the benchmark

```bash
bash scripts/test.sh
```

See [benchmark methodology](docs/BENCHMARK.md) and [raw results](benchmarks/results.json). CI compiles with all Java warnings treated as errors and reruns the suite. There are no hardcoded performance pass thresholds.

## Next integration milestone

Exercise a pinned full x402 resource server and facilitator on a local EVM chain, including signed authorization, nonce identity, lost response and a crash between broadcast and binding. Only then evaluate testnet use. Durable retention alone is not financial safety.

Apache-2.0. The benchmark includes an unmodified Apache-2.0 upstream source file with its license and exact revision recorded under `benchmarks/upstream/`.
