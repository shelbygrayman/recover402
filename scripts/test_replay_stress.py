import copy
import unittest

from replay_stress import BASE_USDC, replay, verify_labels


def tx(number):
    return f"0x{number:064x}"


def call(number, intent="purchase"):
    recipient = "0x" + "1" * 40
    return dict(test="timeout", intent=intent, signed_nonce=tx(number + 10),
                pay_to=recipient, amount_usdc="0.01", settlements=[
                    dict(source="chain", tx_hash=tx(number), nonce=tx(number + 10),
                         to=recipient, value_usdc="0.010000")
                ])


def export(*calls):
    return dict(network="eip155:8453", verification=dict(usdc_contract=BASE_USDC),
                calls=list(calls))


class ReplayTests(unittest.TestCase):
    def test_distinct_attempts_and_repeated_observations(self):
        for second, status in [(call(1), "single"), (call(2), "multiple")]:
            with self.subTest(status=status):
                result = replay(export(call(1), second))
                self.assertEqual(result["intents"][0]["status"], status)

    def test_two_purchases(self):
        self.assertEqual(replay(export(call(1), call(2, "another")))["by_test"]["timeout"]["single"], 2)

    def test_missing_evidence(self):
        row = call(1)
        row["settlements"] = []
        self.assertEqual(replay(export(row))["intents"][0]["status"], "unobserved")

    def test_bad_event(self):
        for field, value in [("source", "estimate"), ("nonce", tx(8)), ("to", "0x" + "2"*40),
                             ("value_usdc", "0.011"), ("tx_hash", "short")]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                row = call(1)
                row["settlements"][0][field] = value
                replay(export(row))

    def test_conflicting_attribution(self):
        for second in [call(1, "another"), call(2)]:
            with self.subTest(second=second), self.assertRaises(ValueError):
                second["signed_nonce"] = second["settlements"][0]["nonce"] = call(1)["signed_nonce"]
                replay(export(call(1), second))

    def test_wrong_chain_or_token(self):
        original = export(call(1))
        for chain, token in [("eip155:1", BASE_USDC), ("eip155:8453", "0x"+"3"*40)]:
            with self.subTest(chain=chain), self.assertRaises(ValueError):
                document = copy.deepcopy(original)
                document.update(network=chain, verification={"usdc_contract": token})
                replay(document)

    def test_reference_check(self):
        document = export(call(1))
        row = dict(test="timeout", intent="purchase", settlements=1, unattributed_settlements=[])
        document["intents"] = [row]
        result = replay(document)
        self.assertEqual(verify_labels(document, result), 1)
        for labels in [[], [row, row], [dict(row, settlements=0)],
                       [dict(row, unattributed_settlements=[tx(9)])]]:
            with self.subTest(labels=labels), self.assertRaises(ValueError):
                verify_labels(dict(document, intents=labels), result)


if __name__ == "__main__":
    unittest.main()
