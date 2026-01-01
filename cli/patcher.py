#!/usr/bin/env python3
"""BitManager CLI - Thin wrapper around Java core"""

import sys, os, subprocess
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
CORE_JAR = SCRIPT_DIR.parent / "core" / "build" / "libs" / "core.jar"

def main():
    if not CORE_JAR.exists():
        print(f"[✗] Core JAR not found: {CORE_JAR}")
        print("[*] Run: ./gradlew :core:jar")
        sys.exit(1)
    
    # Find Java 17+
    java_cmd = None
    for java_path in [
        "/usr/lib/jvm/java-17-openjdk-amd64/bin/java",
        "/usr/lib/jvm/java-21-openjdk-amd64/bin/java",
        "java",
    ]:
        try:
            result = subprocess.run([java_path, "-version"], capture_output=True, text=True)
            if result.returncode == 0:
                java_cmd = java_path
                break
        except:
            continue
    
    if not java_cmd:
        print("[✗] Java 17+ not found")
        sys.exit(1)
    
    # Pass all args to Java core
    cmd = [java_cmd, "-jar", str(CORE_JAR)] + sys.argv[1:]
    sys.exit(subprocess.call(cmd))

if __name__ == "__main__":
    main()
