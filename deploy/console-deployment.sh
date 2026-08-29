#!/usr/bin/env bash

# Console is built and deployed only after its source tree is present.
CONSOLE_ENABLED=false
CONSOLE_INGRESS_MANIFEST="$ROOT/deploy/kind-ingress-api-only.yaml"
if [[ -d "$ROOT/console" ]]; then
  CONSOLE_ENABLED=true
  CONSOLE_INGRESS_MANIFEST="$ROOT/deploy/kind-ingress.yaml"
fi
