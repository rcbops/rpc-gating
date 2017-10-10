library "rpc-gating@${RPC_GATING_BRANCH}"
common.shared_slave(){
  stage('Run Jenkins Job Builder') {
    def scmVars = checkout scm
    if (scmVars.GIT_BRANCH == "master" || scmVars.GIT_BRANCH == null){
      build job: "Jenkins-Job-Builder"
    } else {
      print ("Not running JJB as job was triggered by a push to branch ${env.GIT_BRANCH}, and not master")
    }
  } // stage
} // node
