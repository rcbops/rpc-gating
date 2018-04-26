def webhooks(){
  String instance_name = "WEBHOOK-PROXY"
  pubcloud.getPubCloudSlave(instance_name: instance_name)
  common.override_inventory()
  try{
    common.conditionalStage(
      stage_name: "Webhooks",
      stage:{
        withCredentials([
          file(
            credentialsId: 'id_rsa_cloud10_jenkins_file',
            variable: 'jenkins_ssh_privkey'
          ),
          file(
            credentialsId: 'id_rsa_phobostunnel_pub',
            variable: 'phobostunnel_ssh_pubkey'
          ),
          usernamePassword(
            credentialsId: "github_webhook_proxy_basic_auth",
            usernameVariable: "webhookproxy_user",
            passwordVariable: "webhookproxy_pass"
          ),
          usernamePassword(
            credentialsId: "jira_user_pass",
            usernameVariable: "JIRA_USER",
            passwordVariable: "JIRA_PASS"
          ),
          string(
            credentialsId: "RACKSPACE_WEBHOOK_TOKEN",
            variable: "RACKSPACE_WEBHOOK_TOKEN"
          ),
          string(
            credentialsId: "SSH_IP_ADDRESS_WHITELIST",
            variable: "SSH_IP_ADDRESS_WHITELIST"
          ),
        ]){
          dir('rpc-gating/playbooks'){
            common.venvPlaybook(
              playbooks: [
                "slave_security.yml",
                "webhooks.yml",
              ],
              args: [
                "-i inventory",
                "--private-key=\"${env.JENKINS_SSH_PRIVKEY}\""
              ],
              vars: [
                WORKSPACE: "${env.WORKSPACE}",
                webhookproxy_user: "${env.webhookproxy_user}",
                webhookproxy_pass: "${env.webhookproxy_pass}",
                phobostunnel_ssh_pubkey: "${env.phobostunnel_ssh_pubkey}"
              ]
            ) //venvPlaybook
          } //dir
        } //withCredentials
        String ip = readFile file: "instance_address"
       common.use_node("master"){
          withCredentials([
            file(
              credentialsId: 'id_rsa_cloud10_jenkins_file',
              variable: 'JENKINS_SSH_PRIVKEY'
            )]){
              sh """#!/bin/bash -x
                jdir=~jenkins
                keyfile=\$jdir/id_webhookproxy
                upstart_conf=\$jdir/webhookproxy.conf
                cp \$JENKINS_SSH_PRIVKEY \$keyfile ||:
                chmod 0400 \$keyfile
                cat > \$upstart_conf <<EOF
start on runlevel 2
# ssh will not daemonize so use the default expect.
# expect
respawn
respawn limit unlimited
exec ssh root@$ip -i \$keyfile -R 8888:localhost:443 -N
# This is the recommended way of implementing a respawn delay
# This is required to prevent a tight loop consuming jenkins master
# resources while trying to reconnect to the webhook proxy node.
# http://upstart.ubuntu.com/cookbook/#delay-respawn-of-a-job
post-stop exec sleep 15
EOF

                cat > \$jdir/root_setup.sh <<EOF
#!/bin/bash -x
mkdir ~/.ssh -p
ssh-keyscan $ip >> ~/.ssh/known_hosts
cp \$upstart_conf /etc/init
sleep 3;
initctl reload webhookproxy ||:
initctl stop webhookproxy
initctl start webhookproxy
EOF
               chmod +x \$jdir/root_setup.sh

                echo "Please run ~jenkins/root_setup.sh as root on the jenkins master"
                """
            } //withCredentials
          } //node
        }) //conditionalStage
  } catch (e){
    print(e)
    throw e
  }finally{
    pubcloud.delPubCloudSlave(instance_name: instance_name)
  }
} //func
return this
