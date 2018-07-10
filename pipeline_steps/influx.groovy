def setup(){
  String instance_name = common.gen_instance_name("influx")
  pubcloud.getPubCloudSlave(instance_name: instance_name)
  common.override_inventory()
  try{
    common.conditionalStage(
      stage_name: "Influx",
      stage:{
        creds = [
          file(
            credentialsId: 'id_rsa_cloud10_jenkins_file',
            variable: 'jenkins_ssh_privkey'
          ),
          string(
            credentialsId: "INFLUX_METRIC_PASSWORD",
            variable: "INFLUX_METRIC_PASSWORD"
          ),
          string(
            credentialsId: "INFLUX_ROOT_PASSWORD",
            variable: "INFLUX_ROOT_PASSWORD"
          ),
          string(
            credentialsId: "GRAFANA_ADMIN_PASSWORD",
            variable: "GRAFANA_ADMIN_PASSWORD"
          ),
        ]
        if (env.USE_SSH_WHITELIST == "true"){
          creds += string(
            credentialsId: "SSH_IP_ADDRESS_WHITELIST",
            variable: "SSH_IP_ADDRESS_WHITELIST"
          )
        }
        dir('rpc-maas'){
          git branch: env.RPC_MAAS_BRANCH, url: env.RPC_MAAS_REPO
          sh """#!/bin/bash
            export INVENTORY="${env.WORKSPACE}/inventory/hosts"
            mkdir -p \$(dirname \$INVENTORY)
            cp ${env.WORKSPACE}/rpc-gating/playbooks/inventory/hosts \$INVENTORY
            if ! grep influx_hosts \$INVENTORY; then
              echo [influx_hosts:children] >> \$INVENTORY
              echo job_nodes >> \$INVENTORY
            fi
          """
        }
        common.withRequestedCredentials("jenkins_ssh_privkey") {
          common.venvPlaybook(
            playbooks: [
              "rpc-gating/playbooks/slave_security.yml",
              "rpc-maas/playbooks/maas-tigkstack-influxdb.yml",
              "rpc-maas/playbooks/maas-tigkstack-grafana.yml",
            ],
            args: [
              "-i ${env.WORKSPACE}/inventory",
              "--limit job_nodes",
              "--private-key=\"${env.JENKINS_SSH_PRIVKEY}\""
            ],
            vars: [
              WORKSPACE                  : env.WORKSPACE,
              influxdb_db_root_password  : env.INFLUX_ROOT_PASSWORD,
              influxdb_db_metric_password: env.INFLUX_METRIC_PASSWORD,
              grafana_admin_password     : env.GRAFANA_ADMIN_PASSWORD
            ]
         )
        }
      })
  } catch (e){
    print(e)
    throw e
  }finally{
    pubcloud.delPubCloudSlave(instance_name: instance_name)
  }
} //func

void build_report(Map kwargs){
  withCredentials([
     string(
       credentialsId: "INFLUX_IP",
       variable: "INFLUX_IP"
     )
   ]){
     if (kwargs.leapfrog){
       cmdargs = "--leapfrog-upgrade"
     } else{
       cmdargs = ""
     }
     dir("rpc-gating/influx-reports"){
       sh "./influx-report.sh ${cmdargs}"
     }
  }
}
return this
