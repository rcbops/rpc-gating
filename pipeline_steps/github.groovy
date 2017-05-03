
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
      python rpc-gating/scripts/ghutils.py \
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

return this;
