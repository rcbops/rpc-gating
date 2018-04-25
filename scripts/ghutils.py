#!/usr/bin/env python

# This script contains github related utilties for Jenkins.


import json
import logging
import re

import click
import git
import github3

from notifications import try_context

logger = logging.getLogger("ghutils")


@click.group(chain=True)
@click.pass_context
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
    envvar="PAT",
    required=True,
)
@click.option(
    '--debug/--no-debug'
)
def cli(ctxt, org, repo, pat, debug):
    level = logging.WARNING
    if debug:
        level = logging.DEBUG
    logging.basicConfig(level=level)
    gh = github3.login(token=pat)
    repo_ = gh.repository(org, repo)
    if not repo_:
        raise ValueError("Failed to connect to repo {o}/{r}".format(
            o=org, r=repo
        ))
    repo_.org = gh.organization(org)
    ctxt.obj = repo_


@cli.command()
@click.pass_obj
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
def create_issue(repo, tag, link, label):
    repo.create_issue(
        title="JBF: {tag}".format(tag=tag),
        body="[link to failing build]({url})".format(url=link),
        labels=label
    )


@cli.command()
@click.pass_obj
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
def add_issue_url_to_pr(repo, pull_request_number, issue_key):
    jira_url = "https://rpc-openstack.atlassian.net/browse/"
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


def branch_api_request(repo, branch, method, postfix="", data=None):
    """Make Requests to the github branch protection api.

    Not supported by github3.py yet (6th September 2017)
    """
    url = "{branch_url}/protection{postfix}".format(
        branch_url=repo.branches_urlt.expand(branch=branch),
        postfix=postfix
    )
    # Branch protection api is in preview and requires a specific content type
    response = repo._session.request(
        method, url,
        headers={'Accept': 'application/vnd.github.loki-preview+json'},
        data=data)
    return response


@cli.command()
@click.pass_context
@click.option('--url-match', help="Print wehbooks whose URLs match this regex")
def get_webhooks(ctx, url_match):
    """List the webhooks for all repos in an org.

    A repo must be supplied, it will be used to determine the organisation.
    """
    if url_match:
        url_match_re = re.compile(url_match)
    org = ctx.obj.org
    for repo in org.iter_repos():
        for hook in repo.iter_hooks():
            for k, v in hook.config.items():
                if (url_match and url_match_re.search(v)) or not url_match:
                    print "{org}.{repo}.{name}.{k}: {v}".format(
                        org=org.login, repo=repo.name,
                        name=hook.name, k=k, v=v)


@cli.command()
@click.pass_context
@click.option(
    '--mainline',
    required=True,
    help="Mainline branch to cut from"
)
@click.option(
    '--rc',
    help="Release Candidate branch (re)create, may be omitted if "
         " --ref supplied to clone"
)
def update_rc_branch(ctx, mainline, rc):
    """Update rc branch.

    1. Store branch protection data
    2. Delete rc branch
    3. Create rc branch from head of mainline
    4. Enable branch protection with skeleton or previously stored settings.
    """
    repo = ctx.obj
    rc = try_context(repo, rc, "rc", "rc_ref")

    if mainline == rc:
        raise ValueError("Specifying the same branch for mainline and rc"
                         " will result in dataloss. The mainline branch"
                         " will be deleted, then the rc branch will be"
                         " created from the now non-existent mainline branch")

    branch_protection_enabled = False

    # check if branch exists
    if rc in (b.name for b in repo.iter_branches()):
        logger.debug("Branch {} exists".format(rc))
        # rc branch exists
        branch_protection_response = branch_api_request(repo, rc, 'GET')
        if branch_protection_response.status_code == 200:
            # rc branch exists and protection enabled
            logger.debug("Branch {branch} has protection enabled,"
                         " config: {bp_config}".format(
                             branch=rc,
                             bp_config=branch_protection_response.json()
                             ))
            branch_protection_enabled = True
            # disable branch protection
            r = branch_api_request(repo, rc, 'DELETE')
            r.raise_for_status()
            logger.debug("Branch protection disabled")
        elif branch_protection_response.status_code == 404:
            # rc branch exists without protection, so it doesn't need
            # to be disabled
            # TODO: create jira issue about unprotected branch?
            pass
        else:
            # failure retrieving branch protection status
            branch_protection_response.raise_for_status()

        # Delete branch
        r = repo._session.request(
            'DELETE',
            repo.git_refs_urlt.expand(sha="heads/{}".format(rc)))
        r.raise_for_status()
        logger.debug("Branch {} deleted".format(rc))

    mainline_sha = repo.branch(mainline).commit.sha
    logger.debug("Mainline SHA: {}".format(mainline_sha))

    # create rc branch pointing at head of mainline
    repo.create_ref("refs/heads/{}".format(rc), mainline_sha)
    logger.debug("Branch {} created".format(rc))

    # Skeleton branch protection data, used to protect a new branch.
    protection_data = {
        "required_status_checks": None,
        "enforce_admins": True,
        "required_pull_request_reviews": {
            "dismissal_restrictions": {},
            "dismiss_stale_reviews": False,
            "require_code_owner_reviews": False
        },
        "restrictions": None
    }

    # Incorporate previous branch protection data if the branch was
    # protected perviously
    if branch_protection_enabled:
        stored_bpd = branch_protection_response.json()
        protection_data.update(stored_bpd)
        # The github api returns enforce_admins as dict, but requires it to
        # be sent as a bool.
        protection_data['enforce_admins'] \
            = stored_bpd['enforce_admins']['enabled']

    # Enable branch protection
    r = branch_api_request(repo, rc, 'PUT',
                           data=json.dumps(protection_data))
    r.raise_for_status()
    logger.debug("Branch Protection enabled for branch {}".format(rc))

    # Ensure the rc branch was not updated to anything else while it was
    # unprotected. Stored mainline_sha is used incase mainline has
    # moved on since the SHA was acquired.
    assert mainline_sha == repo.branch(rc).commit.sha
    logger.debug("rc branch update complete")


@cli.command()
@click.pass_obj
@click.option(
    '--version',
    help="Symbolic name of Release (eg r14.1.99)"
)
@click.option(
    '--bodyfile',
    help="File containing release message body",
    required=True
    # Can't use type=click.File because the file may not exist on startup
)
def create_release(repo, version, bodyfile):
    ctx_obj = click.get_current_context().obj
    # Attempt to read release_notes from context
    # They may have been set by release.generate_release_notes
    try:
        with open(bodyfile, "r") as bodyfile_open:
            release_notes = bodyfile_open.read()
    except IOError as e:
        logger.error("Failed to open release notes file: {f} {e}".format(
            f=bodyfile, e=e
        ))
        click.get_current_context().exit(-1)

    version = try_context(ctx_obj, version, "version", "version")
    # Store version in context for use in notifications
    ctx_obj.version = version
    try:
        release = repo.create_release(
            tag_name=version,
            name=version,
            body=release_notes,
        )
        logger.info("Release {} created.".format(version))
    except github3.models.GitHubError as e:
        logger.error("Error creating release: {}".format(e))
        if e.code == 422:
            logger.error("Failed to create release, tag already exists?")
            raise SystemExit(5)
        if e.code == 404:
            logger.error("Failed to create release, Jenkins lacks repo perms?")
            raise SystemExit(6)
        else:
            raise e
    else:
        ctx_obj.release_url = release.html_url


@cli.command()
@click.option(
    "--url",
    help="URL of repo to clone. "
         " May be omitted if --org and --repo are supplied.")
@click.option("--ref", help="ref to checkout")
@click.option(
    "--refspec",
    help="refspec(s) to fetch, space separated.",
    default="+refs/heads/*:refs/remotes/origin/* "
            "+refs/tags/*:refs/tags/* "
            "+refs/heads/*:refs/heads/*")
def clone(url, ref, refspec):
    ctx_obj = click.get_current_context().obj
    url = try_context(ctx_obj, url, "url", "ssh_url")
    ctx_obj.rc_ref = ref
    clone_dir = "{o}/{r}".format(
        o=ctx_obj.owner.login,
        r=ctx_obj.name
    )
    ctx_obj.clone_dir = clone_dir
    logger.debug("Cloning {url}@{ref} to {dir}".format(
        url=url, ref=ref, dir=clone_dir))
    repo = git.Repo.init(clone_dir)
    try:
        origin = repo.remotes.origin
        origin.set_url(url)
    except Exception as e:
        origin = repo.create_remote('origin', url)
    repo.git.fetch(["-u", "-v", "-f", url] + refspec.split())
    try:
        getattr(origin.refs, ref).checkout()
    except AttributeError as e:
        logger.error("Ref {ref} not found in {url}".format(
            ref=ref,
            url=url
        ))
        raise e
    logger.debug("Clone complete, current ref: {sha}/{message}".format(
        sha=repo.head.commit.hexsha, message=repo.head.commit.message
    ))


@cli.command()
@click.pass_obj
@click.option(
    '--source-branch',
    help="Branch that contains the changes to propose",
    required=True
)
@click.option(
    '--target-branch',
    help="Branch that changes should be proposed to",
    required=True
)
@click.option(
    '--title',
    help="Title of the pull request",
    required=True
)
@click.option(
    '--body',
    help="Body of the pull request",
    required=True
)
@click.option(
    '--close-existing/--no-close-existing',
    help="If an existing PR is found for this combination of branches"
         " close it and create a new one.",
    default=False
)
def create_pr(repo, source_branch, target_branch, title, body, close_existing):

    # Check if PR already exists as github won't allow multiple PRs
    # with the same head/source and base/target branches.
    for p in repo.iter_pulls():
        if p.base.ref == target_branch and p.head.ref == source_branch:
            pr = p
            if close_existing:
                pr.close()
            else:
                break
    else:
        pr = repo.create_pull(title=title,
                              base=target_branch,
                              head=source_branch,
                              body=body)
    print "{org}/{repo}#{num}".format(org=repo.owner,
                                      repo=repo.name,
                                      num=pr.number)


@cli.command()
@click.pass_obj
@click.option(
    '--check-name',
    help="Name/context of the check to get the result for",
    required=True
)
@click.option(
    '--pull-id',
    help="ID of pull request to get check result for",
    required=True
)
def get_check_result(repo, check_name, pull_id):
    pr = repo.pull_request(pull_id)
    url = ("https://api.github.com/repos/{owner}/{repo}/commits/{ref}/statuses"
           .format(owner=repo.owner,
                   repo=repo.name,
                   ref=pr.head.sha))
    response = repo._session.request("GET", url)
    for status in response.json():
        if status['context'] == check_name:
            print status['state']
            break
    else:
        raise Exception("failed to find status {cn} for {repo}/pr-{pull_id}"
                        .format(cn=check_name, repo=repo, pull_id=pull_id))


@cli.command()
@click.pass_obj
@click.option(
    '--pull-id',
    help="ID of pull request to close",
    required=True
)
def close_pr(repo, pull_id):
    pr = repo.pull_request(pull_id)
    pr.close()


if __name__ == "__main__":
    cli()
