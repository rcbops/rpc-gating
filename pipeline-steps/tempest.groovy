def tempest_install(){
  common.openstack_ansible(
    playbook: "os-tempest-install.yml",
    path: "/opt/rpc-openstack/openstack-ansible/playbooks"
  )
}

def tempest_run(Map args) {
  def output = sh (script: """#!/bin/bash
  utility_container="\$(${args.wrapper} lxc-ls |grep -m1 utility)"
    ${args.wrapper} lxc-attach \
      --keep-env \
      -n \$utility_container \
      -- /opt/openstack_tempest_gate.sh \
      ${env.TEMPEST_TEST_SETS}
  """, returnStdout: true)
  print output
  return output
}


/* if tempest install fails, don't bother trying to run or collect test results
 * however if running fails, we should still collect the failed results
 */
def tempest(Map args){
  if (args != null && args.containsKey("vm")) {
    wrapper = "sudo ssh -T -oStrictHostKeyChecking=no ${args.vm} \
                RUN_TEMPEST_OPTS=\\\"${env.RUN_TEMPEST_OPTS}\\\" TESTR_OPTS=\\\"${env.TESTR_OPTS}\\\" "
    copy_cmd = "scp -o StrictHostKeyChecking=no -p  -r infra1:"
  } else{
    wrapper = ""
    copy_cmd = "cp -p "
  }
  common.conditionalStage(
    stage_name: "Install Tempest",
    stage: {
      tempest_install()
    }
  )
  common.conditionalStage(
    stage_name: "Tempest Tests",
    stage: {
      try{
        def result = tempest_run(wrapper: wrapper)
        def second_result = ""
        if(result.contains("Race in testr accounting.")){
          second_result = tempest_run(wrapper: wrapper)
        }
        if(second_result.contains("Race in testr accounting.")) {
          currentBuild.result = 'FAILURE'
        }
        } catch (e){
        print(e)
        throw(e)
      } finally{
        sh """
        rm -f *tempest*.xml
        ${copy_cmd}/openstack/log/*utility*/**/*tempest*.xml . ||:
        ${copy_cmd}/openstack/log/*utility*/*tempest*.xml . ||:
        """
        junit allowEmptyResults: true, testResults: '*tempest*.xml'
      } //finally
    } //stage
  ) //conditionalStage
} //func


return this;
