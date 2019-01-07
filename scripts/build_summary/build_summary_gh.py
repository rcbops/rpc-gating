#!/usr/bin/env python
from __future__ import print_function

# Stdlib import
import datetime
import dateutil.parser
import functools
import gc
import json
import os
import re
import traceback
import uuid

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


@to_serializable.register(uuid.UUID)
def _uuid(uuid):
    return str(uuid)


def serialise(obj):
    return json.dumps(obj, default=to_serializable)


@click.command(help='arg is a jenkins jobs dir')
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

    data = dict(builds={})
    # read data from json input file
    if os.path.exists(jsonfile):
        try:
            with open(jsonfile, 'r') as f:
                data = json.load(f)
        except Exception as e:
            print(
                "Failed to read json file: {jsonfile}"
                .format(jsonfile=jsonfile))
            traceback.print_exc()

    # Current production data.json has some extremely long failure detail
    # fields. This commit includes a change to failure.py to ensure
    # that doesn't happen in future. However to deal with the problem
    # on disk, we load and truncate the fields here.
    # At the end of this run, the data file will be rewritten with
    # truncated values, so this fix code will only be needed once.
    if "failures" in data:
        for id, failure in data['failures'].items():
                failure['detail'] = failure['detail'][:1000]

    # create set of build ids so we don't scan builds
    # we already have summary information about
    if "builds" in data:
        build_dict = {"{jn}_{bn}".format(jn=b['job_name'], bn=b['build_num']):
                      b for b in data['builds'].values()}
    else:
        build_dict = {}

    # These dicts store builds and failures read in from
    # the input json file that will also be written
    # to the output json file.
    cached_builds = {}
    cached_failures = {}

    # walk the supplied dir, scan new builds
    parse_failures = 0
    build_files = list(enumerate(["{}/build.xml".format(root)
                       for root, dirs, files
                       in os.walk(jobsdir)
                       if "build.xml" in files
                       and re.match("(P[MR]|RE(LEASE)?|Pull)[-_]", root)]))
    for count, build in build_files:
        path_groups_match = re.search(
            ('^(?P<build_folder>.*/(?P<job_name>[^/]+)/'
             'builds/(?P<build_num>[0-9]+))/'), build)
        if path_groups_match:
            if (count % 100 == 0):
                gc.collect()
                total = len(build_files)
                print("{}/{} ({:.2f} %)".format(
                    count,
                    total,
                    float(count / total) * 100
                ))
            path_groups = path_groups_match.groupdict()
            job_name = path_groups['job_name']
            build_num = path_groups['build_num']
            key = "{job_name}_{build_num}".format(
                job_name=job_name,
                build_num=build_num
            )
            if key in build_dict:
                try:
                    # build already cached, don't need to rescan
                    # But we do need to ensure that the cached data is age
                    # checked and added to a dict of cached items to be
                    # written out at the end.
                    b = build_dict[key]
                    if dateutil.parser.parse(b["timestamp"]) > age_limit:
                        cached_builds[b["id"]] = build_dict[key]
                        # ensure all referenced failures are also stored
                        for failure_id in b["failures"]:
                            print("f", end="")
                            f = data["failures"][failure_id]
                            cached_failures[f["id"]] = f
                    print("c", end="")
                    continue
                except Exception as e:
                    # failed to process cache, read the build log
                    # as if it wasn't cached.
                    # ! = cache read failure
                    print("cache failure: " + str(e))
                    print("!", end="")
            try:
                build = Build(
                    build_folder=path_groups['build_folder'],
                    job_name=path_groups['job_name'],
                    build_num=path_groups['build_num'])
                if build.timestamp > age_limit:
                    # if build.failed:
                    # failed check removed, as not all failures are fatal
                    # especially those that relate to re infrastructure
                    # as we attempt to insulate those from affecting the
                    # build reult. However measuring their frequency is
                    # still useful

                    # store the log in memory only as long as necessary
                    build.log_lines = build.read_logs()
                    Failure.scan_build(build)
                    build.log_lines = []
                    build_dict[key] = build
                    # . = build read ok
                    print(".", end="")
                    # print("OK: {key}\n".format(key=key))
                else:
                    # o = old
                    print("o", end="")
                    # print("Old Build: {key}\n" .format(key=key))
            except lxml.etree.XMLSyntaxError as e:
                print("\nFAIL: {key} {e}\n".format(key=key, e=e))
                parse_failures += 1
            except Exception as e:
                parse_failures += 1
                print("\nFAIL: {key} {e}\n".format(key=key, e=e))
                if ("can't parse internal" not in str(e)):
                    traceback.print_exc()

    print("\nbuilds: {} failures: {}".format(len(build_dict.keys()),
                                             parse_failures))

    # dump data out to json file
    # remove builds older than RETENTION_DAYS
    # ensure we only dump data newer than RETENTION_DAYS

    with open(jsonfile, "w") as f:

        cache_dict = dict(
            builds={id: build for id, build in Build.builds.items()
                    if build.timestamp > age_limit},
            failures={id: f for id, f in Failure.failures.items()
                      if f.build.timestamp > age_limit},
            timestamp=datetime.datetime.now(),
            retention_days=RETENTION_DAYS
        )
        # debug statements for combining previously cached
        # builds and failures with builds and failures
        # detected on this run
        print("\nNew Builds: {lcdb}"
              "\nNew Failures: {lcdf}"
              "\nBuilds carried forward: {lcb}"
              "\nFailures carried forward: {lcf}"
              .format(lcdb=len(cache_dict["builds"]),
                      lcdf=len(cache_dict["failures"]),
                      lcb=len(cached_builds),
                      lcf=len(cached_failures)))

        cache_dict["builds"].update(cached_builds)
        cache_dict["failures"].update(cached_failures)

        # convert objects to dicts for storage, this would be done
        # by serialise() but its easier to do the integrity
        # check when all the values are of the same type.
        for id, build in cache_dict["builds"].items():
            if type(build) is not dict:
                cache_dict["builds"][id] = build.get_serialisation_dict()
        for id, failure in cache_dict["failures"].items():
            if type(failure) is not dict:
                cache_dict["failures"][id] = failure.get_serialisation_dict()

        def build_integrity_fail(id):
            print("Integrity fail for build: {}".format(id))
            del cache_dict["builds"][id]

        def failure_integrity_fail(id):
            print("Integrity fail for failure: {}".format(id))
            del cache_dict["failures"][id]

        # integrity check
        # its important the data set is consistent as the
        # UI assumes consistency. Its better to remove a few
        # inconsistent items than have the whole UI die.
        for id, build in cache_dict["builds"].copy().items():
            try:
                if build["id"] != id:
                    build_integrity_fail(id)
                for failure in build["failures"]:
                    if failure not in cache_dict["failures"]:
                        build_integrity_fail(id)
                        break
            except Exception as e:
                    print("Build integrity exception: " + str(e))
                    build_integrity_fail(id)

        for id, failure in cache_dict["failures"].copy().items():
            try:
                if (failure["id"] != id
                        or failure["build"] not in cache_dict["builds"]):
                    failure_integrity_fail(id)
            except Exception:
                    failure_integrity_fail(id)

        cache_string = serialise(cache_dict)
        f.write(cache_string)


if __name__ == '__main__':
    summary()
