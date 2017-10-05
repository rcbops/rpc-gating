#!/usr/bin/env python

# This script contains jira related utilties for Jenkins.


import click
from jira import JIRA, JIRAError


@click.group()
@click.option('--user',
              help="Jira User",
              required=True)
@click.option('--password',
              help="Jira Password",
              required=True)
@click.option('--instance', 'jira_instance',
              help="Jira instance url",
              default="https://rpc-openstack.atlassian.net")
def cli(user, password, jira_instance):
    click.get_current_context().obj = \
        JIRA(jira_instance, basic_auth=(user, password))


@cli.command()
@click.option('--summary',
              help='Issue summary',
              required=True)
@click.option('--description',
              help='Issue description/body',
              required=True)
@click.option('--project',
              help='Jira Project Key',
              required=True)
@click.option('--type', 'issue_type',
              help="Jira issue type",
              default="Task")
@click.option('--label',
              'labels',
              help="Add label to issue, can be specified multiple times",
              multiple=True,
              default=["jenkins-build-failure"])
@click.option("--existing-issue-query",
              help="JQL query for existing issues. If the query returns"
              " exactly one issue, a new issue will not be created, and"
              " the existing issue's key will be printed.")
def create_issue(summary, description, project, issue_type, labels,
                 existing_issue_query):
    ctx = click.get_current_context()
    authed_jira = ctx.obj
    issue = None
    if existing_issue_query is not None:
        try:
            issues = authed_jira.search_issues(existing_issue_query)
        except JIRAError as e:
            click.echo("Error querying for existing issues, bad JQL?"
                       " Query: {q}, Error: {e}"
                       .format(q=existing_issue_query, e=e))
            ctx.exit(1)
        if len(issues) == 1:
            issue = issues[0]
        elif len(issues) > 1:
            click.echo("query: {q} Returned >1 issues: {il}".format(
                q=existing_issue_query, il=issues))
            ctx.exit(1)

    if issue is None:
        issue = authed_jira.create_issue(
            project=project,
            summary=summary,
            description=description,
            issuetype={'name': issue_type},
            labels=labels
        )
    click.echo(issue.key)


if __name__ == "__main__":
    cli()
