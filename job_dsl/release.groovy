library "rpc-gating@${RPC_GATING_BRANCH}"
common.shared_slave(){
  stage("Configure Git"){
    common.configure_git()
  }
  stage("Release"){
    withCredentials([
      string(
        credentialsId: 'rpc-jenkins-svc-github-pat',
        variable: 'PAT'
      ),
      string(
        credentialsId: 'mailgun_hughsaunders_endpoint',
        variable: 'MAILGUN_ENDPOINT'
      ),
      string(
        credentialsId: 'mailgun_hughsaunders_api_key',
        variable: 'MAILGUN_API_KEY'
      )
    ]){
      sshagent (credentials:['rpc-jenkins-svc-github-ssh-key']){
        sh """#!/bin/bash -xe
          set +x; . .venv/bin/activate; set -x
          ${env.COMMAND}
        """
      }
    }
  }
}
