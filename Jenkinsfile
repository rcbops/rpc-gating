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
      sh "sudo ./lint.sh 2>&1"
    }
  }
}
