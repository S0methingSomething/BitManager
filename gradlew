#!/bin/bash
# Wrapper for gradlew
cd "$(dirname "$0")"
exec ./gradlew "$@"
