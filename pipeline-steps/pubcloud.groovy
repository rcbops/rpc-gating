/* Create public cloud node
 * Params:
 *  - region: Rax region to build in
 *  - name: Name of instance to build
 *  - count: Number of instances to build
 *  - flavor: Flavor to build
 *  - image: Image to build from
 *  - keyname: Name of existing nova keypair
 * Environment Variables:
 *  - WORKSPACE
 */
def create(Map args){
    withEnv(["RAX_REGION=${args.region}"]){
      withCredentials([
        file(
          credentialsId: 'RPCJENKINS_RAXRC',
          variable: 'RAX_CREDS_FILE'
        ),
        file(
          credentialsId: 'id_rsa_cloud10_jenkins_file',
          variable: 'JENKINS_SSH_PRIVKEY'
        )
      ]){
        dir("rpc-gating/playbooks"){
          common.install_ansible()
          common.venvPlaybook(
            playbooks: ["allocate_pubcloud.yml",
                        "drop_ssh_auth_keys.yml"],
            venv: ".venv",
            args: [
              "-i inventory",
              "--private-key=\"${env.JENKINS_SSH_PRIVKEY}\""
            ],
            vars: args
          )
          } // directory
        } //withCredentials
      } // withEnv
} //call


/* Remove public cloud instances
 */
def cleanup(){
    withEnv(['ANSIBLE_FORCE_COLOR=true']){
      withCredentials([
        file(
          credentialsId: 'RPCJENKINS_RAXRC',
          variable: 'RAX_CREDS_FILE'
        ),
        file(
          credentialsId: 'id_rsa_cloud10_jenkins_file',
          variable: 'JENKINS_SSH_PRIVKEY'
        )
      ]){
        dir("rpc-gating/playbooks"){
          common.venvPlaybook(
            playbooks: ['cleanup_pubcloud.yml'],
            venv: ".venv",
            args: [
              "--private-key=\"${env.JENKINS_SSH_PRIVKEY}\"",
            ],
            vars: ["instance_name": instance_name]
          )
          } // directory
        } //withCredentials
      } // withEnv
} //call


def getPubCloudSlave(Map args){
  ssh_slave = load 'rpc-gating/pipeline-steps/ssh_slave.groovy'
  common.conditionalStage(
    stage_name: 'Allocate Resources',
    stage: {
      create (
        name: args.instance_name,
        count: 1,
        region: env.REGION,
        flavor: env.FLAVOR,
        image: env.IMAGE,
        keyname: "jenkins",
      )
    } //stage
  ) //conditionalStages
  common.conditionalStage(
    stage_name: "Connect Slave",
    stage: {
      ssh_slave.connect()
  })
}
def delPubCloudSlave(Map args){
  ssh_slave = load 'rpc-gating/pipeline-steps/ssh_slave.groovy'
  common.conditionalStep(
    step_name: "Pause",
    step: {
      input message: "Continue?"
    }
  )
  common.conditionalStep(
    step_name: 'Cleanup',
    step: {
      ssh_slave.destroy()
      cleanup()
    } //stage
  ) //conditionalStage
}

return this
