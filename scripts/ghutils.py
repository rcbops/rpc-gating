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


@click.command()
@click.option('--org',
              help='Github Organisation that owns the target repo',
              required=True)
@click.option('--repo',
              help='Name of target repo',
              required=True)
@click.option('--pat',
              help="Github Personal Access Token",
              required=True)
@click.option('--title',
              help="Title of pull request",
              required=True)
@click.option('--base',
              help="Base branch to PR onto",
              required=True)
@click.option('--head',
              help="Branch to create a pull request for",
              required=True)
@click.option('--body',
              help="Body of pull request",
              required=True)
def create_pr(org, repo, pat, title, base, head, body):
    gh = github3.login(token=pat)
    repo = gh.repository(org, repo)
    repo.create_pull(
        title=title,
        base=base,
        head=head,
        body=body
    )


@click.group()
def cli():
    pass


cli.add_command(create_issue)
cli.add_command(create_pr)

if __name__ == "__main__":
    cli()
