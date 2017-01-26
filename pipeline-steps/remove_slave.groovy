/* Disconnect slave
 * Params: None
 * Files Required:
 *  - Ansible static inventory: playbooks/inventory/hosts
 */
def call(){
  common = load './rpc-gating/pipeline-steps/common.groovy'
  withCredentials([
    usernamePassword(
      credentialsId: "service_account_jenkins_api_creds",
      usernameVariable: "JENKINS_USERNAME",
      passwordVariable: "JENKINS_API_KEY"
    )
  ]){
    dir("rpc-gating/playbooks"){
      common.venvPlaybook(
        playbooks: ["remove-jenkins-slave.yml"],
        venv: ".venv",
        args: [ "-i inventory" ]
      )
    } //dir
  } //withCredentials
} //call

return this
