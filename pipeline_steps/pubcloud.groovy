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
            vars: args
          )
        } // withEnv
      } // directory
    } //withCredentials
  } // withEnv
} //call


/* Create public cloud node
 * Params:
 *  - region: Rax region to build in
 *  - instance_name: Name of instance to build
 *  - flavor: Flavor to build
 *  - image: Image to build from
 * Environment Variables:
 *  - WORKSPACE
 * The args required can be supplied uppercase in the env dictionary, or lower
 * case as direct arguments.
 */
def getPubCloudSlave(Map args){
  common.conditionalStep(
    step_name: 'Allocate Resources',
    step: {
      add_instance_env_params_to_args(args)
      env.RAX_REGION = args.region
      withCredentials(common.get_cloud_creds()){
        dir("rpc-gating/playbooks"){
          pyrax_cfg = common.writePyraxCfg(
            username: env.PUBCLOUD_USERNAME,
            api_key: env.PUBCLOUD_API_KEY
          )
          env.RAX_CREDS_FILE = pyrax_cfg
          common.venvPlaybook(
            playbooks: ["allocate_pubcloud.yml",
                        "drop_ssh_auth_keys.yml"],
            args: [
              "-i inventory",
              "--private-key=\"${env.JENKINS_SSH_PRIVKEY}\""
            ],
            vars: args
          )
          stash (
            name: "pubcloud_inventory",
            include: "inventory/hosts"
          )
        }
      }
    }
  )
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
    }
  )
  ssh_slave.destroy(args.instance_name)
}

// if the instance params are set in the environment
// but not passed as args, add them to the args.
// nothing is returned as "args" is passed by ref.
// env vars are upper case while args are lower case
void add_instance_env_params_to_args(Map args){
  instance_params=[
    'flavor',
    'image',
    'region'
  ]
  for (p in instance_params){
    if (!(p in args)){
      P = p.toUpperCase()
      if (env[P] != null){
        args[p] = env[P]
        print ("${p} not supplied to runonpubcloud or getPubCloudSlave"
               + " using env var ${P}=${env[P]} instead.")
      } else {
        throw new Exception(
          "Missing required param for building an instance."
          + " Couldn't find value for ${p} in args or environment.")
      }
    }
  }
}

/* One func entrypoint to run a script on a single use slave.
The args required are shown in allocate_pubcloud.yml, they can
be supplied uppercase in the env dictionary, or lower case as
direct arguments. */
def runonpubcloud(Map args=[:], body){
  add_instance_env_params_to_args(args)
  instance_name = common.gen_instance_name()
  try{
    getPubCloudSlave(args + [instance_name: instance_name])
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
