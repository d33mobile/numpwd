#!/usr/bin/env bash
# Check the Java port against the openssl binary of this machine:
# 1. the committed vectors must regenerate byte for byte from the same seed,
# 2. a fresh set from a new seed must pass the unit tests.
# Usage: scripts/ci-vector-check.sh SEED
set -euo pipefail

SEED=${1:?usage: $0 SEED}
OUT_DIR=build/vector-check
mkdir -p "$OUT_DIR"

openssl version
python3 tools/gen_vectors.py --output "$OUT_DIR/committed-seed.tsv"
diff -u mobile/src/test/resources/openssl_vectors.tsv "$OUT_DIR/committed-seed.tsv"

python3 tools/gen_vectors.py --seed "$SEED" --output "$OUT_DIR/fresh.tsv"
NUMPWD_VECTORS="$PWD/$OUT_DIR/fresh.tsv" ./gradlew --no-daemon :mobile:testDebugUnitTest \
    --tests net.d33.numpwd.OpensslVectorsTest
