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
      string(
        credentialsId: "dev_pubcloud_username",
        variable: "PUBCLOUD_USERNAME"
      ),
      string(
        credentialsId: "dev_pubcloud_api_key",
        variable: "PUBCLOUD_API_KEY"
      ),
      file(
        credentialsId: 'id_rsa_cloud10_jenkins_file',
        variable: 'JENKINS_SSH_PRIVKEY'
      )
    ]){
      dir("rpc-gating"){
        git branch: env.RPC_GATING_BRANCH, url: env.RPC_GATING_REPO
      }
      dir("rpc-gating/playbooks"){
        common.install_ansible()
        pyrax_cfg = common.writePyraxCfg(
          username: env.PUBCLOUD_USERNAME,
          api_key: env.PUBCLOUD_API_KEY
        )
        withEnv(["RAX_CREDS_FILE=${pyrax_cfg}"]){
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
        } // withEnv
      } // directory
    } //withCredentials
  } // withEnv
} //call


/* Remove public cloud instances
 */
def cleanup(){
  withEnv(['ANSIBLE_FORCE_COLOR=true']){
    withCredentials([
      string(
        credentialsId: "dev_pubcloud_username",
        variable: "PUBCLOUD_USERNAME"
      ),
      string(
        credentialsId: "dev_pubcloud_api_key",
        variable: "PUBCLOUD_API_KEY"
      ),
      file(
        credentialsId: 'id_rsa_cloud10_jenkins_file',
        variable: 'JENKINS_SSH_PRIVKEY'
      )
    ]){
      dir("rpc-gating/playbooks"){
        pyrax_cfg = common.writePyraxCfg(
          username: env.PUBCLOUD_USERNAME,
          api_key: env.PUBCLOUD_API_KEY
        )
        withEnv(["RAX_CREDS_FILE=${pyrax_cfg}"]){
          common.venvPlaybook(
            playbooks: ['cleanup_pubcloud.yml'],
            venv: ".venv",
            args: [
              "--private-key=\"${env.JENKINS_SSH_PRIVKEY}\"",
            ],
            vars: ["instance_name": instance_name]
          )
        } // withEnv
      } // directory
    } //withCredentials
  } // withEnv
} //call


def getPubCloudSlave(Map args){
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

/* One func entrypoint to run a script on a single use slave */
def runonpubcloud(Map args){
  instance_name = common.gen_instance_name()
  getPubCloudSlave(instance_name: instance_name)
  try{
    node(instance_name){
      args.step()
    }
  }catch (e){
    print(e)
    throw e
  }finally {
    delPubCloudSlave()
  }
}

return this
