#!/bin/bash -xeu

sudo apt-get update && sudo apt-get install -y \
  groovy2 python-pip build-essential python-dev libssl-dev \
  curl libffi-dev sudo git-core

pip install -c constraints.txt -r requirements.txt
pip install -c constraints.txt -r test-requirements.txt

export JAVA_HOME="/usr/lib/jvm/java-8-openjdk-amd64"

# JJB definitions can be stored in project repos, some of which are private.
# An ssh-agent is provided to the lint script so it can clone all the
# necessary repos for testing JJB definitions.

# Check if an agent is available, start one if not.
# This check prevents us from loosing access to an agent supplied by Jenkins.
ssh-add -l &>/dev/null || eval $(ssh-agent)
# Safe to re-add keys that are in the agent.
ssh-add $JENKINS_GITHUB_SSH_PRIVKEY
ssh-add -l
./lint.sh
