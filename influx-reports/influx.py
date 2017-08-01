#!/usr/bin/env python3
""" Get all measurements for an influx job, and
outputs a report of uptime for maas_ components.
Inputs:
    - env.INFLUX_IP (mandatory)
    - env.INFLUX_PORT (mandatory)
    - env.BUILD_TAG (mandatory)
    - ymlreport <ymlreportfile> (optional)
    - subunitreport <subunitreport> (optional)
Outputs:
    - stdout
    - yaml file with uptime (when ymlreport is used)
    - subunit binary file with passing/failure criteria
    (when subunitreport is used)
"""

import argparse
from collections import defaultdict
import datetime
import logging
import os
import math
import sys
import yaml

import dateutil.parser
from influxdb import InfluxDBClient
from subunit.v2 import StreamResultToBytes


class InfluxTimestampParseException(Exception):
    pass


class SubunitContext():
    """Context manager for writing subunit results."""

    def __init__(self, output_path):
        self.output_path = output_path

    def __enter__(self):
        self.output_stream = open(self.output_path, 'wb+')
        self.output = StreamResultToBytes(self.output_stream)
        self.output.startTestRun()
        return self.output

    def __exit__(self, *args, **kwargs):
        self.output.stopTestRun()
        self.output_stream.close()

    def status(self, *args, **kwargs):
        self.output.status(*args, **kwargs)


def return_time(client, query, delta_seconds=0):
    """ From an InfluxDB query, fetch
    the first point time, and return a
    python time object. Shift it from
    a few seconds (delta_seconds) if necessary.
    """
    timestamp_query = client.query(query)
    # Get points is generator, we should just get first
    # point time (string type).
    fpt_str = next(timestamp_query.get_points())['time']
    try:
        fpt = dateutil.parser.parse(
            fpt_str) + datetime.timedelta(seconds=delta_seconds)
    except Exception as alle:
        raise InfluxTimestampParseException(
            "Error parsing a timestamp from influx: {}".format(alle))
    return fpt


def calculate_measurement_uptime(client, measurement, job_reference,
                                 measurement_period_seconds,
                                 resolution=60):
    """ For a certain build (job_reference), estimate the amount
    of seconds a component (column of table) was up.
    For that, we slice the job by resolution.
    If I have no data in the slice, I assume downtime. If I have
    at least one data per slice, I assume up, even if degraded.
    The count of these positive events multiplied by resolution
    is an average idea of the uptime.
    """
    per_field_up_percent = defaultdict(float)

    query = (
        "select max(/.*_status/) from {measurement} "
        "where time < now() and job_reference='{job_reference}' "
        "group by time({resolution}s) fill(-1)"
    ).format(measurement=measurement,
             job_reference=job_reference,
             resolution=resolution)

    max_amount_of_time_slices = math.ceil(
        float(measurement_period_seconds) / resolution)

    all_data = client.query(query)
    for time_slice in all_data.get_points():
        for element in time_slice:
            if element != 'time' and time_slice[element] == 1:
                key = element.replace('max_', '').replace('_status', '')
                # Mark the field as up
                per_field_up_percent[key] += (1 / max_amount_of_time_slices)
                # Mark the measurement as up

    return dict(
        per_field_uptime_percent=dict(per_field_up_percent)
    )


def build_report_dict(client, measurements, job_reference,
                      measurement_period_seconds):
    report = defaultdict(dict)
    for measurement in measurements:
        measurement_data = calculate_measurement_uptime(
            client, measurement, job_reference, measurement_period_seconds)
        report['runtime_seconds'] = measurement_period_seconds
        report['per_field_uptime_percent'][measurement] = \
            dict((measure, round(measure_uptime * 100, 2)) for
                 measure, measure_uptime in
                 measurement_data['per_field_uptime_percent'].items())
    return dict(report)


def main(args=None):
    client = InfluxDBClient(args.influx_ip, args.influx_port,
                            database='telegraf')
    """
    Influx structure:
        Measurement (eg maas_nova)
            Point (a timestamp with data)
                Tags (Indexed K/V, eg job_reference)
                Fields (Unindexed K/V, eg keystone_user_count)
    """
    # First find the first and last timestamp from telegraf.
    # This way querying maas_* data will always
    # be accurate (if no data is reported by maas plugins,
    # this should be a failure metric)
    find_first_timestamp_query = (
        "select first(total) from processes "
        "where job_reference = '{}';".format(args.job_reference)
    )
    first_ts = return_time(client, find_first_timestamp_query, delta_seconds=5)
    find_last_timestamp_query = (
        "select last(total) from processes "
        "where job_reference = '{}';".format(args.job_reference))
    last_ts = return_time(client, find_last_timestamp_query, delta_seconds=-5)

    measurement_period_seconds = (last_ts - first_ts).total_seconds()

    logging.info(
        ("Metrics were gathered between {first} and {last}\n"
         "For a total of {seconds} seconds".format(
             first=first_ts,
             last=last_ts,
             seconds=measurement_period_seconds)))

    measurements = ['maas_glance', 'maas_cinder', 'maas_keystone',
                    'maas_heat', 'maas_neutron', 'maas_nova', 'maas_horizon']

    report = build_report_dict(
        client, measurements, args.job_reference, measurement_period_seconds)

    yml_report = yaml.safe_dump(report, default_flow_style=False)
    logging.info(yml_report)
    if args.ymlreport:
        with open(args.ymlreport, 'w+') as output_file:
            output_file.write("---\n")
            output_file.write(yml_report)

    # Subunit is a unit test result format. Here we output a stream of
    # test results, one per measurement.
    # Each measurement is a successfull test if its overall uptime %age
    # is higher than args.min_uptime
    if args.subunitreport:
        with SubunitContext(args.subunitreport) as output:
            for measurement_name, measurement_data in \
                    report['per_field_uptime_percent'].items():
                for measure_name, uptime_percentage in \
                        measurement_data.items():
                    status = "fail"
                    if uptime_percentage > args.min_uptime:
                        status = "success"
                    # Record test start
                    output.status(
                        test_id=measure_name,
                        timestamp=first_ts
                    )

                    # Record end of test
                    output.status(
                        test_id=measure_name,
                        # TODO(hughsaunders): Be more intelligent
                        # about thresholds
                        test_status=status,
                        test_tags=None,
                        runnable=False,
                        file_name=measure_name,
                        file_bytes=" {}% lower than threshold".format(
                            uptime_percentage).encode('ascii'),
                        timestamp=last_ts,
                        eof=True,
                        mime_type='text/plain; charset=UTF8'
                    )


if __name__ == "__main__":
    """ Args parser and logic router """
    logging.getLogger().setLevel(logging.INFO)
    parser = argparse.ArgumentParser(
        description='Fetch maas_ metrics, and report downtime data.')
    parser.add_argument("--ymlreport", help="Yaml report filename")
    parser.add_argument("--subunitreport", help="Subunit report filename")
    parser.add_argument(
        "--min-uptime",
        help="Minimum uptime required (percentage)",
        default=1,
        type=float)
    arguments = parser.parse_args()
    try:
        arguments.influx_ip = os.environ['INFLUX_IP']
    except KeyError:
        logging.error("Please set INFLUX_IP")
        sys.exit(1)

    try:
        arguments.influx_port = os.environ['INFLUX_PORT']
    except KeyError:
        logging.error("Please set INFLUX_PORT")
        sys.exit(2)

    try:
        arguments.job_reference = os.environ['BUILD_TAG']
    except KeyError:
        logging.error("Please set BUILD_TAG for its usage as job ref")
        sys.exit(3)
    main(arguments)
