def setup(){
  instance_name = common.gen_instance_name("influx")
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
          string(
            credentialsId: "INFLUX_METRIC_PASSWORD",
            variable: "INFLUX_METRIC_PASSWORD"
          ),
          string(
            credentialsId: "INFLUX_ROOT_PASSWORD",
            variable: "INFLUX_ROOT_PASSWORD"
          ),
        ]){
          dir('rpc-gating'){
            git branch: env.RPC_GATING_BRANCH, url: env.RPC_GATING_REPO
          }
          dir('rpc-maas'){
            git branch: env.RPC_MAAS_BRANCH, url: env.RPC_MAAS_REPO
            sh """#!/bin/bash
              export INVENTORY="${env.WORKSPACE}/inventory/hosts"
              mkdir -p \$(dirname \$INVENTORY)
              cp ${env.WORKSPACE}/rpc-gating/playbooks/inventory/hosts \$INVENTORY
              if ! grep log_hosts \$INVENTORY; then
                  echo [log_hosts:children] >> \$INVENTORY
                  echo job_nodes >> \$INVENTORY
              fi
            """
          }
          common.venvPlaybook(
            playbooks: [
              "rpc-gating/playbooks/slave_security.yml",
              "rpc-maas/playbooks/maas-tigkstack-influxdb.yml",
            ],
            args: [
              "-i ${env.WORKSPACE}/inventory",
              "--limit job_nodes",
              "--private-key=\"${env.JENKINS_SSH_PRIVKEY}\""
            ],
            vars: [
              WORKSPACE: env.WORKSPACE,
              influxdb_db_root_password: env.INFLUX_ROOT_PASSWORD,
              influxdb_db_metric_password: env.INFLUX_METRIC_PASSWORD
            ]
          )
        }
      })
  } catch (e){
    print(e)
    throw e
  }finally{
    pubCloudSlave.delPubCloudSlave()
  }
} //func
return this
