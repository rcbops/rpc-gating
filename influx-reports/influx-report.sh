#!/bin/bash
PYTHON_PATH="${WORKSPACE}/.venv/bin/python"
REPORTS_FOLDER="/var/log/influx_test_results"
INFLUX_DOWNTIME_FOLDER="$(dirname "${BASH_SOURCE[0]}")"
ADDITIONAL_ARGS="$*"

mkdir -p "${REPORTS_FOLDER}"
$INFLUX_DOWNTIME_FOLDER/influx.py --ymlreport $REPORTS_FOLDER/influx-downtime.yml --subunitreport $REPORTS_FOLDER/influx-downtime.bin $ADDITIONAL_ARGS
