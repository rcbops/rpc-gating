
def create_issue(
    String tag,
    String link,
    String label="jenkins-build-failure",
    String org="rcbops",
    String repo="u-suk-dev"){
  withCredentials([
    string(
      credentialsId: 'rpc-jenkins-svc-github-pat',
      variable: 'pat'
    )
  ]){
    sh """#!/bin/bash -xe
      cd ${env.WORKSPACE}
      set +x; . .venv/bin/activate; set -x
      python rpc-gating/scripts/ghutils.py\
        --org '$org'\
        --repo '$repo'\
        --pat '$pat'\
        create_issue\
        --tag '$tag'\
        --link '$link'\
        --label '$label'
    """
  }
}

/**
 * Add issue link to pull request description
 *
 * Pull request commit messages include the issue key for an issue on Jira.
 * Update the description of the current GitHub pull request with a link to
 * the Jira issue.
 */
void add_issue_url_to_pr(){
  List org_repo = env.ghprbGhRepository.split("/")
  String org = org_repo[0]
  String repo = org_repo[1]
  Integer pull_request_number = env.ghprbPullId as Integer

  // clone the target repo with all github PR refs
  dir(repo) {
    common.clone_with_pr_refs()
  }

  // try to derive the issue number from the contents
  // of the comparison between the target branch and
  // the PR
  String issue_key = common.get_jira_issue_key(repo)

  withCredentials([
    string(
      credentialsId: 'rpc-jenkins-svc-github-pat',
      variable: 'pat'
    )
  ]){
    sh """#!/bin/bash -xe
      cd $env.WORKSPACE
      set +x; . .venv/bin/activate; set -x
      python rpc-gating/scripts/ghutils.py\
        --org '$org'\
        --repo '$repo'\
        --pat '$pat'\
        add_issue_url_to_pr\
        --pull-request-number '$pull_request_number'\
        --issue-key '$issue_key'
    """
  }
  return null
}


return this;
