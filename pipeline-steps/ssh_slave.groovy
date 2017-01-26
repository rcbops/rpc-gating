/* Connect a slave to the jenkins master
 * Params: None
 * Environment:
 *  - WORKSPACE
 * Files:
 *  - playbooks/inventory/hosts
 */
def connect(){
  withCredentials([
    file(
      credentialsId: 'id_rsa_cloud10_jenkins_file',
      variable: 'JENKINS_SSH_PRIVKEY'
    ),
    usernamePassword(
      credentialsId: "service_account_jenkins_api_creds",
      usernameVariable: "JENKINS_USERNAME",
      passwordVariable: "JENKINS_API_KEY"
    )
  ]){
    dir("rpc-gating/playbooks"){
      common.venvPlaybook(
        playbooks: ["setup-jenkins-slave.yml"],
        venv: ".venv",
        args: [
          "-i inventory",
          "--limit job_nodes",
          "--private-key=\"${env.JENKINS_SSH_PRIVKEY}\""
        ]
      )
    } //dir
  } //withCredentials
} //call

/* Disconnect slave
 * Reads global var: instance_name
 */
def destroy(){
  withCredentials([
    usernamePassword(
      credentialsId: "service_account_jenkins_api_creds",
      usernameVariable: "JENKINS_USERNAME",
      passwordVariable: "JENKINS_API_KEY"
    )
  ]){
    dir("rpc-gating/scripts"){
      sh """
        . ../playbooks/.venv/bin/activate
        pip install jenkinsapi
        python jenkins_node.py \
          delete --name "${instance_name}"
      """
    } //dir
  } //withCredentials
} //call

return this
