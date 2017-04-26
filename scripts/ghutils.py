#!/usr/bin/env python

# This script contains github related utilties for Jenkins.


import click
import github3


@click.command()
@click.option('--tag',
              help='Jenkins build tag',
              required=True)
@click.option('--link',
              help='Link to related build in Jenkins UI',
              required=True)
@click.option('--org',
              help='Github Organisation that owns the target repo',
              required=True)
@click.option('--repo',
              help='Name of target repo',
              required=True)
@click.option('--pat',
              help="Github Personal Access Token",
              required=True)
@click.option('--label',
              help="Add label to issue, can be specified multiple times",
              multiple=True,
              required=True)
def create_issue(tag, link, org, repo, pat, label):
    gh = github3.login(token=pat)
    repo = gh.repository(org, repo)
    repo.create_issue(
        title="JBF: {tag}".format(tag=tag),
        body="[link to failing build]({url})".format(url=link),
        labels=label
    )


@click.group()
def cli():
    pass


cli.add_command(create_issue)

if __name__ == "__main__":
    cli()
