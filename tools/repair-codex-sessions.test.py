import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("repair", Path(__file__).with_name("repair-codex-sessions.py"))
repair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repair)


class SessionEvidenceTests(unittest.TestCase):
    def test_wrapped_ansi_markdown_prose_matches(self):
        message = "Here is a specific response containing the exact folder and the detailed troubleshooting steps. " * 2
        screen = "\x1b[32m● " + message[:45] + "\n│ " + message[45:] + "\x1b[0m"
        self.assertEqual(repair.matching_sessions(screen, [("one", [repair.normalize("**" + message + "**")])]), ["one"])

    def test_copied_fork_turns_are_ambiguous(self):
        message = "A long and distinctive reply about this project's chosen implementation and its verified behavior. " * 2
        sessions = [(name, [repair.normalize(message)]) for name in ("parent", "fork")]
        self.assertEqual(repair.matching_sessions(message, sessions), ["fork", "parent"])

    def test_welcome_and_short_generic_status_do_not_identify_a_session(self):
        self.assertEqual(repair.matching_sessions("Working… Ready. Codex", [("one", ["Working…Ready.Codex"])]), [])

    def test_latest_visible_reply_identifies_a_fork_without_using_copied_older_turns(self):
        old = "The previous conversation contains a detailed description of the original design and all its prerequisites. " * 2
        new = "The new fork's latest answer documents a different choice and a verified result specific to this current session. " * 2
        sessions = [("parent", [repair.normalize(old)]), ("fork", [repair.normalize(old), repair.normalize(new)])]
        self.assertEqual(repair.matching_sessions(old + "\n" + new, sessions), ["fork"])

    def test_ambiguous_latest_reply_does_not_fall_back_to_an_older_unique_turn(self):
        old = "Only one session contains this earlier unique detailed explanation about the implementation's structure. " * 2
        new = "This newest visible reply is present in two copied sessions and cannot safely tell them apart anymore. " * 2
        sessions = [("one", [repair.normalize(old), repair.normalize(new)]), ("two", [repair.normalize(new)])]
        self.assertEqual(repair.matching_sessions(old + "\n" + new, sessions), ["one", "two"])

    def test_tool_results_and_developer_messages_cannot_identify_a_session(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "rollout-01234567-1234-1234-1234-0123456789ab.jsonl"
            path.write_text('\n'.join([
                '{"type":"session_meta","payload":{"id":"01234567-1234-1234-1234-0123456789ab"}}',
                '{"type":"response_item","payload":{"type":"function_call_output","output":"not prose"}}',
                '{"type":"response_item","payload":{"type":"message","role":"developer","content":[{"text":"shared instructions"}]}}',
                '{"type":"response_item","payload":{"type":"message","role":"assistant","content":[{"text":"actual reply"}]}}',
            ]), encoding="utf-8")
            self.assertEqual(repair.load_messages(path, root)[1], ["actualreply"])
            self.assertIsNone(repair.load_messages(path, root / "other"))

    def test_filename_must_address_the_same_session_as_its_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "rollout-00000000-1234-1234-1234-0123456789ab.jsonl"
            path.write_text('{"type":"session_meta","payload":{"id":"01234567-1234-1234-1234-0123456789ab"}}', encoding="utf-8")
            self.assertIsNone(repair.load_messages(path, root))

    def test_agent_command_input_is_evidence_but_its_output_is_not(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "rollout-01234567-1234-1234-1234-0123456789ab.jsonl"
            path.write_text('\n'.join([
                '{"type":"session_meta","payload":{"id":"01234567-1234-1234-1234-0123456789ab"}}',
                '{"type":"response_item","payload":{"type":"function_call","arguments":"{\\"cmd\\":\\"unique command\\"}"}}',
                '{"type":"response_item","payload":{"type":"function_call_output","output":"shared untrusted result"}}',
            ]), encoding="utf-8")
            self.assertEqual(repair.load_messages(path, root)[1], ["uniquecommand"])


if __name__ == "__main__":
    unittest.main()
