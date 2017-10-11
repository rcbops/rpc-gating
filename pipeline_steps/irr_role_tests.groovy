def run_irr_tests() {
  pubcloud.runonpubcloud {
    currentBuild.result="SUCCESS"
    env.RE_HOOK_ARTIFACT_DIR="${WORKSPACE}/artifacts"
    env.RE_HOOK_RESULT_DIR="${WORKSPACE}/results"
    try {
      ansiColor('xterm') {
        dir("${env.WORKSPACE}/${env.ghprbGhRepository}") {
          stage('Checkout'){
            print("Triggered by PR: ${env.ghprbPullLink}")
            common.clone_with_pr_refs()
          }
          stage('Execute ./run_tests.sh'){
            withCredentials(common.get_cloud_creds()) {
              sh """#!/bin/bash
              mkdir -p "${RE_HOOK_RESULT_DIR}"
              mkdir -p "${RE_HOOK_ARTIFACT_DIR}"
              bash ./run_tests.sh
              """
            }
          }
        }
      }
    } catch (e) {
      print(e)
      currentBuild.result="FAILURE"
      throw e
    } finally {
      common.safe_jira_comment("${currentBuild.result}: [${env.BUILD_TAG}|${env.BUILD_URL}]")
      common.archive_artifacts()
    }
  } // pubcloud slave
}

return this;
