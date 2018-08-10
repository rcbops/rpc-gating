library "rpc-gating-master"
common.globalWraps(){
  stage("Configure Git"){
    common.configure_git()
  }

  stage("Release"){
    // If this is a PR test, then we need to set some
    // of the environment variables automatically as
    // they will not be provided by a human.
    if ( env.ghprbPullId != null ) {
      List source_repo = env.ghprbGhRepository.split("/")
      env.ORG = source_repo[0]
      env.REPO = source_repo[1]
      env.RC_BRANCH = "pr/${env.ghprbPullId}/merge"
    }
    withCredentials([
      string(
        credentialsId: 'rpc-jenkins-svc-github-pat',
        variable: 'PAT'
      ),
      string(
        credentialsId: 'mailgun_mattt_endpoint',
        variable: 'MAILGUN_ENDPOINT'
      ),
      string(
        credentialsId: 'mailgun_mattt_api_key',
        variable: 'MAILGUN_API_KEY'
      ),
      usernamePassword(
        credentialsId: "jira_user_pass",
        usernameVariable: "JIRA_USER",
        passwordVariable: "JIRA_PASS"
      )
    ]){
      sshagent (credentials:['rpc-jenkins-svc-github-ssh-key']){
        sh """#!/bin/bash -xe
          set +x; . .venv/bin/activate; set -x
          ${env.COMMAND}
        """
      } // sshagent
    } // withCredentials
  } // stage
} // globalWraps
