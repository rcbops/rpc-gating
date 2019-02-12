// Can't use env.FOO = {FOO} to transfer JJB vars to groovy
// as this file won't be templated by JJB.
// Alternative is to use parameters with JJB vars as the defaults.
library "rpc-gating-master"
common.globalWraps(){

  // We do not want to trigger any of these
  // for rpc-gating forks or branches other
  // than 'master'. So if this is triggered
  // by anything else, end now.
  // TODO(odyssey4me):
  // Remove once RE-1645 is resolved.
  if (env.RPC_GATING_BRANCH != "master") {
    print "Triggered by branch ${RPC_GATING_BRANCH} instead of master. Skipping triggers."
    currentBuild.result = 'SUCCESS'
    return
  }

  // Here we define the jobs to execute when a merge happens.
  // Some are executed in series, and some parallel. We wait
  // until they are all complete before completing the pipeline.

  List serialJobs = [
    'Build-Gating-Venv',
    'Jenkins-Job-Builder'
  ]

  List parallelJobs = [
    'Build-Docker-Images-For-Master',
    'Setup-Grafana',
    'Setup-Graphite',
    'Setup-Nodepool',
    'Setup-StatsD'
  ]

  List jobParameters = [
    [
      $class: 'StringParameterValue',
      name: 'RPC_GATING_BRANCH',
      value: env.RPC_GATING_BRANCH
    ]
  ]

  for (job in serialJobs) {
    stage(job) {
      build(
        job: job,
        wait: true,
        parameters: jobParameters
      ) // build
    } // stage
  } // for

  def parallelBuilds = [:]

  // Cannot do for (job in jobNames), see:
  // https://jenkins.io/doc/pipeline/examples/#parallel-multiple-nodes
  for (j in parallelJobs) {
    def job = j
    parallelBuilds[job] = {
      build(
        job: job,
        wait: true,
        parameters: jobParameters
      ) // build
    } // parallelBuilds
  } // for

  parallel parallelBuilds

} // globalWraps
