#!/usr/bin/env python

# Stdlib import
import datetime
import functools
import gc
import json
import os
import re
import sys
import traceback

# 3rd Party imports
import click
import lxml

# Project imports
from build import Build
from failure import Failure

# # Jenkins Build Summary Script
# This script reads all the build.xml files specified and prints a summary of
# each job.  This summary includes the cluster it ran on and all the parent
# jobs.  Note that EnvVars.txt is read from the same dir as build.xml.

# Builds older than this will not be cached. This is to prevent the cache
# from growing indefinitely even though jenkins is only retaining 30 days of
# builds. This value should be the same as the jenkins retain days value.
RETENTION_DAYS = 60


# The following methods are for serialising various types of objects that
# the default JSONEncoder can't handle. Single dispatch is used to add
# multiple implmentations to the same method name. At runtime an implementation
# will be looked up based on the type of the first argument
@functools.singledispatch
def to_serializable(obj):
    return json.JSONEncoder.default([], obj)


@to_serializable.register(Build)
@to_serializable.register(Failure)
def to_s_projectobjs(obj):
    return obj.get_serialisation_dict()


# how do I get a reference to dict_values?
@to_serializable.register(type({}.values()))
def to_s_dictv(obj):
    return list(obj)


@to_serializable.register(datetime.datetime)
def _datetime(dt):
    return str(dt)


def serialise(obj):
    return json.dumps(obj, default=to_serializable).replace("'", "\'")


@click.command(help='args are paths to jenkins build.xml files')
@click.argument('jobsdir')
@click.option('--newerthan', default=0,
              help='Build IDs older than this will not be shown')
@click.option('--jsonfile', default='/opt/jenkins/www/.cache')
def summary(jobsdir, newerthan, jsonfile):

    # calculate age limit based on retention days,
    # builds older than this will be ignored weather
    # they are found in json or jobdir.
    age_limit = (datetime.datetime.now()
                 - datetime.timedelta(days=RETENTION_DAYS))

    data = dict(builds=[])
    # read data from json input file
    if os.path.exists(jsonfile):
        try:
            with open(jsonfile, 'r') as f:
                data = json.load(f)
        except Exception as e:
            sys.stderr.write(
                "Failed to read json file: {jsonfile}"
                .format(jsonfile=jsonfile))
            traceback.print_exc(file=sys.stderr)

    # Current production data.json has some extremely long failure detail
    # fields. This commit includes a change to failure.py to ensure
    # that doesn't happen in future. However to deal with the problem
    # on disk, we load and truncate the fields here.
    # At the end of this run, the data file will be rewritten with
    # truncated values, so this fix code will only be needed once.
    for b in data['builds']:
        for f in b['failures']:
            f['detail'] = f['detail'][:1000]

    # create set of build ids so we don't scan builds
    # we already have summary information about
    build_dict = {"{jn}_{bn}".format(jn=b['job_name'], bn=b['build_num']):
                  b for b in data['builds']}

    # walk the supplied dir, scan new builds
    for count, build in enumerate(["{}/build.xml".format(root)
                                  for root, dirs, files
                                  in os.walk(jobsdir)
                                  if "build.xml" in files
                                  and ("PM_" in root or "PR_" in root)]):
        path_groups_match = re.search(
            ('^(?P<build_folder>.*/(?P<job_name>[^/]+)/'
             'builds/(?P<build_num>[0-9]+))/'), build)
        if path_groups_match:
            path_groups = path_groups_match.groupdict()
            job_name = path_groups['job_name']
            build_num = path_groups['build_num']
            key = "{job_name}_{build_num}".format(
                job_name=job_name,
                build_num=build_num
            )
            if key in build_dict:
                continue
            try:
                build = Build(
                    build_folder=path_groups['build_folder'],
                    job_name=path_groups['job_name'],
                    build_num=path_groups['build_num'])
                if build.timestamp > age_limit:
                    if build.failed:
                        # store the log in memory only as long as necessary
                        build.log_lines = build.read_logs()
                        Failure.scan_build(build)
                        build.log_lines = []
                        if (count % 25 == 0):
                            gc.collect()
                    build_dict[key] = build
                    sys.stderr.write(".")
                    # sys.stderr.write("OK: {key}\n".format(key=key))
                else:
                    sys.stderr.write("_")
                    # sys.stderr.write("Old Build: {key}\n" .format(key=key))
            except lxml.etree.XMLSyntaxError as e:
                sys.stderr.write("\nFAIL: {key} {e}\n".format(key=key, e=e))
            except Exception as e:
                sys.stderr.write("\nFAIL: {key} {e}\n".format(key=key, e=e))
                if ("can't parse internal" not in str(e)):
                    traceback.print_exc(file=sys.stderr)

    # dump data out to json file
    # remove builds older than RETENTION_DAYS
    # ensure we only dump data newer than RETENTION_DAYS

    with open(jsonfile, "w") as f:
        f.write(serialise(dict(
            builds=build_dict.values(),
            timestamp=datetime.datetime.now(),
            retention_days=RETENTION_DAYS
        )))

    #
    # Pickle build objs newer than RETENTION_DAYS to the cache file, so those
    # logs don't need to be reprocessed on the next run.
    # cache_dict = {}
    # for key, build in buildobjs.items():
    #     if build.timestamp > age_limit:
    #         cache_dict[key] = build
    #         # don't cache log_lines as the cache size would get unmanagably
    #         # large
    #         build.log_lines = []
    # with open(cache, 'wb') as f:
    #     pickle.dump(cache_dict, f, pickle.HIGHEST_PROTOCOL)


if __name__ == '__main__':
    summary()
