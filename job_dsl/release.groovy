library "rpc-gating-master"
common.globalWraps(){
  common.standard_job_slave(env.SLAVE_TYPE) {
    stage("Configure Git"){
      common.configure_git()
    }
    stage("Verify component metadata"){
      // This stage is used by component pre-merge release tests only
      if ( env.ghprbPullId != null ) {
        common.clone_with_pr_refs(
          "${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}",
        )
        venv = "${WORKSPACE}/.componentvenv"
        sh """#!/bin/bash -xe
            virtualenv --python python3 ${venv}
            set +x; . ${venv}/bin/activate; set -x
            pip install -c '${env.WORKSPACE}/rpc-gating/constraints_rpc_component.txt' rpc_component
        """
        component_text = sh(
          script: """#!/bin/bash -xe
            set +x; . ${venv}/bin/activate; set -x
            cd "${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}"
            component metadata get
          """,
          returnStdout: true
        )
        println "=== component CLI standard out ==="
        println component_text
      }
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
  } // standard_job_slave
} // globalWraps
