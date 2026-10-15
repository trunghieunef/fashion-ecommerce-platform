#!/usr/bin/env bash
set -euo pipefail
for topic in catalog.events catalog.events.DLT inventory.events; do
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --create --if-not-exists --topic "$topic" --partitions 3 --replication-factor 1
done
