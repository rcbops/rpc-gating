#!/usr/bin/env python
from itertools import chain
import os.path
import re

import click
from sh import git, ErrorReturnCode_128
import yaml


@click.command()
@click.option(
    "--job-source",
    "job_sources",
    multiple=True,
    default=[],
    help=(
        "Format URL;commitish, e.g. "
        "https://github.com/rcbops/rpc-gating;master, or directory. If the "
        "same repository is specified multiple times, the last occurrence is "
        "used."
    ),
)
def setup(job_sources):
    job_sources_by_name = {}
    for job_source in job_sources:
        if os.path.isdir(job_source):
            name = os.path.basename(os.path.realpath(job_source))
            js = {"commitish": None, "directory": job_source, "url": None}
        else:
            url, commitish = job_source.split(";", 1)
            match = re.search(
                r"/([a-zA-Z0-9-_]+)(?:.git)?",
                url
            )
            name = match.group(1)
            js = {"commitish": commitish, "directory": name, "url": url}
        job_sources_by_name[name] = js

    paths_by_source = {}
    for name, job_source in job_sources_by_name.items():
        directory = job_source["directory"]
        if job_source["url"]:
            try:
                git.clone(
                    "--branch",
                    job_source["commitish"],
                    job_source["url"],
                    directory
                )
            except ErrorReturnCode_128:
                git.checkout(
                    job_source["commitish"],
                    _env={"GIT_DIR": directory + "/.git"}
                )

        try:
            with open("{d}/component_metadata.yml".format(d=directory)) as f:
                metadata = yaml.load(f)
        except IOError:
            pass
        else:
            paths = metadata.get("jenkins", {}).get("jjb_paths", [])
            paths_by_source[name] = [
                os.path.join(directory, path)
                for path in paths
            ]

    all_paths = chain(*paths_by_source.values())

    click.echo(":".join(all_paths))


if __name__ == "__main__":
    setup()
