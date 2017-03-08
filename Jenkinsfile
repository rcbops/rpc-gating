properties([])
node(){
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
      withEnv([
        'RPC_GATING_LINT_USE_VENV=no'
      ]){
        sh "./lint.sh 2>&1"
      }// withenv
    }// stage
  }// inside
}// node
