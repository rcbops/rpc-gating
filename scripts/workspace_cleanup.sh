#!/usr/bin/env bash

echo "Removing Workspaces that haven't been modified in the last 7 days"
set -xe
cd /var/lib/jenkins/workspace \
  && find . -maxdepth 1 -ctime +7 \
    |while read old; do rm -rf "$old"; done
