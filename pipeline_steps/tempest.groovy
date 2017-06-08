def tempest_install(vm=null){
  // NOTE(mkam): Can remove ANSIBLE_CACHE_PLUGIN when we no longer gate stable/mitaka
  // NOTE(alextricity25): --skip-tags needs to be used here because tags cannot be used
  // with included roles in earlier versions of Ansible. Such that used in Mitaka
  common.openstack_ansible(
    vm: vm,
    playbook: "../../scripts/run_tempest.yml",
    args: "--skip-tags tempest_execute_tests",
    path: "/opt/rpc-openstack/rpcd/playbooks",
    environment_vars: ["ANSIBLE_CACHE_PLUGIN=memory"]
  )
}

def tempest_run(wrapper="") {
  tempest_cmd = "cd /opt/rpc-openstack/rpcd/playbooks && openstack-ansible ../../scripts/run_tempest.yml -t tempest_execute_tests -vv"
  if (wrapper != "") {
    tempest_cmd_wrapped = "${wrapper} '${tempest_cmd}'"
  } else {
    tempest_cmd_wrapped = tempest_cmd
  }
  def output = sh (script: """#!/bin/bash
  ${tempest_cmd_wrapped}
  """, returnStdout: true)
  print output
  return output
}


/* if tempest install fails, don't bother trying to run or collect test results
 * however if running fails, we should still collect the failed results
 */
def tempest(deploy_vm=null){
  if (deploy_vm != null) {
    wrapper = "sudo ssh -T -oStrictHostKeyChecking=no ${deploy_vm}"
    copy_cmd = "scp -o StrictHostKeyChecking=no -p  -r \$(${wrapper} \"cd /opt/rpc-openstack/openstack-ansible/playbooks && ansible utility[0] -m command -a 'echo {{ physical_host }}' | grep -Ev 'Variable files|utility'\"):"
  } else{
    wrapper = ""
    copy_cmd = "cp -p "
  }
  common.conditionalStage(
    stage_name: "Install Tempest",
    stage: {
      tempest_install(deploy_vm)
    }
  )
  common.conditionalStage(
    stage_name: "Tempest Tests",
    stage: {
      try{
        def result = tempest_run(wrapper)
        def second_result = ""
        if(result.contains("Race in testr accounting.")){
          second_result = tempest_run(wrapper)
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
