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
      sh "sudo chown -R jenkins:jenkins .; ./lint.sh 2>&1"
    }
  }
}
