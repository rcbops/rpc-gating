#!/usr/bin/env python

import json
import os
import re
import sys

import requests


def pr_api_url(pr_ui_url):
    """ Convert a github pull request UI URL into the correspnding API URL """
    match = re.match(
        "https://github.com/"
        "(?P<org>[^/]*)/(?P<repo>[^/]*)/pull/(?P<prnum>[\\d]*)",
        pr_ui_url)
    gd = match.groupdict()
    url = "https://api.github.com/repos/{org}/{repo}/pulls/{prnum}".format(
        org=gd["org"],
        repo=gd["repo"],
        prnum=gd["prnum"]
    )
    print("{pr_ui_url} --> {url}".format(pr_ui_url=pr_ui_url, url=url))
    return url


def add_token(url, pat):
    url = "{url}?access_token={pat}".format(url=url, pat=pat)
    print(url)
    return url


def set_commit_status(change_url, state, url, description, context, pat):
    """Set github status based on PR URL.
    Input:
        * PR UI URL
            This is translated into the corresponding API URL, which is used
            to retrieve information about the PR including the status url
        * Status info (state, url, description, context).
            This info is posted to the status_url to set a new status
    """
    # Get PR info
    response = requests.get(add_token(pr_api_url(change_url), pat))
    print("Get pr info response: ", response)
    pr_info = response.json()

    # Prepare request to create Status
    body = json.dumps({
        "state": state,
        "target_url": url,
        "description": description,
        "context": context
    })
    url = add_token(pr_info["statuses_url"], pat)
    print("Update status request, url: {url}, body: {body}".format(
        url=url,
        body=body))
    response = requests.post(url=url, data=body)
    print("update status response: ", response, response.content)


def main(args):
    child_job_name, child_job_number, state, context = args

    set_commit_status(
        change_url=os.environ["CHANGE_URL"],
        state=state,
        url="{jbase}/job/{job}/{bnum}".format(
            jbase=os.environ["JENKINS_URL"],
            job=child_job_name,
            bnum=child_job_number),
        description="Direct link to {context} build".format(
            context=context.split('/')[-1]),
        context=context,
        pat=os.environ["GITHUB_PAT"])


if __name__ == "__main__":
    main(sys.argv[1:])
