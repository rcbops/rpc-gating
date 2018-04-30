#!/bin/bash -xe

export LC_ALL=C.UTF-8
export LANG=C.UTF-8
python3 build_summary_gh.py \
  --cache /cache/summary.cache \
  /in/{PR_*,PM_*}/builds/*/build.xml \
  > /out/index_tmp.html \
  && cp /out/index_tmp.html /out/index.html
