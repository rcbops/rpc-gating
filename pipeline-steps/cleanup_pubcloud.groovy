/* Remove public cloud instances
 * Params:
 *  - resources: dict returned by allocate_pubcloud
 *    - resources.create_args.region: region instances were built in
 *    - resources.created: list of instances created by allocate_pubcloud
 *      - resources.created.item.id: Id field is used to identify instances to delete
 */
def call(Map args){
    common = load './rpc-gating/pipeline-steps/common.groovy'
    withEnv(['ANSIBLE_FORCE_COLOR=true',
             "RAX_REGION=${args.resources.create_args.region}" ]){
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
            vars: args.resources
          )
          } // directory
        } //withCredentials
      } // withEnv
} //call
return this
