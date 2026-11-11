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
    CLARIFIED_SPECIFICATIONS_HEADER,
    INTERVIEW_ANALYSIS_PROMPT,
    MAX_CLARIFY_QUESTIONS,
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
        self.assertIn("must exactly match one of options_fa", INTERVIEW_ANALYSIS_PROMPT)
        self.assertIn("accept the recommendation and still get a good result",
                      INTERVIEW_ANALYSIS_PROMPT)

    def test_layman_language_rules_are_in_the_prompt(self):
        self.assertIn("Never quiz technical knowledge", INTERVIEW_ANALYSIS_PROMPT)
        self.assertIn("never ask about frameworks, stacks", INTERVIEW_ANALYSIS_PROMPT)
        self.assertIn("explain it in\n   parentheses in plain words", INTERVIEW_ANALYSIS_PROMPT)
