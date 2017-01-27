#!/bin/bash

if [ ! -f .git/hooks/pre-commit ]
then
  ln lint.sh .git/hooks/pre-commit
  echo "lint.sh installed as pre-commit hook"
else
  echo "pre commit hook already exists, abort"
fi
