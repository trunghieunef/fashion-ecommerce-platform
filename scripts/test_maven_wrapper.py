import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile


@unittest.skipUnless(os.name == "nt" and shutil.which("javac"), "requires Windows and a JDK")
class WindowsMavenWrapperChecks(unittest.TestCase):
    def test_launches_java_with_project_path_and_arguments(self):
        with tempfile.TemporaryDirectory(prefix="wrapper with spaces ") as directory:
            root = Path(directory).resolve()
            shutil.copyfile(Path(__file__).resolve().parents[1] / "mvnw.cmd", root / "mvnw.cmd")
            source = root / "MavenWrapperMain.java"
            source.write_text(
                'package org.apache.maven.wrapper;\n'
                'public class MavenWrapperMain {\n'
                '  public static void main(String[] args) {\n'
                '    System.out.println(System.getProperty("maven.multiModuleProjectDirectory"));\n'
                '    System.out.println(String.join("|", args));\n'
                '  }\n'
                '}\n', encoding="utf-8",
            )
            subprocess.run(["javac", "-d", str(root), str(source)], check=True, capture_output=True)
            wrapper = root / ".mvn" / "wrapper" / "maven-wrapper.jar"
            wrapper.parent.mkdir(parents=True)
            class_path = Path("org/apache/maven/wrapper/MavenWrapperMain.class")
            with zipfile.ZipFile(wrapper, "w") as archive:
                archive.write(root / class_path, class_path.as_posix())
            env = os.environ.copy()
            env.pop("JAVA_OPTS", None)
            env.pop("MAVEN_OPTS", None)
            result = subprocess.run(
                [str(root / "mvnw.cmd"), "-version", "argument with spaces"],
                cwd=root, env=env, capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            lines = result.stdout.strip().splitlines()
            self.assertEqual(Path(lines[0]).resolve(), root)
            self.assertEqual(lines[1], "-version|argument with spaces")


if __name__ == "__main__":
    unittest.main()
