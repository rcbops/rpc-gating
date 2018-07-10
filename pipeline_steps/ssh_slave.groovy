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
def _connect(Map args){
  common.conditionalStep(
    step_name: "Connect Slave",
    step: {
      if ("ip" in args){
        writeFile(
          file: "ssh_slave_inventory",
          text: """
[job_nodes]
${ip}
"""
          )
        args.inventory="ssh_slave_inventory"
      }
      if (!("inventory" in args)){
        args.inventory = "inventory"
      }
      if (!("port" in args)){
        args.port = 22
      }
      common.withRequestedCredentials("jenkins_ssh_privkey, jenkins_api_creds"){
        dir("rpc-gating/playbooks"){
          unstash args.inventory
          common.venvPlaybook(
            playbooks: ["setup_jenkins_slave.yml"],
            args: [
              "-i ${args.inventory}",
              "--limit job_nodes",
              "--extra-vars='ansible_port=${args.port}'",
              "--private-key=\"${env.JENKINS_SSH_PRIVKEY}\""
            ]
          ) //venvPlaybook
        } // dir
      } // withRequestedCredentials
  })
}

def connect(Map args){
  if (env.STAGES == null){
    env.STAGES="Connect Slave"
  }
  // scl is only available on centos slaves
  // all centos slaves are internal
  // :. scl avilable -> slave is internal
  scl = sh(returnStatus: true,
           script:"which scl")
  if (scl == 0){
    echo "Connect SSH Slave, already running on internal node, not allocating another."
    // on internal / centos slave already
    _connect(args)
  } else {
    // not on internal slave, so allocate node
    echo "Connect SSH Slave, not running on internal node, allocating one before proceeding."
    common.internal_slave(){
      _connect(args)
    }
  }
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
