#!/bin/bash

if [ ! -f .git/hooks/pre-commit ]
then
  pushd .git/hooks
  ln -s ../../lint.sh .git/hooks/pre-commit
  popd
  echo "lint.sh installed as pre-commit hook"
else
  echo "pre commit hook already exists, abort"
fi
