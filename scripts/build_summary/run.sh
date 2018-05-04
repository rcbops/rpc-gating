#!/bin/bash -xe

export LC_ALL=C.UTF-8
export LANG=C.UTF-8
python3 build_summary_gh.py --jsonfile /out/data.json /in
