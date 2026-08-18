#!/usr/bin/env python3
"""Verify standalone Agent Journal consumers resolve the accepted Jackson floors."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
import textwrap
import xml.etree.ElementTree as ET
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parent.parent
MAVEN = REPOSITORY_ROOT / "mvnw"
MODULES = (
    "journal-core",
    "claude-code-capture",
    "gemini-cli-capture",
)
EXPECTED_LINES = {
    "journal-core": {"com.fasterxml.jackson"},
    "claude-code-capture": {"com.fasterxml.jackson", "tools.jackson"},
    "gemini-cli-capture": {"com.fasterxml.jackson"},
}
DEPENDENCY_PATTERN = re.compile(
    r"^\s+([^:\s]+):([^:\s]+):([^:\s]+):([^:\s]+):([^\s]+)(?:\s|$)"
)


def project_version() -> str:
    root = ET.parse(REPOSITORY_ROOT / "pom.xml").getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    version = root.findtext("m:version", namespaces=namespace)
    if not version:
        raise RuntimeError("root pom.xml has no project version")
    return version


def version_tuple(version: str) -> tuple[int, ...]:
    if not re.fullmatch(r"\d+(?:\.\d+)*", version):
        raise ValueError(f"non-numeric Jackson version {version!r}")
    return tuple(int(part) for part in version.split("."))


def accepted_floor(group_id: str, artifact_id: str) -> str:
    if group_id.startswith("tools.jackson"):
        return "3.1.6"
    if group_id == "com.fasterxml.jackson.core" and artifact_id == "jackson-annotations":
        # Jackson 2.21.x intentionally uses the independently versioned 2.21 annotations.
        return "2.21"
    return "2.21.6"


def maven_command(repo: Path | None, *arguments: str) -> list[str]:
    command = [str(MAVEN), "-B", "-q", *arguments]
    if repo is not None:
        command.append(f"-Dmaven.repo.local={repo}")
    return command


def run(command: list[str], *, cwd: Path) -> None:
    completed = subprocess.run(command, cwd=cwd, text=True, capture_output=True)
    if completed.returncode:
        sys.stderr.write(completed.stdout)
        sys.stderr.write(completed.stderr)
        raise subprocess.CalledProcessError(completed.returncode, command)


def consumer_pom(module: str, version: str) -> str:
    return textwrap.dedent(
        f"""\
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>consumer.resolution.gate</groupId>
          <artifactId>{module}-consumer</artifactId>
          <version>1.0.0</version>
          <dependencies>
            <dependency>
              <groupId>io.github.markpollack</groupId>
              <artifactId>{module}</artifactId>
              <version>{version}</version>
            </dependency>
          </dependencies>
        </project>
        """
    )


def resolved_jackson(dependency_list: Path) -> list[tuple[str, str, str]]:
    resolved = []
    for line in dependency_list.read_text(encoding="utf-8").splitlines():
        match = DEPENDENCY_PATTERN.match(line)
        if not match:
            continue
        group_id, artifact_id, _packaging, version, _scope = match.groups()
        if group_id.startswith("com.fasterxml.jackson") or group_id.startswith("tools.jackson"):
            resolved.append((group_id, artifact_id, version))
    return sorted(set(resolved))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--maven-repo",
        type=Path,
        help="Maven local repository to use (default: Maven's normal local repository)",
    )
    parser.add_argument(
        "--skip-install",
        action="store_true",
        help="Use already-installed artifacts instead of installing the current checkout",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    maven_repo = args.maven_repo.resolve() if args.maven_repo else None
    version = project_version()

    if not args.skip_install:
        run(
            maven_command(maven_repo, "-DskipTests", "install"),
            cwd=REPOSITORY_ROOT,
        )

    failures: list[str] = []
    with tempfile.TemporaryDirectory(prefix="agent-journal-consumers-") as directory:
        consumer_root = Path(directory)
        for module in MODULES:
            module_directory = consumer_root / module
            module_directory.mkdir()
            (module_directory / "pom.xml").write_text(
                consumer_pom(module, version), encoding="utf-8"
            )
            dependency_list = module_directory / "dependencies.txt"
            run(
                maven_command(
                    maven_repo,
                    "-f",
                    str(module_directory / "pom.xml"),
                    "org.apache.maven.plugins:maven-dependency-plugin:3.7.0:list",
                    "-DincludeScope=runtime",
                    f"-DoutputFile={dependency_list}",
                    "-DappendOutput=false",
                ),
                cwd=REPOSITORY_ROOT,
            )

            dependencies = resolved_jackson(dependency_list)
            present_lines = {
                "tools.jackson" if group.startswith("tools.jackson") else "com.fasterxml.jackson"
                for group, _artifact, _version in dependencies
            }
            missing = EXPECTED_LINES[module] - present_lines
            for line in sorted(missing):
                failures.append(f"{module}: expected {line} dependencies but resolved none")

            print(module)
            for group_id, artifact_id, resolved_version in dependencies:
                floor = accepted_floor(group_id, artifact_id)
                try:
                    safe = version_tuple(resolved_version) >= version_tuple(floor)
                except ValueError as error:
                    safe = False
                    failures.append(f"{module}: {group_id}:{artifact_id}: {error}")
                status = "PASS" if safe else "FAIL"
                print(f"  {group_id}:{artifact_id} {resolved_version} (floor {floor}) {status}")
                if not safe:
                    failures.append(
                        f"{module}: {group_id}:{artifact_id} resolved {resolved_version}, below {floor}"
                    )

    if failures:
        print("\nConsumer-resolution gate failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1

    print(f"\nAll {len(MODULES)} standalone consumers satisfy the accepted Jackson floors.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
