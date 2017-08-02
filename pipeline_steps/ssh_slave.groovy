/*
  Anything that requires a connection to the jenkins api must run on an internal
  slave. These are all currently CentOS. We can't add a sensible label like
  internal as puppet removes them.

  To ensure that Jenkins API operations are always executed on an internal slave
  these functions allocate their own node block and run venv creation if
  necessary.
*/


/* Connect a slave to the jenkins master
 * Params: None
 * Environment:
 *  - WORKSPACE
 * Files:
 *  - playbooks/inventory/hosts
 */
def connect(port=22){
  common.conditionalStep(
    step_name: "Connect Slave",
    step: {
      common.internal_slave(){
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
            unstash "pubcloud_inventory"
            common.venvPlaybook(
              playbooks: ["setup_jenkins_slave.yml"],
              args: [
                "-i inventory",
                "--limit job_nodes",
                "--extra-vars='ansible_port=${port}'",
                "--private-key=\"${env.JENKINS_SSH_PRIVKEY}\""
              ]
            )
          }
        }
      }
  })
}

/* Disconnect slave
 * Reads global var: instance_name
 */
def destroy(slave_name){
  common.conditionalStep(
    step_name: 'Destroy Slave',
    step: {
      common.internal_slave(){
        withCredentials([
          usernamePassword(
            credentialsId: "service_account_jenkins_api_creds",
            usernameVariable: "JENKINS_USERNAME",
            passwordVariable: "JENKINS_API_KEY"
          )
        ]){
          dir("rpc-gating/scripts"){
            retry(5) {
              sh """
                set +x; . ${env.WORKSPACE}/.venv/bin/activate; set +x
                python jenkins_node.py \
                  delete --name "${slave_name}"
              """
            }
          }
        }
      }
    }
  )
}

return this
