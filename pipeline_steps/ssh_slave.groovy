/* Connect a slave to the jenkins master
 * Params: None
 * Environment:
 *  - WORKSPACE
 * Files:
 *  - playbooks/inventory/hosts
 */
def connect(){
  common.conditionalStage(
    stage_name: "Connect Slave",
    stage: {
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
            playbooks: ["setup_jenkins_slave.yml"],
            args: [
              "-i inventory",
              "--limit job_nodes",
              "--private-key=\"${env.JENKINS_SSH_PRIVKEY}\""
            ]
          )
        } //dir
      } //withCredentials
  }) //conditionalStage
} //call

/* Disconnect slave
 * Reads global var: instance_name
 */
def destroy(){
  common.conditionalStep(
    step_name: 'Destroy Slave',
    step: {
      withCredentials([
        usernamePassword(
          credentialsId: "service_account_jenkins_api_creds",
          usernameVariable: "JENKINS_USERNAME",
          passwordVariable: "JENKINS_API_KEY"
        )
      ]){
        dir("rpc-gating/scripts"){
          sh """
            . ${env.WORKSPACE}/.venv/bin/activate
            pip install 'pip==9.0.1'
            pip install -c ../constraints.txt jenkinsapi
            python jenkins_node.py \
              delete --name "${instance_name}"
          """
        } //dir
      } //withCredentials
    } //stage
  ) //conditionalStage
} //call

return this
