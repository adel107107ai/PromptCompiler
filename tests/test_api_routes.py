"""Tests for API routes including interactive prompt engineering endpoints."""

import unittest
from unittest.mock import AsyncMock, patch
from starlette.testclient import TestClient

from prompt_compiler.app import app


class ApiRouteTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(app)

    def test_pages_route_returns_html(self):
        response = self.client.get("/")
        self.assertEqual(response.status_code, 200)
        self.assertIn("Prompt Engine", response.text)

    def test_models_route_returns_list(self):
        response = self.client.get("/models")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIsInstance(data, list)
        self.assertTrue(any(m["id"] == "gemini-3.5-flash" for m in data))

    def test_analyze_empty_prompt_returns_400(self):
        response = self.client.post("/analyze", json={"prompt": ""})
        self.assertEqual(response.status_code, 400)
        self.assertIn("Prompt is empty", response.json().get("error", ""))

    def test_synthesize_empty_prompt_returns_400(self):
        response = self.client.post("/synthesize", json={"original_prompt": ""})
        self.assertEqual(response.status_code, 400)
        self.assertIn("Original prompt is empty", response.json().get("error", ""))

    @patch("prompt_compiler.api.routes.translation.call_gemini", new_callable=AsyncMock)
    def test_analyze_endpoint_success(self, mock_gemini):
        mock_gemini.return_value = (
            '{"detected_category": "coding", "detected_purpose": "ساخت ربات", '
            '"analysis_summary": "بررسی ابهامات ربات", "ambiguities": ["زبان نامشخص"], '
            '"questions": [{"id": "goals", "category": "اهداف", "question": "کدام زبان؟", '
            '"explanation": "تعیین زبان", "suggested_options": ["پایتون", "نود"], "placeholder": "..."}]}',
            {"totalTokenCount": 120},
        )
        response = self.client.post("/analyze", json={"prompt": "یه ربات تلگرام بساز"})
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("analysis", data)
        self.assertEqual(data["analysis"]["detected_category"], "coding")
        self.assertEqual(len(data["analysis"]["questions"]), 1)
        self.assertEqual(data["analysis"]["questions"][0]["category"], "اهداف")

    @patch("prompt_compiler.api.routes.translation.call_gemini", new_callable=AsyncMock)
    def test_synthesize_endpoint_success(self, mock_gemini):
        mock_gemini.return_value = (
            '{"detected_category": "coding", "english_prompt": "Build a Telegram bot with Python.", '
            '"persian_prompt": "یک ربات تلگرام با پایتون بساز.", '
            '"preserved_requirements": ["Telegram bot", "Python"], "assumptions": [], '
            '"missing_information": [], "suggestions": []}',
            {"totalTokenCount": 200},
        )
        response = self.client.post(
            "/synthesize",
            json={
                "original_prompt": "یه ربات تلگرام بساز",
                "answers": [
                    {"id": "goals", "category": "اهداف", "question": "کدام زبان؟", "answer": "پایتون"}
                ],
            },
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("parsed", data)
        self.assertEqual(data["parsed"]["english_prompt"], "Build a Telegram bot with Python.")
        self.assertEqual(data["parsed"]["persian_prompt"], "یک ربات تلگرام با پایتون بساز.")
        self.assertIn("Python", data["parsed"]["preserved_requirements"])


if __name__ == "__main__":
    unittest.main()
