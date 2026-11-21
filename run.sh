#!/usr/bin/env bash
# Runs the 1.1 and 2.1 booking demos against a local Ollama.
#   ./run.sh            # both
#   ./run.sh 1.1        # one of them
#   AUTO_APPROVE=true ./run.sh 2.1   # approve the cancel without asking
set -euo pipefail
cd "$(dirname "$0")"
OLLAMA_URL=${OLLAMA_URL:-http://localhost:11434}
CHAT_MODEL=${CHAT_MODEL:-qwen3:8b}
DECISION_MODEL=${DECISION_MODEL:-d1-omni:600m}
export OLLAMA_URL CHAT_MODEL DECISION_MODEL

curl -sf "$OLLAMA_URL/api/tags" > /dev/null || { echo "Ollama is not answering at $OLLAMA_URL"; exit 1; }
need=("$CHAT_MODEL"); [[ "${1:-2.1}" != 1.1 ]] && need+=("$DECISION_MODEL")
for m in "${need[@]}"; do
  if ! ollama list | awk 'NR>1{print $1}' | grep -qx "$m"; then
    read -rp "Model $m is not pulled. Pull it now? [y/N] " a
    [[ $a == [yY]* ]] && ollama pull "$m" || { echo "Skipping: $m missing"; exit 1; }
  fi
done

[[ -f booking-1.1/target/booking-1.1-1.0.0.jar && -f booking-2.1/target/booking-2.1-1.0.0.jar ]] || mvn -q -B package -DskipTests

for v in ${1:-1.1 2.1}; do
  echo; echo "################################  Spring AI $v  ################################"
  java --enable-native-access=ALL-UNNAMED -jar "booking-$v/target/booking-$v-1.0.0.jar" \
    --spring.ai.retry.max-attempts=2 --demo.auto-approve="${AUTO_APPROVE:-false}" 2>&1 | tee "run-$v.log"
done
