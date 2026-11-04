#!/usr/bin/env bash
# Entry point for the official runner image (ghcr.io/actions/actions-runner).
#
# The runner's own credentials (.runner, .credentials, .credentials_rsaparams) live in the
# /home/runner/state volume, so the image can be replaced without losing the registration and no
# GitHub token is ever stored on the host. Registration is a one-off done by hand:
#   docker compose run --rm runner register <token from Settings -> Actions -> Runners -> New>
set -euo pipefail

cd /home/runner
STATE=/home/runner/state
FILES=(.runner .credentials .credentials_rsaparams)

# A fresh named volume is root-owned; the image's runner user has passwordless sudo.
[ -w "${STATE}" ] || sudo chown runner:runner "${STATE}"

if [ "${1:-}" = "register" ]; then
    token="${2:?usage: register <registration token>}"
    ./config.sh --unattended \
        --url "https://github.com/${REPO}" \
        --token "${token}" \
        --name "${RUNNER_NAME}" \
        --labels "${RUNNER_LABELS}" \
        --work _work \
        --replace \
        --disableupdate   # updates come from pulling a newer image, not from the runner itself
    cp "${FILES[@]}" "${STATE}/"
    echo "Registered as ${RUNNER_NAME}; start the service now."
    exit 0
fi

for f in "${FILES[@]}"; do
    if [ ! -f "${STATE}/${f}" ]; then
        echo "Runner is not registered yet. Run once:" >&2
        echo "  docker compose run --rm runner register <token>" >&2
        exit 1
    fi
    cp "${STATE}/${f}" .
done

exec ./run.sh
