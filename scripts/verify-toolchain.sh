#!/usr/bin/env bash
set -euo pipefail

./mvnw -version | grep -F 'Apache Maven 3.9.16'
test "$(node --version)" = "v24.21.0"
test "$(npm --version)" = "11.19.0"
./mvnw -q help:effective-pom -Doutput=target/effective-pom.xml
npm ci --ignore-scripts
npm ls --all
