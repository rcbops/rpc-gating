#!/bin/bash -xeu

sudo apt-get update && sudo apt-get install -y \
  groovy2 python-pip build-essential python-dev libssl-dev \
  curl libffi-dev sudo git-core

pip install -c constraints.txt -r requirements.txt
pip install -c constraints.txt -r test-requirements.txt

RPC_GATING_LINT_USE_VENV=no ./lint.sh
