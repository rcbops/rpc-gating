#!/usr/bin/env python

import argparse
from collections import defaultdict
from copy import deepcopy
from datetime import datetime, timedelta
from glob import iglob
import logging
import os
import sys

import dateutil
from influxdb import InfluxDBClient
import pytz
from subunit.v2 import StreamResultToBytes
import yaml


LEAPFROG_STAGES = [
    {
        "stage_name": "RPCO/ubuntu14-leapfrog.sh",
        "filename": "deploy-rpc.complete",
        "stages": [
            {
                "stage_name": "RPCO/pre_leap.sh",
                "filename": "rpc-prep.complete",
            },
            {
                "stage_name": "OSA-OPS/run-stages.sh",
                "filename": "osa-leap.complete",
                "stages": [
                    {
                        "stage_name": "OSA-OPS/prep.sh",
                        "filename": "openstack-ansible-prep-finalsteps.leap",
                    },
                    {
                        "stage_name": "OSA-OPS/upgrade.sh",
                        "filename": (
                            "openstack-ansible-upgrade-hostupgrade.leap"
                        ),
                    },
                    {
                        "stage_name": "OSA-OPS/migrations.sh",
                        "filename": "openstack-ansible-14.*-db.leap",
                    },
                    {
                        "stage_name": "OSA-OPS/redeploy.sh",
                        "filename": "osa-leap.complete",
                        "stages": [
                            {
                                "stage_name": "RPCO/pre_redeploy.sh",
                                "filename": (
                                    "rebootstrap-ansible-for-rpc.complete"
                                ),
                            },
                        ]
                    },
                ]
            },
            {
                "stage_name": "RPCO/post_leap.sh",
                "filename": "deploy-rpc.complete",
            },
        ]
    },
]


class InfluxTimestampParseException(Exception):
    pass


class MultipleFilenameMatches(Exception):
    pass


class SubunitContext():
    """Context manager for writing subunit results."""

    def __init__(self, output_path):
        self.output_path = output_path

    def __enter__(self):
        self.output_stream = open(self.output_path, "wb+")
        self.output = StreamResultToBytes(self.output_stream)
        self.output.startTestRun()
        return self.output

    def __exit__(self, *args, **kwargs):
        self.output.stopTestRun()
        self.output_stream.close()


def generate_reports(data, max_downtime=100, ymlfile=None, subunitfile=None):
    yml_report = yaml.safe_dump(data, default_flow_style=False)
    logging.info(yml_report)
    if ymlfile:
        with open(ymlfile, "w+") as output_file:
            output_file.write("---\n")
            output_file.write(yml_report)

    if subunitfile:
        with SubunitContext(subunitfile) as output:
            for stage in data:
                for svc_name, svc_data in stage["services_states"].items():
                    status = "fail"
                    if svc_data["DOWN percentage"] < max_downtime:
                        status = "success"
                    # Record test start
                    output.status(
                        test_id=svc_name,
                        timestamp=stage["started"]
                    )

                    # Record end of test
                    output.status(
                        test_id=svc_name,
                        # TODO(hughsaunders): Be more intelligent
                        # about thresholds
                        test_status=status,
                        test_tags=None,
                        runnable=False,
                        file_name=svc_name,
                        file_bytes=" {}% higher than threshold".format(
                            svc_data["DOWN percentage"]).encode("ascii"),
                        timestamp=stage["completed"],
                        eof=True,
                        mime_type="text/plain; charset=UTF8"
                    )


def get_mtime(filename, leapfiledir, completefiledir):
    if filename.endswith(".complete"):
        directory = completefiledir
    elif filename.endswith(".leap"):
        directory = leapfiledir
    else:
        directory = "./"

    paths = iglob(os.path.join(directory, filename))
    path = next(paths)
    try:
        next(paths)
    except StopIteration:
        pass
    else:
        raise MultipleFilenameMatches
    file_stats = os.stat(path)

    return datetime.utcfromtimestamp(file_stats.st_mtime).replace(
        microsecond=0, tzinfo=pytz.utc,
    )


def get_downtime(client, build, start, end):
    measurements = [
        "maas_glance", "maas_cinder", "maas_keystone", "maas_heat",
        "maas_neutron", "maas_nova", "maas_horizon", "ping",
    ]
    resolution = 60

    start_rounded = start.replace(second=0)
    end_rounded = end.replace(second=0) + timedelta(minutes=1)
    max_downtime_rounded = (end_rounded - start_rounded).total_seconds()

    service_state = defaultdict(lambda: {"up": 0, "down": 0, "unknown": 0})

    for measurement in measurements:
        query = (
            "select max(/.*_status|percent_packet_loss/) "
            "from {measurement} "
            "where time > '{start:%Y-%m-%d %H:%M:%S}' "
            "and time < '{end:%Y-%m-%d %H:%M:%S}' "
            "and job_reference='{build}' "
            "group by time({resolution}s) fill(-1)"
        ).format(
            start=start,
            end=end,
            build=build,
            measurement=measurement,
            resolution=resolution,
        )

        all_data = client.query(query)
        elements = (
            (name, value)
            for time_slice in all_data.get_points()
            for name, value in time_slice.items()
            if name != "time"
        )
        for name, value in elements:
            key = str(name.replace("max_", "").replace("_status", ""))
            if key == "percent_packet_loss":
                value = 1 if value == 0 else value
            if value == 1:
                service_state[key]["up"] += resolution
            elif value == -1:
                service_state[key]["unknown"] += resolution
            else:
                service_state[key]["down"] += resolution

    return {
        svc: {
            "UP hh:mm:ss": str(timedelta(seconds=secs["up"])),
            "UP percentage": round(
                100 * secs["up"] / max_downtime_rounded, 1
            ),
            "DOWN hh:mm:ss": str(timedelta(seconds=secs["down"])),
            "DOWN percentage": round(
                100 * secs["down"] / max_downtime_rounded, 1
            ),
            "UNKNOWN hh:mm:ss": str(timedelta(seconds=secs["unknown"])),
            "UNKNOWN percentage": round(
                100 * secs["unknown"] / max_downtime_rounded, 1
            ),
        }
        for svc, secs in service_state.items()
    }


def return_time(client, query, delta_seconds=0):
    """ From an InfluxDB query, fetch
    the first point time, and return a
    python time object. Shift it from
    a few seconds (delta_seconds) if necessary.
    """
    timestamp_query = client.query(query)
    # Get points is generator, we should just get first
    # point time (string type).
    fpt_str = next(timestamp_query.get_points())["time"]
    try:
        fpt = (
            dateutil.parser.parse(fpt_str) +
            timedelta(seconds=delta_seconds)
        )
    except Exception as e:
        raise InfluxTimestampParseException(
            "Error parsing a timestamp from influx: {}".format(e)
        )
    return fpt


def add_time(client, build, stages, started, leapfiledir, completefiledir):
    for stage in stages:
        completed = get_mtime(stage["filename"], leapfiledir, completefiledir)
        del stage["filename"]
        duration = completed - started
        stage["started"] = started
        stage["completed"] = completed
        stage["duration"] = str(duration)
        stage["services_states"] = get_downtime(
            client, build, started, completed
        )
        add_time(
            client,
            build,
            stage.get("stages", []),
            started,
            leapfiledir,
            completefiledir,
        )
        started = completed


def get_build_data(
    client, build, leapfrog=False, leapfiledir=None, completefiledir=None
        ):
    if leapfrog:
        # This is the first marker file created and so approximately the start
        start_time = get_mtime("clone.complete", leapfiledir, completefiledir)
        stages = deepcopy(LEAPFROG_STAGES)
        add_time(
            client, build, stages, start_time, leapfiledir, completefiledir
        )
    else:
        # First find the first and last timestamp from telegraf.
        # This way querying maas_* data will always
        # be accurate (if no data is reported by maas plugins,
        # this should be a failure metric)
        find_first_timestamp_query = (
            "select first(total) from processes "
            "where job_reference = '{}';".format(build)
        )
        started = return_time(
            client, find_first_timestamp_query, delta_seconds=5
        )
        find_last_timestamp_query = (
            "select last(total) from processes "
            "where job_reference = '{}';".format(build))
        completed = return_time(
            client, find_last_timestamp_query, delta_seconds=-5
        )
        services_states = get_downtime(
            client, build, started, completed
        )
        stages = [
            {
                "stage_name": "Upgrade",
                "services_states": services_states,
                "stages": [],
                "started": started,
                "completed": completed,
                "duration": str(completed - started),
            }
        ]

    return stages


def main(args):
    client = InfluxDBClient(
        args.influx_ip, args.influx_port, database="telegraf"
    )
    stages = get_build_data(
        client,
        args.build_ref,
        leapfrog=args.leapfrog_upgrade,
        leapfiledir=args.leapfiledir,
        completefiledir=args.completefiledir,
    )
    generate_reports(
        data=stages,
        max_downtime=100,
        ymlfile=args.ymlreport,
        subunitfile=args.subunitreport,
    )


if __name__ == "__main__":
    """ Args parser and logic router """
    logging.getLogger().setLevel(logging.INFO)
    parser = argparse.ArgumentParser(
        description="Fetch maas_ metrics, and report downtime data.")
    parser.add_argument("--ymlreport", help="Yaml report filename")
    parser.add_argument("--subunitreport", help="Subunit report filename")
    parser.add_argument(
        "--max-downtime",
        help="Maximum allowed downtime (percentage)",
        default=99.0,
        type=float,
    )
    parser.add_argument("--leapfrog-upgrade", action="store_true")
    parser.add_argument(
        "--leapfiledir",
        default="/opt/rpc-leapfrog/leap42",
    )
    parser.add_argument(
        "--completefiledir",
        default="/etc/openstack_deploy/upgrade-leap",
    )
    arguments = parser.parse_args()
    try:
        arguments.influx_ip = os.environ["INFLUX_IP"]
    except KeyError:
        logging.error("Please set INFLUX_IP")
        sys.exit(1)

    arguments.influx_port = os.environ.get("INFLUX_PORT", "8086")

    try:
        arguments.build_ref = os.environ["BUILD_TAG"]
    except KeyError:
        logging.error("Please set BUILD_TAG for its usage as job ref")
        sys.exit(3)
    main(arguments)
