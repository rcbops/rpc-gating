#!/usr/bin/env python

# Script used to review jenkins jobs for compliance with internal conventions:
#   * Naming conventions:
#        https://github.com/rcbops/rpc-gating#naming-conventions
#   * Retention Policy

import argparse
from datetime import datetime, timedelta
import jmespath
import operator
import os
import re
import sys
import yaml

# The python-crontab library thinks that the following
# conditions are true, so we use the croniter library
# instead as it is better at validating input.
# crontab.CronSlices.is_valid("")
# crontab.CronSlices.is_valid(None)
# crontab.CronSlices.is_valid(0)
from croniter import croniter

# In this script, a return value of 1 indicates an error has occured,
# 0 indicates success (same as bash).
# Note that in python truth, 1 -> True and 0 -> False


def parse_args():
    description = "Check JJB for RPC convention compliance"
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("--exclude-files",
                        required=False,
                        default="",
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
            if check_timed_trigger(item["project"], filename):
                _rc = 1

        if "job-template" in item:
            # check job-template name
            if parse_job_name(item["job-template"]["name"], filename):
                _rc = 1
            if check_retention(item['job-template'], filename):
                _rc = 1
            if check_timed_trigger(item['job-template'], filename):
                _rc = 1

        if "job" in item:
            # check individual job
            if parse_job_name(item["job"]["name"], filename):
                _rc = 1
            if check_retention(item['job'], filename):
                _rc = 1
            if check_timed_trigger(item['job'], filename):
                _rc = 1

        if invalid_protocol(item, filename):
            _rc = 1

    return _rc


def parse_job_name(job_name, file_name):
    regex = (
        '^([a-zA-Z0-9]+-?|\{[a-zA-Z0-9]+\}-?)+([_]([{][a-zA-Z0-9_]+[}]-?)+)?$'
    )
    match = re.match(regex, job_name)
    if not match:
        out = "Lint error: Job name \"%s\" in \"%s\"" % (job_name, file_name)
        out += "\n\tdoes not conform to rpc-gating naming conventions.\n"
        sys.stderr.write(out)
        return 1
    return 0


def parse_file_name(in_dir, in_file):
    regex = '^((Dockerfile)|(([a-z0-9]+_?)+(\.{1}[a-z0-9]+)?))$'
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
            print(e)
        else:
            return_value = 0
            break
    else:
        sys.stderr.write(error_message)

    return return_value


# Ensure all jobs have a valid timed triggers entry
# Structure:
#   job:
#     triggers:
#      - timed: "@daily"
def check_timed_trigger_value(schedule, data):
    name = data['name']

    # Values from grammar in Jenkins core:
    # https://github.com/jenkinsci/jenkins/blob/2652a1b1eb90b5859686f505a0c6459af2dc2299/core/src/main/grammar/crontab.g#L46-L76
    named_schedules = {
        "@yearly": "H H H H *",
        "@annually": "H H H H *",
        "@monthly": "H H H * *",
        "@weekly": "H H * * H",
        "@daily": "H H * * *",
        "@midnight": "H H(0-2) * * *",
        "@hourly": "H * * * *",

        # The following are from rpc-gating/rpc-jobs/defaults.yml
        "{CRON_DAILY}": "H H * * 2-7",
        "{CRON_WEEKLY}": "H H * * H(2-7)",
        "{CRON_MONTHLY}": "H H H * 2-7"
    }

    # If the trigger provided is not a string,
    # then it is not valid.
    if not isinstance(schedule, str):
        return 1

    # A comment can't be invalid.
    if is_comment(schedule):
        return 0

    # Replace Jenkins named schedules with cron patterns
    # that croniter can parse
    if schedule in named_schedules:
        schedule = named_schedules[schedule]

    if (is_valid_cron_expression(schedule, name)
            and no_maint_window_conflict(schedule, name)
            and allowed_stdjob_schedule(schedule, data)):
        return_value = 0
    else:
        return_value = 1

    return return_value


# Routine to flatten lists, for example:
# [[0, 1, 2],[[3, 4],[5, 6]], 7, 8] => [0,1,2,3,4,5,6,7,8]
def flatten(s):
    if s == []:
        return s
    if isinstance(s[0], list):
        return flatten(s[0]) + flatten(s[1:])
    return s[:1] + flatten(s[1:])


# Tests project, job-templates, and jobs for invalid protocols
# If an invalid protocol is discovered, this method will return True
# Otherwise, will return False
def invalid_protocol(job, file_name):
    ret_val = False

    # Grab the name from the project, job-template, or job
    name = jmespath.search('[*.name] | [0]', job)

    # Common error message
    error_message = ("{f}/{n}: Invalid protocol -"
                     " only 'https' protocol is allowed."
                     " Expected value similar to"
                     " 'https://github.com/...'"
                     " but received:"
                     .format(f=file_name, n=name))

    search_path = '[project.repo_url,' \
                  'project.repo_name[].*.repo_url,' \
                  'project.repo[].*.URL,' \
                  'project.repo[].*.repo_url,' \
                  '"job-template".repo.repo_url,' \
                  '"job-template".properties[].*.url,' \
                  'job.properties[].github.url,' \
                  'job."pipeline-scm".scm[].git.url]'
    values = jmespath.search(search_path, job)
    # filter out None values and flatten any lists
    values = flatten([x for x in values if x is not None])
    # DEBUG
    # sys.stderr.write("File: {}, Item: {}, values: {}\n".format(
    # file_name, name, values))
    # For each url discovered...
    for url in values:
        # sys.stderr.write("Testing: {}\n".format(url))
        if not re.search('^(https://|internal:|{[^}]+}$)', url):
            sys.stderr.write("{} {}\n".format(error_message, url))
            ret_val = True

    return ret_val


def translate_hash(schedule, rep_mode="all"):
    # rep_mode = all
    # replace H with * and ranges with *, this is for checking
    # all possible times a job could be scheduled (useful for
    # maintenance window checking)

    # repo_mode = one
    # replace H with a single value, and ranges with the lowest
    # value in the range. This is useful for checking the interval
    # between executions.

    # Jenkins supports the use of hashes, denoted by the symbol H,
    # in the trigger to allow the load to be spread on the system.
    # The croniter library does not know how to validate them, so
    # we replace them.
    # Valid inputs and what they should output after replacement:
    # 1. "H * * * *" > "* * * * *"
    # 2. "H(0-29)/10 * * * *" > "0-29/10 * * * *"
    # 3. "H/10 H(10-11) * H *" > "*/10 10-11 * * *"

    # handle the H(..) pattern first
    if rep_mode == "all":
        rep_char = "*"
        # replace a hash range with a standard range
        schedule = re.sub(r'H\((\d+-\d+)\)', r'\1', schedule)
    else:
        rep_char = "1"
        # replace a hash range with the first value in the
        # range
        schedule = re.sub(r'H\((\d+)-\d+\)', r'\1', schedule)

    # then handle the H pattern
    schedule = re.sub(r'H', rep_char, schedule)

    return schedule


def is_valid_cron_expression(schedule, name):
    raw_schedule = schedule
    schedule = translate_hash(schedule)
    if croniter.is_valid(schedule):
        return True
    else:
        sys.stderr.write("{n} Invalid cron expression: {s}"
                         " (translated from {rs})\n"
                         .format(n=name, s=schedule, rs=raw_schedule))
        return False


def is_comment(schedule):
    if re.match(r'^\s*#', schedule):
        return True
    else:
        return False


def allowed_stdjob_schedule(build_schedule, data):
    """ Standard jobs should run at most daily"""
    # non standard jobs are not validated by this rule
    if not is_standard_job(data):
        return True

    raw_schedule = build_schedule
    # This check is about the interval, so the H values
    # in the input cron expression should be translated
    # to a single value as Jenkins does
    # rather than * which represents all values.
    build_schedule = translate_hash(build_schedule, rep_mode="one")
    name = data['name']

    base = datetime.now()
    build_iter = croniter(build_schedule, base)
    build_start_1 = build_iter.get_next(datetime)
    build_start_2 = build_iter.get_next(datetime)
    delta = build_start_2 - build_start_1
    if delta < timedelta(hours=23):
        sys.stderr.write(
            "{n} Is scheduled too frequently (every {delta}), standard"
            " jobs should be executed at most daily. Cron: {rs},"
            " translated to: {s}\n".format(
                n=name,
                delta=delta,
                rs=raw_schedule,
                s=build_schedule))
        return False
    return True


def no_maint_window_conflict(build_schedule, name):
    raw_schedule = build_schedule

    # Convert H --> * as Jenkins could assign
    # any value to H.
    build_schedule = translate_hash(build_schedule)

    base = datetime.now()
    max_build_duration = timedelta(hours=10)

    maint_schedule = "0 10 * * 1"
    maint_iter = croniter(maint_schedule, base)
    maint_duration = timedelta(hours=2)

    # Find the interval between builds
    build_iter = croniter(build_schedule, base)
    next_build = build_iter.get_next(datetime)
    second_build = build_iter.get_next(datetime)
    build_interval = second_build - next_build

    # check the next 60 maintenance windows for conflicts
    # this is over a year so should cover most eventualities.
    for _ in range(60):
        maint_start = maint_iter.get_next(datetime)
        maint_end = maint_start + maint_duration

        # Start looking for conflicts between builds and the maint window
        # three build_intervals before the maint window is scheduled to start
        build_iter = croniter(build_schedule,
                              maint_start - (3 * build_interval))
        # loop over builds checking if any of them conflict with the
        # maintenance window.
        while True:
            build_start = build_iter.get_next(datetime)
            build_end = build_start + max_build_duration
            if build_start > maint_end:
                # This job invocation starts after the maintenance
                # window so can't conflict with it.
                break

            # Job starts before the maintenance window,
            # so if job end time is after maint_start the job
            # will conflict with the maintenance window.
            elif build_end > maint_start:
                error_message = (
                    "Scheduled build of {n} conflicts with Release Engineering"
                    " Maintenance Window. Build: {bs}-->{be},"
                    " Window: {ws} --> {we}. Cron: {rs} translated to: {s}"
                    " for linting."
                    " See https://rpc-openstack.atlassian.net/wiki/spaces"
                    "/RE/pages/469794817/RE+Infrastructure+Maintenance+Window"
                    " for further information and examples.\n"
                    .format(
                        n=name,
                        bs=build_start,
                        be=build_end,
                        ws=maint_start,
                        we=maint_end,
                        rs=raw_schedule,
                        s=build_schedule
                    ))
                sys.stderr.write(error_message)
                return False
    return True


def is_standard_job(data):
    standard_pm_template = (
        'PM_{repo_name}-{branch}-{image}-{scenario}-{action}')
    return standard_pm_template in data.get('jobs', [])


def check_timed_trigger(data, in_file):

    # The maintenance window start and end jobs are scheduled within the
    # maintenance window exclusion time, so skip the time checks.
    if "re_maintenance_window.yml" in in_file:
        return 0

    failures = 0

    jmes_expressions = {
        "project_key": "CRON",
        "axis_key": "*[*].*.CRON|[][]",
        "timed_triggers": "triggers[*].timed"
    }

    # Each jmes expression can return one of more schedules
    # Each schedule can be single or multi-line string.
    schedules = []
    for jmes_expression in jmes_expressions.values():
        results = jmespath.search(jmes_expression, data) or []
        if not isinstance(results, list):
            results = [results]
        for result in results:
            for line in result.split("\n"):
                if line not in ["{CRON}", ""]:
                    schedules.append(line)

    for schedule in schedules:
        failures += check_timed_trigger_value(schedule, data)

    if failures > 0:
        return 1
    else:
        return 0


if __name__ == "__main__":
    args = parse_args()
    rc = 0

    excludes = map(operator.methodcaller("strip"),
                   args.exclude_files.split(','))

    for _dir in args.dirs.split(','):

        # exclude any dirs that match args.exclude_files
        walk_list = []
        for root, dirs, files in os.walk(_dir):
            if not any(e in root for e in excludes):
                walk_list.append((root, dirs, files))

        for root, dirs, files in walk_list:
            for _file in files:
                if _file in args.exclude_files:
                    continue
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
