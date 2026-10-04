"""TASK:SEC-01: scripts/scan-secrets.sh must fail on a committed secret and pass on clean history."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "scan-secrets.sh"


def docker_available():
    if shutil.which("docker") is None or shutil.which("bash") is None:
        return False
    return subprocess.run(["docker", "info"], capture_output=True).returncode == 0


def git(repo, *args):
    subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True)


@unittest.skipUnless(docker_available(), "scan-secrets.sh runs gitleaks in Docker")
class ScanSecretsTest(unittest.TestCase):
    def scan(self, content):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            git(repo, "init", "-q")
            git(repo, "config", "user.email", "test@example.invalid")
            git(repo, "config", "user.name", "test")
            (repo / "config.txt").write_text(content, encoding="utf-8")
            git(repo, "add", ".")
            git(repo, "commit", "-q", "-m", "fixture")
            # Absolute path: on Windows CreateProcess would pick WSL bash from System32 before PATH.
            bash = shutil.which("bash")
            return subprocess.run([bash, SCRIPT.as_posix(), repo.as_posix()], capture_output=True, text=True)

    def test_committed_github_token_fails_the_scan(self):
        # Built at runtime so this file itself never contains a matching token.
        token = "ghp" + "_" + "Zq7Rk2Lm9Xv4Bn8Tc3Wd6Yh1Js5Pf0Ga2Ke7U"
        result = self.scan("GITHUB_TOKEN=" + token + "\n")
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn(token, result.stdout + result.stderr, "findings must be redacted")

    def test_clean_history_passes(self):
        result = self.scan("GREETING=hello\n")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
