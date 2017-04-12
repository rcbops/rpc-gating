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
def cleanup(Map args){
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
        variable: 'jenkins_ssh_privkey'
      )
    ]){
      dir("rpc-gating/playbooks"){
        common.install_ansible()
        pyrax_cfg = common.writePyraxCfg(
          username: env.PUBCLOUD_USERNAME,
          api_key: env.PUBCLOUD_API_KEY
        )
        withEnv(["RAX_CREDS_FILE=${pyrax_cfg}"]){
          common.venvPlaybook(
            playbooks: ['aio_maas_cleanup.yml',
                        'cleanup_pubcloud.yml'],
            venv: ".venv",
            args: [
              "--private-key=\"${env.JENKINS_SSH_PRIVKEY}\"",
            ],
            vars: [
              "instance_name": instance_name,
              "server_name": args.server_name,
              "region": args.region
            ]
          )
        } // withEnv
      } // directory
    } //withCredentials
  } // withEnv
} //call


def getPubCloudSlave(Map args){
  ssh_slave = load 'rpc-gating/pipeline_steps/ssh_slave.groovy'
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
  ssh_slave.connect()
}
def delPubCloudSlave(Map args){
  ssh_slave = load 'rpc-gating/pipeline_steps/ssh_slave.groovy'
  common.conditionalStep(
    step_name: "Pause",
    step: {
      input message: "Continue?"
    }
  )
  common.conditionalStep(
    step_name: 'Cleanup',
    step: {
      cleanup (
        instance_name: instance_name,
        server_name: instance_name,
        region: env.REGION,
      )
    } //stage
  ) //conditionalStage
  ssh_slave.destroy()
}

/* One func entrypoint to run a script on a single use slave */
def runonpubcloud(body){
  instance_name = common.gen_instance_name()
  getPubCloudSlave(instance_name: instance_name)
  try{
    node(instance_name){
      body()
    }
  }catch (e){
    print(e)
    throw e
  }finally {
    delPubCloudSlave(instance_name: instance_name)
  }
}

return this
