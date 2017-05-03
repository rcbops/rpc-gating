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
@click.option('--label',
              help="Add label to issue, can be specified multiple times",
              multiple=True,
              required=True)
@click.pass_context
def create_issue(ctx, tag, link, label):
    ctx.obj['repo'].create_issue(
        title="JBF: {tag}".format(tag=tag),
        body="[link to failing build]({url})".format(url=link),
        labels=label
    )


@click.command()
@click.option('--name',
              help="Hook Name",
              required=True)
@click.option('--hooktargeturl',
              help="Destination for webhook",
              required=True)
@click.pass_context
def create_webhook(ctx, hooktargeturl, name):
    ctx.obj['repo'].create_hook(
        name=name,
        config={
            "url": hooktargeturl,
            "content_type": "json"
        }
    )


# The cli group takes common args and stores them in ctx.obj
@click.group()
@click.option('--org',
              help='Github Organisation that owns the target repo',
              default="rcbops")
@click.option('--repo',
              help='Name of target repo',
              required=True)
@click.option('--pat',
              help="Github Personal Access Token",
              required=True)
@click.pass_context
def cli(ctx, org, repo, pat):
    gh = github3.login(token=pat)
    repo = gh.repository(org, repo)
    ctx.obj['gh'] = gh
    ctx.obj['repo'] = repo


cli.add_command(create_issue)

if __name__ == "__main__":
    cli()
