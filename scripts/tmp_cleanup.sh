#!/usr/bin/env bash

echo "Remove old pip build directories"
set -xe
mkdir -p /var/lib/jenkins/tmp
cd /var/lib/jenkins/tmp \
  && find . -maxdepth 1 -mtime +2 -type d -name "pip*" \
    -exec rm -rf {} \;
