// Can't use env.FOO = {FOO} to transfer JJB vars to groovy
// as this file won't be templated by JJB.
// Alternative is to use parameters with JJB vars as the defaults.
library "rpc-gating@${RPC_GATING_BRANCH}"
common.globalWraps(){
  common.standard_job_slave(env.SLAVE_TYPE) {
    try {

      stage("Configure Git"){
        common.configure_git()
      }

      stage("Clone repo to scan"){
        common.clone_with_pr_refs(
          "repo",
          repo_url,
          branch
        )
      }

      stage("Checkmarx Scan"){
        // Switch to scan repo dir to avoid sending the gating venv to checkmarx
        dir("repo"){
          checkmarx.scan(scan_type, repo_name, exclude_folders)
        } // dir
      } // stage

    } catch(e) {
      print(e)
      // Only create failure card when run as a post-merge job
      if (env.ghprbPullId == null && ! common.isAbortedBuild() && env.JIRA_PROJECT_KEY != '') {
        print("Creating build failure issue.")
        common.build_failure_issue(env.JIRA_PROJECT_KEY)
      } else {
        print("Skipping build failure issue creation.")
      } // if
      throw e
    } // try
  }
} // globalWraps
