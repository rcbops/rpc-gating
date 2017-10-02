def run_irr_tests() {
  pubcloud.runonpubcloud {
    currentBuild.result="SUCCESS"
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
      irr_archive_artifacts()
    }
  } // pubcloud slave
}

def irr_archive_artifacts(){
  stage('Compress and Publish Artefacts'){
    pubcloud.uploadToCloudFiles(
      container: "jenkins_logs",
    )
    publishHTML(
      allowMissing: true,
      alwaysLinkToLastBuild: true,
      keepAll: true,
      reportDir: 'artifacts_report',
      reportFiles: 'index.html',
      reportName: 'Build Artifact Links'
    )
  }
}

return this;
