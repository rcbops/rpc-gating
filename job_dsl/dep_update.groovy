// Can't use env.FOO = {FOO} to transfer JJB vars to groovy
// as this file won't be templated by JJB.
// Alternative is to use parameters with JJB vars as the defaults.
library "rpc-gating@${RPC_GATING_BRANCH}"
common.shared_slave(){
  stage("Configure Git"){
    common.configure_git()
  }
  stage("Checkout"){
    dir("repo"){
      git branch: env.BRANCH, url: env.URL
    }
  }
  stage("dep_update"){
    withCredentials([
      string(
        credentialsId: 'rpc-jenkins-svc-github-pat',
        variable: 'PAT'
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
          rpc-gating/scripts/dep_update.sh
        """
      }
    }
  }
}
