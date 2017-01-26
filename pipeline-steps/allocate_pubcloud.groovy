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
def call(Map args){
    common = load './rpc-gating/pipeline-steps/common.groovy'
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
          created = common.parse_json(file: 'resources.json')

          return ["created": created,
                  "create_args": args]
          } // directory
        } //withCredentials
      } // withEnv
} //call
return this
