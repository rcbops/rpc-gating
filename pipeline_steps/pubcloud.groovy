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
    withCredentials(common.get_cloud_creds()){
      dir("rpc-gating/playbooks"){
        pyrax_cfg = common.writePyraxCfg(
          username: env.PUBCLOUD_USERNAME,
          api_key: env.PUBCLOUD_API_KEY
        )
        withEnv(["RAX_CREDS_FILE=${pyrax_cfg}"]){
          common.venvPlaybook(
            playbooks: ["allocate_pubcloud.yml",
                        "drop_ssh_auth_keys.yml"],
            args: [
              "-i inventory",
              "--private-key=\"${env.JENKINS_SSH_PRIVKEY}\""
            ],
            vars: args
          )
        }
        stash (
          name: "pubcloud_inventory",
          include: "inventory/hosts"
        )
      }
    }
  }
}


/* Remove public cloud instances
 */
def cleanup(Map args){
  withEnv(['ANSIBLE_FORCE_COLOR=true']){
    withCredentials(common.get_cloud_creds()){
      dir("rpc-gating/playbooks"){
        pyrax_cfg = common.writePyraxCfg(
          username: env.PUBCLOUD_USERNAME,
          api_key: env.PUBCLOUD_API_KEY
        )
        withEnv(["RAX_CREDS_FILE=${pyrax_cfg}"]){
          common.venvPlaybook(
            playbooks: ['cleanup_pubcloud.yml'],
            args: [
              "--private-key=\"${env.JENKINS_SSH_PRIVKEY}\"",
            ],
            vars: [
              "instance_name": args.instance_name,
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
  common.conditionalStep(
    step_name: 'Allocate Resources',
    step: {
      create (
        name: args.instance_name,
        count: 1,
        region: env.REGION,
        flavor: env.FLAVOR,
        image: env.IMAGE,
        keyname: "jenkins",
      )
    } //step
  ) //conditionalsteps
  ssh_slave.connect()
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
      cleanup (
        instance_name: args.instance_name,
        server_name:  args.instance_name,
        region: env.REGION,
      )
    } //step
  ) //conditionalstep
  ssh_slave.destroy(args.instance_name)
}

/* One func entrypoint to run a script on a single use slave */
def runonpubcloud(body){
  instance_name = common.gen_instance_name()
  try{
    getPubCloudSlave(instance_name: instance_name)
    common.use_node(instance_name){
      body()
    }
  }catch (e){
    print(e)
    throw e
  }finally {
    delPubCloudSlave(instance_name: instance_name)
  }
}

def uploadToCloudFiles(Map args){
  withCredentials(common.get_cloud_creds()) {
    dir("rpc-gating/playbooks") {
      pyrax_cfg = common.writePyraxCfg(
        username: env.PUBCLOUD_USERNAME,
        api_key: env.PUBCLOUD_API_KEY
      )
      withEnv(["RAX_CREDS_FILE=${pyrax_cfg}"]) {
        common.venvPlaybook(
          playbooks: ["upload_to_cloud_files.yml"],
          vars: [
            container: args.container,
            src: args.src,
            html_report_dest: args.html_report_dest,
            description_file: args.description_file
          ]
        ) // venvPlaybook
      } // withEnv
    } // dir
  } // withCredentials
}

return this
