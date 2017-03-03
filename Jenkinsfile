properties([])
node(){
  sh "Echo doing some bad stuff in an non-org member pr"
  deleteDir()
  stage("Prepare"){
    checkout scm
    lint_container = docker.build 'lint'
  }
  lint_container.inside {
    stage("checkout"){
      checkout scm
    }
    stage("lint"){
      sh "./lint.sh 2>&1"
    }
  }
}
