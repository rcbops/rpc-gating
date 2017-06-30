def setup(){
  instance_name = "INFLUX"
  pubCloudSlave.getPubCloudSlave(instance_name: instance_name)
  common.override_inventory()
  try{
    common.conditionalStage(
      stage_name: "Influx",
      stage:{
        withCredentials([
          file(
            credentialsId: 'id_rsa_cloud10_jenkins_file',
            variable: 'jenkins_ssh_privkey'
          ),
          string(
            credentialsId: "SSH_IP_ADDRESS_WHITELIST",
            variable: "SSH_IP_ADDRESS_WHITELIST"
          ),
        ]){
          dir(WORKSPACE){
            unstash "pubcloud_inventory"
          }
          dir('rpc-gating'){
            git branch: env.RPC_GATING_BRANCH, url: env.RPC_GATING_REPO
            common.venvPlaybook(
              playbooks: [
                "playbooks/slave_security.yml",
              ],
              args: [
                "-i ${env.WORKSPACE}/inventory",
                "--limit job_nodes",
                "--private-key=\"${env.JENKINS_SSH_PRIVKEY}\""
              ],
              vars: [
                WORKSPACE: "${env.WORKSPACE}",
              ]
            )
          }
          dir('rpc-maas'){
            git branch: env.RPC_MAAS_BRANCH, url: env.RPC_MAAS_REPO
            sh """
              export INVENTORY="${env.WORKSPACE}/inventory/hosts"
              if ! grep log_hosts $INVENTORY; then
                  echo [log_hosts:children] >> $INVENTORY
                  echo job_nodes >> $INVENTORY
              fi
            """
            // Run playbooks
            common.venvPlaybook(
              playbooks: [
                "playbooks/maas-tigkstack-influxdb.yml",
              ],
              args: [
                "-i ${env.WORKSPACE}/inventory",
                "--limit job_nodes",
                "--private-key=\"${env.JENKINS_SSH_PRIVKEY}\""
              ],
              vars: [
                WORKSPACE: "${env.WORKSPACE}"
              ]
            ) //venvPlaybook
          } //dir
        } //withCredentials
      }) //conditionalStage
  } catch (e){
    print(e)
    throw e
  }finally{
    pubCloudSlave.delPubCloudSlave()
  }
} //func
return this
