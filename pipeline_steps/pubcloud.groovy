/* Remove public cloud instances
 */
def cleanup(Map args){
  withCredentials(common.get_cloud_creds()){
    dir("rpc-gating/playbooks"){
      if (!("inventory" in args)){
        args.inventory = "inventory"
      }
      unstash(args.inventory)
      pyrax_cfg = common.writePyraxCfg(
        username: env.PUBCLOUD_USERNAME,
        api_key: env.PUBCLOUD_API_KEY
      )
      withEnv(["RAX_CREDS_FILE=${pyrax_cfg}"]){
        common.venvPlaybook(
          playbooks: ['cleanup_pubcloud.yml'],
          args: [
            "-i ${args.inventory}",
            "--private-key=\"${env.JENKINS_SSH_PRIVKEY}\"",
          ],
          vars: args
        )
      } // withEnv
    } // directory
  } //withCredentials
} //call


/* Save image of public cloud instance
 * Params:
 *  - region: Rax region to build in
 *  - instance_name: Name of instance to save
 *  - image: Name to use for the saved image
 * Environment Variables:
 *  - WORKSPACE
 * The args required can be supplied uppercase in the env dictionary, or lower
 * case as direct arguments.
 *
 *  NOT Parallel safe unless inventory_path is supplied and unique per branch.
 *
 */
def savePubCloudSlave(Map args){
  common.conditionalStep(
    step_name: 'Save Slave',
    step: {
      add_instance_env_params_to_args(args)
      if (!("inventory" in args)){
        args.inventory = "inventory"
      }
      withCredentials(common.get_cloud_creds()){

        dir("rpc-gating/playbooks"){
          pyrax_cfg = common.writePyraxCfg(
            username: env.PUBCLOUD_USERNAME,
            api_key: env.PUBCLOUD_API_KEY
          )
          env.RAX_CREDS_FILE = pyrax_cfg
          env.SAVE_IMAGE_NAME = args.image
          common.venvPlaybook(
            playbooks: ['save_pubcloud.yml'],
            args: [
              "-i inventory",
              "--private-key=\"${env.JENKINS_SSH_PRIVKEY}\"",
            ],
            vars: args
          )
          stash (
            name: args.inventory,
            include: "${args.inventory}/hosts"
          )
        } // dir
      } //withCredentials
    } //step
  ) //conditionalStep
} //save


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
 *
 *  NOT Parallel safe unless inventory_path is supplied and unique per branch.
 *
 */
String getPubCloudSlave(Map args){
  common.conditionalStep(
    step_name: 'Allocate Resources',
    step: {
      add_instance_env_params_to_args(args)
      if (!("inventory" in args)){
        args.inventory = "inventory"
      }
      withCredentials(common.get_cloud_creds()){
        dir("rpc-gating/playbooks"){
          pyrax_cfg = common.writePyraxCfg(
            username: env.PUBCLOUD_USERNAME,
            api_key: env.PUBCLOUD_API_KEY
          )
          env.RAX_CREDS_FILE = pyrax_cfg
          common.venvPlaybook(
            playbooks: ["allocate_pubcloud.yml",
                        "instance_prep.yml",
                        "drop_ssh_auth_keys.yml"],
            args: [
              "-i inventory",
              "--private-key=\"${env.JENKINS_SSH_PRIVKEY}\""
            ],
            vars: args
          )
          stash (
            name: args.inventory,
            include: "${args.inventory}/hosts"
          )
        }
      }
    }
  )
  ssh_slave.connect(args)
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
      args.server_name = args.instance_name
      add_instance_env_params_to_args(args)
      cleanup (args)
    }
  )
  ssh_slave.destroy(args.instance_name)
}

// if the instance params are set in the environment
// but not passed as args, add them to the args.
// nothing is returned as "args" is passed by ref.
// env vars are upper case while args are lower case
void add_instance_env_params_to_args(Map args){
  List instance_params=[
    'flavor',
    'image',
    'regions',
    'fallback_regions',
    'instance_name'
  ]
  for (String p in instance_params){
    if (!(p in args)){
      String P = p.toUpperCase()
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
  // randomised inventory_path to avoid parallel conflicts
  if (env.WORKSPACE == null){
    throw new Exception("runonpubcloud must be run from within a node")
  }
  args.inventory="inventory.${common.rand_int_str()}"
  args.inventory_path="${WORKSPACE}/rpc-gating/playbooks/${args.inventory}"
  String instance_name = common.gen_instance_name()
  args.instance_name = instance_name
  try{
    getPubCloudSlave(args)
    common.use_node(instance_name){
      body()
    }
  }catch (e){
    print(e)
    throw e
  }finally {
    // Cleanup relies on the inventory file, if its not there don't try.
    // Periodic cleanup will remove the instance.
    // The inventory file may be missing if the job is aborted before or
    // during instance allocation, either manualy or via a PR update.
    if (fileExists("${args.inventory_path}/hosts")){
      try {
        delPubCloudSlave(args)
      // catch timeouts
      } catch (hudson.AbortException | org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e){
          // Ideally timeouts would be within the node block so that cleanup
          // cannot fail due to timeout. However that is more complicated
          // when the node block is reused in different situations (eg
          // runonpubcloud).
          // As we are planning to move to node pool, its not worth investing
          // the time to refactor that, so drop timeout exceptions instead
          // of creating useless jira issues.
          print "Caught timeout exception, dropping (${e})"
      // catch all other exceptions
      } catch (e){
        print "Error while cleaning up, swallowing this exception to prevent "\
              +"cleanup errors from failing the build: ${e}"
        common.create_jira_issue("RE", "Cleanup Failure: ${env.BUILD_TAG}")
      } // inner try
    } // if
  } //outer try
}

def uploadToCloudFiles(Map args){
  if(fileExists("${WORKSPACE}/artifacts")){
    print("WORKSPACE/artifacts directory found, Preparing to upload artifacts.")
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
                description_file: args.description_file
              ]
            ) // venvPlaybook
          } // withEnv
      } // dir
    } // withCredentials
  } else {
    print("WORKSPACE/artifacts not found, skipping artifact upload")
  }
}

return this
