#!/usr/bin/env python

# Script used to review jenkins jobs and report job names
# not following conventions
# https://github.com/rcbops/rpc-gating#naming-conventions

import argparse
import os
import re
import sys
import yaml


def parse_args():
    description = "Parses given directories for rpc-gating naming conventions"
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("--exclude-files",
                        required=False,
                        default=[],
                        help="Comma seperated list of files to exclude")
    parser.add_argument("--dirs",
                        required=True,
                        help="Comma seperated dirs to parse")
    return parser.parse_args()


def parse_jjb_file(in_dir, in_file):
    _rc = 0
    filename = os.path.join(in_dir, in_file)
    file_yaml = ''
    # blanket catch errors reading/parsing yaml
    try:
        file_yaml = yaml.safe_load(open(filename).read())
    except:
        out = "Lint error: yaml parsing error parsing file %s\n" % (filename)
        sys.stderr.write(out)
        return 1

    # Catch NoneType from blank yaml
    if not file_yaml:
        out = "Lint error: NoneType returned for yaml file %s\n" % (filename)
        sys.stderr.write(out)
        return 1

    for item in file_yaml:
        if "project" in item:
            # check project name
            if parse_job_name(item["project"]["name"], filename):
                _rc = 1
            # check project jobs names
            for job in item["project"]["jobs"]:
                if parse_job_name(job, filename):
                    _rc = 1

        if "job-template" in item:
            # check job-template name
            if parse_job_name(item["job-template"]["name"], filename):
                _rc = 1

        if "job" in item:
            # check individual job
            if parse_job_name(item["job"]["name"], filename):
                _rc = 1
    return _rc


def parse_job_name(job_name, file_name):
    regex = '^([a-zA-Z0-9]+-?)+([_]([{][a-zA-Z0-9]+[}]-?)+)?$'
    match = re.match(regex, job_name)
    if not match:
        out = "Lint error: Job name \"%s\" in \"%s\"" % (job_name, file_name)
        out += "\n\tdoes not conform to rpc-gating naming conventions.\n"
        sys.stderr.write(out)
        return 1
    return 0


def parse_file_name(in_dir, in_file):
    regex = '^([a-z0-9]+_?)+(\.{1}[a-z]+)?$'
    filename = os.path.join(in_dir, in_file)
    match = re.match(regex, in_file)
    if not match:
        out = "Lint error: filename \"%s\"\n\t does not " % filename
        out += "conform to rpc-gating conventions\n"
        sys.stderr.write(out)
        return 1
    return 0


if __name__ == "__main__":
    args = parse_args()
    rc = 0
    for _dir in args.dirs.split(','):
        for root, dirs, files in os.walk(_dir):
            for _file in files:
                if _file in args.exclude_files:
                    pass
                else:
                    if (_file.endswith(".yml")) or (_file.endswith(".yaml")):
                        if parse_jjb_file(_dir, _file):
                            rc = 1
                    if parse_file_name(_dir, _file):
                        rc = 1
    if rc:
        out = "RPC-Gating naming conventions:\n\t%s\n" % \
            "https://github.com/rcbops/rpc-gating#naming-conventions"
        sys.stderr.write(out)

    sys.exit(rc)
