"""Behavioral conformance tests for the interview-driven compiler (1.4.0.md).

The directive's sample tests assert on their own string literals; these tests
verify the actual rules instead: the gate, the caps, the precedence of
prohibitions over interview answers, and the never-block degradation.
"""

import os
import sys
import json
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from prompt_compiler.prompt_engine import (
    ANALYSIS_LIGHT_MODEL,
    ANALYSIS_MAX_OUTPUT_TOKENS,
    CLARIFIED_SPECIFICATIONS_HEADER,
    INTERVIEW_ANALYSIS_PROMPT,
    MAX_CLARIFY_QUESTIONS,
    SYSTEM_PROMPT,
    build_answers_block,
    build_user_prompt,
    parse_clarify_output,
)
from prompt_compiler.catalog import KNOWN_CATEGORIES, MODELS


class TestInterviewCompiler(unittest.TestCase):

    def test_clear_prompt_fast_path(self):
        """A clear request must produce no interview: gate closed, zero questions."""
        raw = json.dumps({
            "has_critical_ambiguity": False,
            "detected_category": "coding",
            "reasoning_fa": "زبان، نسخه و وظیفه مشخص است.",
            "questions": [],
        })
        parsed = parse_clarify_output(raw)
        self.assertFalse(parsed["has_critical_ambiguity"])
        self.assertEqual([], parsed["questions"])
        self.assertNotIn(CLARIFIED_SPECIFICATIONS_HEADER, build_user_prompt("یک اسکریپت بنویس"))

    def test_ambiguous_prompt_caps_at_four_questions(self):
        questions = [{"id": "q%d" % i, "question_fa": "س%d" % i,
                      "why_needed_fa": "چ%d" % i, "options_fa": ["الف", "ب"]}
                     for i in range(9)]
        parsed = parse_clarify_output(json.dumps({
            "has_critical_ambiguity": True, "detected_category": "general",
            "reasoning_fa": "ابهام جدی", "questions": questions}))
        self.assertTrue(parsed["has_critical_ambiguity"])
        self.assertLessEqual(len(parsed["questions"]), MAX_CLARIFY_QUESTIONS)
        for question in parsed["questions"]:
            self.assertIn("question_fa", question)
            self.assertIn("why_needed_fa", question)
            self.assertLessEqual(len(question["options_fa"]), 4)

    def test_negative_constraint_precedence(self):
        """An interview answer must not override a prohibition from the request itself."""
        base_prompt = "بدون استفاده از فریم‌ورک‌های سنگین مثل React یک فرم بساز."
        interview_answer = "می‌خواهم از Next.js استفاده کنم."
        compiled = build_user_prompt(base_prompt, [("چارچوب؟", interview_answer)])
        self.assertIn(CLARIFIED_SPECIFICATIONS_HEADER, compiled)
        self.assertIn("prohibitions and negative constraints stated in the original request "
                      "take precedence", compiled)
        self.assertIn("never silently override it", compiled)
        self.assertIn("بدون استفاده از فریم‌ورک‌های سنگین مثل React", compiled)
        self.assertIn("- چارچوب؟: می‌خواهم از Next.js استفاده کنم.", compiled)

    def test_unreadable_analysis_degrades_to_fast_path(self):
        parsed = parse_clarify_output("an HTML error page")
        self.assertFalse(parsed["has_critical_ambiguity"])
        self.assertTrue(parsed["degraded"])
        self.assertEqual([], parsed["questions"])

    def test_analysis_prompt_is_category_aware(self):
        for category in sorted(KNOWN_CATEGORIES):
            self.assertIn(category, INTERVIEW_ANALYSIS_PROMPT)
        self.assertNotIn("{categories}", INTERVIEW_ANALYSIS_PROMPT)

    def test_analysis_light_model_is_a_real_generate_model(self):
        self.assertTrue(ANALYSIS_LIGHT_MODEL.endswith("flash-lite"))
        entry = next((m for m in MODELS if m["id"] == ANALYSIS_LIGHT_MODEL), None)
        self.assertIsNotNone(entry)
        self.assertTrue(entry["generate"])


if __name__ == "__main__":
    unittest.main()


class RecommendationTests(unittest.TestCase):
    """The recommendation carries the novice: parse, fallback and prompt rules."""

    def test_recommended_is_parsed(self):
        parsed = parse_clarify_output(json.dumps({
            "has_critical_ambiguity": True,
            "questions": [{"question_fa": "مخاطب؟", "options_fa": ["الف", "ب"],
                           "recommended_fa": "ب"}],
        }))
        self.assertEqual("ب", parsed["questions"][0]["recommended_fa"])

    def test_recommended_falls_back_to_first_option(self):
        parsed = parse_clarify_output(json.dumps({
            "has_critical_ambiguity": True,
            "questions": [{"question_fa": "قالب؟", "options_fa": ["اول", "دوم"]}],
        }))
        self.assertEqual("اول", parsed["questions"][0]["recommended_fa"])

    def test_recommended_rule_is_in_the_prompt(self):
        self.assertIn("must exactly match one of", INTERVIEW_ANALYSIS_PROMPT)
        self.assertIn("still get a good result", INTERVIEW_ANALYSIS_PROMPT)

    def test_layman_language_rules_are_in_the_prompt(self):
        self.assertIn("Never quiz technical knowledge", INTERVIEW_ANALYSIS_PROMPT)
        self.assertIn('never "database", "framework", "stack"', INTERVIEW_ANALYSIS_PROMPT)
        self.assertIn("parentheses in plain words", INTERVIEW_ANALYSIS_PROMPT)

    def test_master_prompt_contract_is_in_the_compile_prompt(self):
        """The compiled output must be a phase-banded master build prompt for build requests."""
        for fragment in ("Phase 0 - Context, Goal & Non-Goals", "Phase 6 - Verification",
                         "verifiable checkpoint", "Assumptions block",
                         "STOPS at the first failing checkpoint",
                         "WITHOUT the phase structure"):
            self.assertIn(fragment, SYSTEM_PROMPT)
        for phase in ("Phase 1", "Phase 2", "Phase 3", "Phase 4", "Phase 5", "Phase 7"):
            self.assertIn(phase, SYSTEM_PROMPT)

    def test_dont_know_default_rule_is_in_the_specs_header(self):
        self.assertIn("safest typical default", CLARIFIED_SPECIFICATIONS_HEADER)
        self.assertIn("Phase 0 Assumptions block", CLARIFIED_SPECIFICATIONS_HEADER)


class UnreadableAnalysisTests(unittest.TestCase):
    """A truncated or malformed analysis must not silently discard a real ambiguity."""

    TRUNCATED_WITH_QUESTION = (
        '{"has_critical_ambiguity": true, "questions": ['
        '{"id":"G1","question_fa":"هدف نهایی چیست؟","why_needed_fa":"چ","options_fa":["الف","ب"],'
        '"recommended_fa":"الف"}, {"id":"S1", "question_fa": "نصف'
    )

    def test_truncated_after_a_whole_question_is_recovered(self):
        parsed = parse_clarify_output(self.TRUNCATED_WITH_QUESTION)
        self.assertTrue(parsed["has_critical_ambiguity"])
        self.assertFalse(parsed["degraded"])
        self.assertEqual(1, len(parsed["questions"]))
        self.assertEqual("هدف نهایی چیست؟", parsed["questions"][0]["question_fa"])

    def test_truncated_before_any_whole_question_is_degraded(self):
        raw = '{"has_critical_ambiguity": true, "reasoning_fa": "درخواست مبهم است'
        parsed = parse_clarify_output(raw)
        self.assertTrue(parsed["degraded"])
        self.assertEqual([], parsed["questions"])

    def test_prose_around_the_json_is_tolerated(self):
        raw = ('Sure! Here is the JSON:\n```json\n'
               '{"has_critical_ambiguity": true, "questions": [{"id":"G1","question_fa":"س؟"}]}\n'
               '```\nHope that helps.')
        parsed = parse_clarify_output(raw)
        self.assertTrue(parsed["has_critical_ambiguity"])
        self.assertEqual(1, len(parsed["questions"]))

    def test_salvage_never_invents_a_gate(self):
        # Whole questions, but the response never states the gate: stay on the fast path.
        raw = '{"questions": [{"id":"G1","question_fa":"س؟"}]'
        parsed = parse_clarify_output(raw)
        self.assertFalse(parsed["has_critical_ambiguity"])
        self.assertTrue(parsed["degraded"])

    def test_salvage_is_capped(self):
        questions = ",".join('{"id":"q%d","question_fa":"س%d"}' % (i, i) for i in range(9))
        parsed = parse_clarify_output('{"has_critical_ambiguity": true, "questions": [%s]' % questions)
        self.assertLessEqual(len(parsed["questions"]), MAX_CLARIFY_QUESTIONS)

    def test_analysis_budget_is_large_enough_for_the_schema(self):
        # Measured against the live API: 1200 was malformed about one run in three.
        self.assertGreaterEqual(ANALYSIS_MAX_OUTPUT_TOKENS, 4096)
