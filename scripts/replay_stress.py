"""Replay PaidFor's Base USDC exact-payment stress export with Python 3.10+.

Each input represents one buyer and one token on Base. Each (test, intent)
represents one logical purchase. See the repository README for a pinned input.
"""

import argparse
from collections import defaultdict
from decimal import Decimal
import json
from pathlib import Path
import re
import statistics
import sys
import time

HASH = re.compile(r"0x[0-9a-fA-F]{64}")
ADDRESS = re.compile(r"0x[0-9a-fA-F]{40}")
USDC = re.compile(r"[0-9]+(?:\.[0-9]{1,6})?")
BASE_USDC = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"


def checked_string(value, pattern, label):
    if not isinstance(value, str) or not pattern.fullmatch(value):
        raise ValueError(f"invalid {label}")
    return value.lower()


def group_key(row):
    key = (row["test"], row["intent"])
    if not all(isinstance(part, str) and part.strip() for part in key):
        raise ValueError("expected nonempty test and intent strings")
    return key


def matched_transaction(call, event):
    if event["source"] != "chain":
        raise ValueError("expected a chain-sourced settlement")
    tx = checked_string(event["tx_hash"], HASH, "transaction hash")
    for field, match_field, pattern in [
        ("signed_nonce", "nonce", HASH), ("pay_to", "to", ADDRESS),
        ("amount_usdc", "value_usdc", USDC),
    ]:
        expected = checked_string(call[field], pattern, field)
        observed = checked_string(event[match_field], pattern, match_field)
        if pattern is USDC:
            expected, observed = Decimal(expected), Decimal(observed)
            if expected <= 0:
                raise ValueError("expected a positive USDC amount")
        if expected != observed:
            raise ValueError(f"settlement disagrees with {field}")
    return tx, call["signed_nonce"].lower()


def replay(document):
    if (document["network"] != "eip155:8453"
            or document["verification"]["usdc_contract"].lower() != BASE_USDC):
        raise ValueError("expected Base mainnet USDC")
    calls = document["calls"]
    if not isinstance(calls, list) or not calls:
        raise ValueError("expected a nonempty calls array")

    groups = defaultdict(set)
    tx_owner, nonce_tx = {}, {}
    for number, call in enumerate(calls, 1):
        key = group_key(call)
        group = groups[key]
        if not isinstance(call["settlements"], list):
            raise ValueError(f"call {number}: expected a settlements array")
        for event in call["settlements"]:
            tx, nonce = matched_transaction(call, event)
            if tx_owner.setdefault(tx, (key, nonce)) != (key, nonce):
                raise ValueError(f"call {number}: conflicting attribution for transaction")
            if nonce_tx.setdefault(nonce, tx) != tx:
                raise ValueError(f"call {number}: nonce associated with two transactions")
            group.add(tx)

    summary = {}
    rows = []
    for (test, intent), hashes in sorted(groups.items()):
        counts = summary.setdefault(test, dict(intents=0, multiple=0, single=0, unobserved=0))
        counts["intents"] += 1
        status = "multiple" if len(hashes) > 1 else "single" if hashes else "unobserved"
        counts[status] += 1
        rows.append(dict(test=test, intent=intent, status=status, transactions=sorted(hashes)))
    return dict(calls=len(calls), distinct_transactions=len(tx_owner), by_test=summary, intents=rows)


def verify_labels(document, result):
    """Check independent counts against every published per-intent count."""
    labels = {}
    if not isinstance(document["intents"], list):
        raise ValueError("expected an intents array")
    for row in document["intents"]:
        key = group_key(row)
        if key in labels or type(row["settlements"]) is not int or row["settlements"] < 0:
            raise ValueError("invalid or repeated reference count")
        if row["unattributed_settlements"] != []:
            raise ValueError("reference contains unattributed evidence requiring manual review")
        labels[key] = row["settlements"]
    observed = {group_key(row): len(row["transactions"]) for row in result["intents"]}
    if observed != labels:
        raise ValueError("published per-intent counts differ from the attributed call evidence")
    return len(observed)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("export", type=Path)
    parser.add_argument("--details", action="store_true", help="include intent IDs and transaction hashes")
    parser.add_argument("--check", action="store_true", help="compare every intent with the export's reference counts")
    parser.add_argument("--benchmark", action="store_true", help="time seven warm local runs of the selected checks")
    args = parser.parse_args()
    try:
        def run():
            document = json.loads(args.export.read_bytes())
            result = replay(document)
            if args.check:
                result["matched_reference_counts"] = verify_labels(document, result)
            if not args.details:
                del result["intents"]
            return result

        result = run()
        if args.benchmark:
            samples = []
            for _ in range(7):
                start = time.perf_counter_ns()
                if run() != result:
                    raise ValueError("input or result changed during benchmark")
                samples.append((time.perf_counter_ns() - start) / 1e6)
            result["benchmark_ms"] = {"runs": samples, "median": statistics.median(samples)}
        print(json.dumps(result, indent=2))
    except (OSError, ValueError, KeyError, AttributeError, TypeError) as exc:
        parser.exit(2, f"replay error: {exc}\n")


if __name__ == "__main__":
    main()
