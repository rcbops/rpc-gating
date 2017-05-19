#!/usr/bin/env python

# This script contains jira related utilties for Jenkins.


import click
from jira import JIRA


@click.command()
@click.option('--tag',
              help='Jenkins build tag',
              required=True)
@click.option('--link',
              help='Link to related build in Jenkins UI',
              required=True)
@click.option('--project',
              help='Jira Project Key',
              required=True)
@click.option('--user',
              help="Jira User",
              required=True)
@click.option('--password',
              help="Jira Password",
              required=True)
@click.option('--type',
              help="Jira issue type",
              default="Task")
@click.option('--instance',
              help="Jira instance url",
              default="https://rpc-openstack.atlassian.net")
@click.option('--label',
              'labels',
              help="Add label to issue, can be specified multiple times",
              multiple=True,
              default=["jenkins-build-failure"])
def create_issue(tag, link, project, user, password, type, instance, labels):
    authed_jira = JIRA(instance, basic_auth=(user, password))
    authed_jira.create_issue(
        project=project,
        summary="JBF: {tag}".format(tag=tag),
        description="Jenkins Build Failed :( [{tag}|{url}]".format(
            url=link,
            tag=tag
        ),
        issuetype={'name': type},
        labels=labels
    )


@click.group()
def cli():
    pass


cli.add_command(create_issue)

if __name__ == "__main__":
    cli()
