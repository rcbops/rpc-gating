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
@click.option(
    '--org',
    help='Github Organisation that owns the target repo',
    required=True,
)
@click.option(
    '--repo',
    help='Name of target repo',
    required=True,
)
@click.option(
    '--pat',
    help="Github Personal Access Token",
    required=True,
)
@click.option(
    '--pull-request-number',
    help="Pull request to update",
    required=True,
)
@click.option(
    '--issue-key',
    help='Issue being resolved by pull request',
    required=True,
)
def add_issue_url_to_pr(org, repo, pat, pull_request_number, issue_key):
    jira_url = "https://rpc-openstack.atlassian.net/browse/"
    gh = github3.login(token=pat)
    repo = gh.repository(org, repo)
    pull_request = repo.pull_request(pull_request_number)
    current_body = pull_request.body or ""

    issue_text = "Issue: [{key}]({url}{key})".format(
        url=jira_url,
        key=issue_key,
    )

    if issue_text in current_body:
        click.echo(
            "Pull request not updated, it already includes issue reference."
        )
    else:
        if current_body:
            updated_body = "{body}\n\n{issue}".format(
                body=current_body,
                issue=issue_text,
            )
        else:
            updated_body = issue_text

        success = pull_request.update(body=updated_body)
        if success:
            click.echo("Pull request updated with issue reference.")
        else:
            raise Exception("There was a failure updating the pull request.")


@click.group()
def cli():
    pass


cli.add_command(create_issue)
cli.add_command(add_issue_url_to_pr)

if __name__ == "__main__":
    cli()
