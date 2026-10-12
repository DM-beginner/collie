"""Exercise the real PowerShell launcher through a harmless native command stub."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class LauncherTests(unittest.TestCase):
    def run_launcher(self, arguments, inside=True):
        with tempfile.TemporaryDirectory(prefix="collie launcher ") as directory:
            root = Path(directory)
            (root / "capture.ps1").write_text(
                "ConvertTo-Json -InputObject @($args) -Compress | "
                "Set-Content -LiteralPath $env:COLLIE_TEST_OUTPUT; exit 7",
                encoding="utf-8-sig",
            )
            (root / "codex.cmd").write_text(
                '@echo off\r\n"%SystemRoot%\\System32\\WindowsPowerShell\\v1.0\\'
                'powershell.exe" -NoProfile -File "%~dp0capture.ps1" %*\r\n'
                'exit /b %errorlevel%\r\n', encoding="ascii"
            )
            (root / "arguments.json").write_text(json.dumps(arguments))
            helper = Path(__file__).with_name("codex-herdr.ps1")
            quote = lambda value: "'" + str(value).replace("'", "''") + "'"
            command = (
                f". {quote(helper)}; "
                f"$items = Get-Content -LiteralPath {quote(root / 'arguments.json')} "
                "-Raw | ConvertFrom-Json; codex @items; exit $LASTEXITCODE"
            )
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"],
                       HERDR_ENV="1" if inside else "0",
                       COLLIE_TEST_OUTPUT=str(root / "output.json"))
            result = subprocess.run(["powershell", "-NoProfile", "-Command", command],
                                    env=env, capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 7, result.stderr)
            return json.loads((root / "output.json").read_text(encoding="utf-8-sig"))

    def test_normal_start(self):
        self.assertEqual(self.run_launcher([]), ["--no-daemon"])

    def test_resume_and_fork(self):
        for verb in ("resume", "fork"):
            with self.subTest(verb=verb):
                self.assertEqual(self.run_launcher([verb, "thread-id"]),
                                 ["--no-daemon", verb, "thread-id"])

    def test_outside_herdr(self):
        self.assertEqual(self.run_launcher(["resume", "thread-id"], False),
                         ["resume", "thread-id"])

    def test_admin_and_exec(self):
        for arguments in (["exec", "test prompt"], ["app-server", "daemon", "version"],
                          ["--config", "model=test", "doctor"]):
            with self.subTest(arguments=arguments):
                self.assertEqual(self.run_launcher(arguments), arguments)

    def test_quotes_spaces_and_config_values(self):
        arguments = ["--model", "exec", "--cd", "D:\\project with spaces", "resume", "id"]
        self.assertEqual(self.run_launcher(arguments), ["--no-daemon"] + arguments)

    def test_flag_not_duplicated(self):
        arguments = ["--no-daemon", "resume", "thread-id"]
        self.assertEqual(self.run_launcher(arguments), arguments)


if __name__ == "__main__":
    unittest.main()
