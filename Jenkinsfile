properties([])
node(){
  sh "Echo doing some bad stuff in an non-org member pr"
  sh "and do something elseee"
  deleteDir()
  stage("Prepare"){
    checkout scm
    lint_container = docker.build env.BUILD_TAG.toLowerCase()
  }
  lint_container.inside {
    stage("Checkout"){
      checkout scm
    }
    stage("Lint"){
      withEnv([
        'RPC_GATING_LINT_USE_VENV=no'
      ]){
        sh "./lint.sh 2>&1"
      }// withenv
    }// stage
    //if this is a branch build for master
    // (not a pr), run the JJB job to apply the changes.
    stage("Apply"){
      if(env.BRANCH_NAME=="master"){
        build job: "Jenkins-Job-Builder"
      }
    }
  }// inside
}// node
