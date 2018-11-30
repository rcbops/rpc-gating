// Can't use env.FOO = {FOO} to transfer JJB vars to groovy
// as this file won't be templated by JJB.
// Alternative is to use parameters with JJB vars as the defaults.
library "rpc-gating-master"
common.globalWraps(){
  image_list = [
    "nodepool-rpco-14.2-xenial-base",
    "nodepool-rpco-14.2-trusty-base"
  ]

  try {
    currentBuild.result = "SUCCESS"
    // We need to checkout the rpc-openstack repo on the CIT Slave
    // so that we can check whether the patch is a docs-only patch
    // before allocating resources unnecessarily.
    common.prepareRpcGit("auto", env.WORKSPACE)
    if(common.is_doc_update_pr("${env.WORKSPACE}/rpc-openstack")){
      return
    }

    // Python artifacts can be built in parallel for all
    // distributions (images) supported by a series.
    python_parallel = [:]
    for (x in image_list) {
      // Need to bind the image variable before the closure - can't do 'for (image in ...)'
      // https://jenkins.io/doc/pipeline/examples/#parallel-multiple-nodes
      def image = x
      python_parallel[image] = {
        artifact_build.python(image)
      }
    }

    // We can run all the artifact build processes in parallel
    // for PR's because they do not upload anything.
    if ( env.ghprbPullId != null ) {
      Map branches = python_parallel + [
        "apt": {
          // We do not need to download existing data from
          // rpc-repo for PR's as they do not upload their
          // changes. We only need to do that when making
          // changes to the data. Skipping the data sync
          // for PR's saves a ton of time.
          env.PULL_FROM_MIRROR = "NO"
          artifact_build.apt()
        },
        "git": {
          // We only care about building these on one of the
          // images because the artifacts produced are not
          // distro-specific. We take the first one for
          // convenience.
          artifact_build.git(image_list[0])
        }
      ]
      parallel branches
    } else {
      // For periodic jobs, we want the results to be pushed
      // up to the mirror.
      env.PUSH_TO_MIRROR = "YES"

      // When the job is periodic, the jobs must run in a
      // particular sequence in order to ensure that each
      // artifact set is built and uploaded so that it can
      // be used in the step that follows.

      // The apt artifacts are built on a long-lived slave
      // with a single executor, so no locking is required.
      stage('Apt'){
        artifact_build.apt()
      }

      // We lock the git job per series to ensure that no
      // more than one job for each series executes at the
      // same time.
      // We use the first available image for the series
      // as the artifacts are not distribution-specific.
      stage('Git'){
        lock("artifact_git_newton"){
          artifact_build.git(image_list[0])
        }
      }

      // We lock the python job per series to ensure that no
      // more than one job for each series executes at the
      // same time.
      stage('Python'){
        lock("artifact_python_newton"){
          parallel python_parallel
        }
      }

    } //if
  } catch (e) {
    print e
    currentBuild.result = "FAILURE"
    throw e
  }
} // globalWraps
