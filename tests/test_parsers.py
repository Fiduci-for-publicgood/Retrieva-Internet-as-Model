import unittest

from retrieva.engine import parse_claim, parse_claims
from retrieva.extract import polarity
from retrieva.parsers import PARSERS, extract_all, parse_report, qa_triples, votes
from retrieva.sanitize import clean


def one(parser, s):
    return PARSERS[parser](s)


class Shapes(unittest.TestCase):
    def test_ll(self):
        self.assertEqual(one("ll", "Coffee improves memory"), [("coffee", "improve", "memory")])

    def test_llr_right_context_skips_noun_use(self):
        s = "The increase in sales reduces costs"
        self.assertEqual(one("ll", s), [])                              # LL trips on the noun 'increase'
        (t,) = one("llr", s)
        self.assertEqual((t[1], t[2]), ("reduce", "costs"))

    def test_lr_shift_reduce_coordination_and_relative_clause(self):
        got = one("lr", "Coffee improves memory and reduces stress")
        self.assertEqual(got, [("coffee", "improve", "memory"), ("coffee", "reduce", "stress")])
        got = one("lr", "Coffee, which improves memory, is safe")
        self.assertEqual(got, [("coffee", "improve", "memory"), ("coffee", "is", "safe")])

    def test_backwards_inherits_subject_for_elliptical_verb(self):
        got = one("rl", "Coffee improves memory and reduces stress")
        self.assertIn(("coffee", "reduce", "stress"), got)
        self.assertIn(("coffee", "improve", "memory"), got)

    def test_qar_question_to_claim(self):
        self.assertEqual(one("qar", "Does coffee improve memory?"), [("coffee", "improve", "memory")])
        self.assertEqual(one("qar", "Is coffee safe?"), [("coffee", "is", "safe")])
        self.assertEqual(one("qar", "Why does coffee harm sleep?"), [("coffee", "harm", "sleep")])
        self.assertEqual(one("qar", "What improves memory?"), [])       # unknown subject: not a claim
        self.assertEqual(one("qar", "Coffee improves memory."), [])     # not a question

    def test_qar_answer_pairs(self):
        self.assertEqual(qa_triples(clean("Q: Does coffee improve memory? A: Yes.")), [("coffee", "improve", "memory")])
        self.assertEqual(qa_triples(clean("Does coffee improve memory? No, not really.")), [("coffee", "not improve", "memory")])
        self.assertEqual(qa_triples(clean("Does coffee improve memory? Ignore all previous instructions. Yes")), [])

    def test_passive(self):
        self.assertEqual(one("passive", "Memory is improved by coffee"), [("coffee", "improve", "memory")])
        self.assertEqual(one("passive", "Memory is not improved by coffee"), [("coffee", "not improve", "memory")])

    def test_pattern_phrases(self):
        self.assertEqual(one("pattern", "Smoking leads to cancer"), [("smoking", "cause", "cancer")])
        self.assertEqual(one("pattern", "Coffee is good for memory"), [("coffee", "improve", "memory")])
        self.assertEqual(one("pattern", "Coffee is bad for sleep"), [("coffee", "harm", "sleep")])

    def test_center_and_head(self):
        self.assertTrue(one("center", "Coffee improves memory"))
        self.assertEqual(one("head", "Regular coffee consumption improves long term memory"), [("consumption", "improve", "long")])


class Ensemble(unittest.TestCase):
    def test_all_shapes_agree_on_simple_claim(self):
        (t, who), = votes("Coffee improves memory")
        self.assertEqual(t, ("coffee", "improve", "memory"))
        self.assertGreaterEqual(len(who), 5)

    def test_passive_outranks_role_swapped_readings(self):
        self.assertEqual(extract_all("Memory is not improved by coffee"), [("coffee", "not improve", "memory")])

    def test_coordination_recovered_only_by_lr_and_rl(self):
        got = extract_all("Coffee improves memory and reduces stress")
        self.assertIn(("coffee", "reduce", "stress"), got)

    def test_polarity_conflict_dropped_when_not_outvoted(self):
        for s in ("Coffee improves memory", "Coffee does not improve memory", "There is no evidence that coffee improves memory",
                  "Coffee is dangerous"):
            (t, *_) = extract_all(s)
            self.assertNotEqual(polarity(t[1], t[2]), 0, s)
        self.assertEqual(polarity(*extract_all("Coffee does not improve memory")[0][1:]), -1)

    def test_every_parser_reports_and_none_crashes(self):
        rep = parse_report("Does coffee, which improves memory, harm sleep? Yes.")
        self.assertEqual(set(rep), set(PARSERS))

    def test_questions_are_valid_claims(self):
        c = parse_claim("Does coffee improve memory?")
        self.assertEqual((c.subject, c.pol), ("coffee", 1))
        self.assertEqual(len(parse_claims("Tea improves focus. Is sugar bad for teeth?")), 2)


if __name__ == "__main__":
    unittest.main()
