#!/bin/bash -xeu

sudo apt-get update && sudo apt-get install -y \
  groovy2 python-pip build-essential python-dev libssl-dev \
  curl libffi-dev sudo git-core

pip install -c constraints.txt -r requirements.txt
pip install -c constraints.txt -r test-requirements.txt

export JAVA_HOME="/usr/lib/jvm/java-8-openjdk-amd64"
./lint.sh
