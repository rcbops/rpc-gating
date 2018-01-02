// Can't use env.FOO = {FOO} to transfer JJB vars to groovy
// as this file won't be templated by JJB.
// Alternative is to use parameters with JJB vars as the defaults.
library "rpc-gating@${RPC_GATING_BRANCH}"
common.globalWraps(){
  try {
    stage("Configure Git"){
      common.configure_git()
    }

    stage("Checkout"){
      if ( env.ghprbPullId != null ) {
        repo_url = "https://github.com/${env.ghprbGhRepository}.git"
        repo_branch = "origin/pr/${env.ghprbPullId}/merge"
        print("Triggered by PR: ${env.ghprbPullLink}")
      } else {
        repo_url = env.URL
        repo_branch = env.BRANCH
      }
      print("Repo: ${repo_url} Branch: ${repo_branch}")
      common.clone_with_pr_refs("repo", repo_url, repo_branch)
    } // stage

    stage("Dependency Update"){
      // To prevent multiple periodics acting on the same
      // repository at the same time, we implement a lock
      // based on the repository name and the branch.
      // For PR's instead of the branch we use the ghprbPullId
      // in order to prevent the lock name including /
      // characters.
      String repo_name = repo_url.split('/')[-1]
      if ( env.ghprbPullId != null ) {
        lock_branch = env.ghprbPullId
      } else {
        lock_branch = repo_branch
      }
      lock("dep_update_${repo_name}_${lock_branch}") {
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
          } // sshagent
        } // withCredentials
      } // lock
    } // stage
  } catch(e) {
    print(e)
    // Only create failure card when run as a post-merge job
    if ( env.ghprbPullId == null ) {
      common.create_jira_issue("RE",
                               env.BUILD_TAG,
                               env.BUILD_URL,
                               "Task")
    } // if
    throw e
  } // try
} // globalWraps
