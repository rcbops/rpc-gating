PYTHON_PATH="${WORKSPACE}/.venv/bin/python"
REPORTS_FOLDER="${WORKSPACE}/test_results"
INFLUX_DOWNTIME_FOLDER="$(dirname "${BASH_SOURCE[0]}")"

mkdir -p "${REPORTS_FOLDER}"
$PYTHON_PATH $INFLUX_DOWNTIME_FOLDER/influx.py --ymlreport $REPORTS_FOLDER/influx-uptime.yml --subunitreport $REPORTS_FOLDER/influx-uptime.bin
