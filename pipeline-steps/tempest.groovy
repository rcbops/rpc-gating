def tempest_install(){
  common.openstack_ansible(
    playbook: "os-tempest-install.yml",
    path: "/opt/rpc-openstack/openstack-ansible/playbooks"
  )
}

def tempest_run(Map args){
  withEnv(["RUN_TEMPEST_OPTS=${env.RUN_TEMPEST_OPTS}"]){
    sh """#!/bin/bash
      utility_container="\$(lxc-ls |grep -m1 utility)"
      lxc-attach \
        --keep-env \
        -n \$utility_container \
        -- /opt/openstack_tempest_gate.sh \
        ${env.TEMPEST_TEST_SETS}
    """
  }
}

def multi_node_tempest_run(Map args){
  withEnv(["RUN_TEMPEST_OPTS=${env.RUN_TEMPEST_OPTS}"]) {
    sh """#!/bin/bash
      utility_container="\$(sudo ssh -T -oStrictHostKeyChecking=no infra1 lxc-ls |grep -m1 utility)"
      sudo ssh -T -oStrictHostKeyChecking=no infra1 lxc-attach \
        --keep-env
        -n \$utility_container \
        -- /opt/openstack_tempest_gate.sh \
        ${env.TEMPEST_TEST_SETS}
  """
  }
}

/* if tempest install fails, don't bother trying to run or collect test results
 * however if runnig fails, we should still collect the failed results
 */
def tempest(){
  common.conditionalStage(
    stage_name: "Tempest",
    stage: {
      tempest_install()
      try{
        tempest_run()
      } catch (e){
        print(e)
        throw(e)
      } finally{
        sh "rm *tempest*.xml; cp /openstack/log/*utility*/**/*tempest*.xml . ||:"
        junit allowEmptyResults: true, testResults: '*tempest*.xml'
      } //finally
    } //stage
  ) //conditionalStage
} //func

def multi_node_tempest(){
  common.conditionalStage(
    stage_name: "Tempest",
    stage: {
      tempest_install()
      try{
        multi_node_tempest_run()
      } catch (e){
        print(e)
        throw(e)
      } finally{
        sh "rm *tempest*.xml; scp -o StrictHostKeyChecking=no -r infra1:/openstack/log/*utility*/**/*tempest*.xml . ||:"
        junit allowEmptyResults: true, testResults: '*tempest*.xml'
      } //finally
    } //stage
  ) //conditionalStage
} //func

return this;
