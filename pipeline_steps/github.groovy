
def create_issue(
    tag,
    link,
    label="jenkins-build-failure",
    org="rcbops",
    repo="u-suk-dev"){
  withCredentials([
    string(
      credentialsId: 'rpc-jenkins-svc-github-pat',
      variable: 'pat'
    )
  ]){
    sh """#!/bin/bash -xe
      cd ${env.WORKSPACE}
      . .venv/bin/activate
      python rpc-gating/scripts/ghutils.py create_issue\
        --tag '$tag'\
        --link '$link'\
        --org '$org'\
        --repo '$repo'\
        --pat '$pat'\
        --label '$label'
    """
  }
}

def create_pull_request(
    body,
    change_branch,
    base_branch="master",
    title="[master] Update OSA SHA",
    org="rcbops",
    repo="rpc-openstack"){
  withCredentials([
    string(
      credentialsId: 'rpc-jenkins-svc-github-pat',
      variable: 'pat'
    )
  ]){
    sh """#!/bin/bash -xe
      cd ${env.WORKSPACE}
      . .venv/bin/activate
      python rpc-gating/scripts/ghutils.py create_pull_request\
        --org '$org'\
        --repo '$repo'\
        --pat '$pat'\
        --body '$body'\
        --title '$title'\
        --branch '$base_branch'\
        --head '$change_branch'
    """
  }
}

return this;
