#!/usr/bin/env python

# Script used to review jenkins jobs for compliance with internal conventions:
#   * Naming conventions:
#        https://github.com/rcbops/rpc-gating#naming-conventions
#   * Retention Policy

import argparse
import os
import re
import sys
import yaml

import jmespath


def parse_args():
    description = "Check JJB for RPC convention compliance"
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
    print("Checking {}/{}".format(in_dir, in_file))
    _rc = 0
    filename = os.path.join(in_dir, in_file)
    file_yaml = ''
    # blanket catch errors reading/parsing yaml
    try:
        file_yaml = yaml.safe_load(open(filename).read())
    except Exception as e:
        out = ("Lint error: yaml parsing error parsing file {f}.\n"
               "Error: {e}\n").format(f=filename, e=e)
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
            if check_retention(item['job-template'], filename):
                _rc = 1

        if "job" in item:
            # check individual job
            if parse_job_name(item["job"]["name"], filename):
                _rc = 1
            if check_retention(item['job'], filename):
                _rc = 1
    return _rc


def parse_job_name(job_name, file_name):
    regex = '^([a-zA-Z0-9]+-?)+([_]([{][a-zA-Z0-9_]+[}]-?)+)?$'
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


# Ensure all jobs have a retention policy
# Structure:
#   job:
#     properties:
#      - build-discarder:
#          days-to-keep: 3

def check_retention_value(job, value_name, vmin, vmax):
    value = jmespath.search(
        'properties[*]."build-discarder"."{vn}"'.format(vn=value_name), job)[0]

    # try and convert to int, if that fails, check for templated value.
    try:
        days = int(value)
    except ValueError as e:
        # Can't check templated values, assume they are ok.
        if '{' in value and '}' in value:
            return
        else:
            raise e

    if days <= vmin or days > vmax:
        raise ValueError("Value for {vn} out of range. "
                         "Value: {v}, min: {min}, max: {max}"
                         .format(vn=value_name, v=value,
                                 min=vmin, max=vmax))


def check_retention(job, in_file):
    error_message = ("{f}/{j}: Valid build-discarder config not found."
                     " Please ensure days-to-keep (0 < x <= 365) or"
                     " num-to-keep (0 < x <= 100) is specified."
                     " Properties: {p}\n"
                     .format(j=job['name'], f=in_file,
                             p=job.get('properties')))

    checks = (
        ('days-to-keep', 0, 365),
        ('num-to-keep', 0, 100),
    )

    for (retention_type, min_retention, max_retention) in checks:
        try:
            check_retention_value(job, retention_type,
                                  min_retention, max_retention)
        except (TypeError, IndexError):
            return_value = 1
        except ValueError as e:
            return_value = 1
            print e
        else:
            return_value = 0
            break
    else:
        sys.stderr.write(error_message)

    return return_value


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
                        if parse_jjb_file(root, _file):
                            rc = 1
                    if parse_file_name(root, _file):
                        rc = 1
    if rc:
        out = "RPC-Gating JJB conventions:\n\t%s\n" % \
            "https://github.com/rcbops/rpc-gating#conventions"
        sys.stderr.write(out)

    sys.exit(rc)
