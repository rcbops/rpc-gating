library "rpc-gating@${RPC_GATING_BRANCH}"
common.globalWraps(){
  common.shared_slave(){

    stage("Configure Git"){
      common.configure_git()
    }

    stage("Release"){
      // If this is a PR test, then we need to set some
      // of the environment variables automatically as
      // they will not be provided by a human.
      if ( env.ghprbPullId != null ) {
        List source_repo = env.ghprbAuthorRepoGitUrl.split("/")
        env.ORG = source_repo[-2]
        env.REPO = source_repo[-1].tokenize(".")[0]
        env.RC_BRANCH = env.ghprbSourceBranch
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
  } // shared_slave
} // globalWraps
