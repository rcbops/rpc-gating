import groovy.json.JsonSlurperClassic
import groovy.json.JsonOutput

// Install ansible on a jenkins slave
def install_ansible(){
  sh """#!/bin/bash -xe
    cd ${env.WORKSPACE}
    if [[ ! -d ".venv" ]]; then
      if ! which virtualenv; then
        pip install virtualenv
      fi
      if which scl
      then
        source /opt/rh/python27/enable
        virtualenv --python=/opt/rh/python27/root/usr/bin/python .venv
      else
        virtualenv .venv
      fi
    fi
    # hack the selinux module into the venv
    cp -r /usr/lib64/python2.6/site-packages/selinux .venv/lib64/python2.7/site-packages/ ||:
    source .venv/bin/activate

    # These pip commands cannot be combined into one.
    pip install -U six packaging appdirs
    pip install -U setuptools
    pip install 'pip==9.0.1'
    pip install \
      -U \
      -c rpc-gating/constraints.txt \
      -r rpc-gating/requirements.txt

    mkdir -p rpc-gating/playbooks/roles
    ansible-galaxy install -r rpc-gating/role_requirements.yml -p rpc-gating/playbooks/roles
  """
}

/* Run ansible-playbooks within a venev
 * Sadly the standard ansibleplaybook step doesn't allow specifying a custom
 * ansible path. It does allow selection of an ansible tool, but those are
 * statically configured in global jenkins config.
 *
 * Args:
 *  playbooks: list of playbook filenames
 *  vars: dict of vars to be passed to ansible as overrides
 *  args: list of string args to pass to ansible-playbook
 */
def venvPlaybook(Map args){
  withEnv(common.get_deploy_script_env()){
    ansiColor('xterm'){
      if (!('vars' in args)){
        args.vars=[:]
      }
      if (!('args' in args)){
        args.args=[]
      }
      for (def i=0; i<args.playbooks.size(); i++){
        playbook = args.playbooks[i]
        vars_file="vars.${playbook.split('/')[-1]}"
        write_json(file: vars_file, obj: args.vars)
        sh """#!/bin/bash -x
          which scl && source /opt/rh/python27/enable
          . ${env.WORKSPACE}/.venv/bin/activate
          ansible-playbook -v ${args.args.join(' ')} -e@${vars_file} ${playbook}
        """
      } //for
    } //color
  } //withenv
} //venvplaybook

def calc_ansible_forks(){
  def forks = sh (script: """#!/bin/bash
    CPU_NUM=\$(grep -c ^processor /proc/cpuinfo)
    if [ \${CPU_NUM} -lt "10" ]; then
      ANSIBLE_FORKS=\${CPU_NUM}
    else
      ANSIBLE_FORKS=10
    fi
    echo -n "\${ANSIBLE_FORKS}"
  """, returnStdout: true)
  print "Ansible forks: ${forks}"
  return forks
}

/* this is a func rather than a var, so that the linter doesn't try
to evaluate ${forks} and fail.
These vars should be set every time deploy.sh or test-upgrade is run
*/
def get_deploy_script_env(){
  forks = calc_ansible_forks()
  return [
    'ANSIBLE_FORCE_COLOR=true',
    'ANSIBLE_HOST_KEY_CHECKING=False',
    'TERM=linux',
    "FORKS=${forks}",
    "ANSIBLE_FORKS=${forks}",
    'ANSIBLE_SSH_RETRIES=3',
    'ANSIBLE_GIT_RELEASE=ssh_retry', //only used in mitaka and below
    'ANSIBLE_GIT_REPO=https://github.com/hughsaunders/ansible' // only used in mitaka and below
  ]
}

def openstack_ansible(Map args){
  if (!('path' in args)){
    args.path = "/opt/rpc-openstack/openstack-ansible/playbooks"
  }
  if (!('args' in args)){
    args.args = ""
  }
  if (!('environment_vars' in args)){
    args.environment_vars = []
  }
  def full_env = args.environment_vars + common.get_deploy_script_env()

  ansiColor('xterm'){
    if (!('vm' in args)) {
      dir(args.path) {
        withEnv(full_env){
          sh """#!/bin/bash
          openstack-ansible ${args.playbook} ${args.args}
          """
        }
      }
    } else {
      def export_vars = ""
      for (e in full_env) {
        export_vars += "export ${e}; "
      }
      sh """#!/bin/bash
      sudo ssh -T -oStrictHostKeyChecking=no ${args.vm} \
        '${export_vars} cd ${args.path}; openstack-ansible ${args.playbook} ${args.args}'
      """
    }
  }
}


/*
 * JsonSluperClassic and JsonOutput are not serializable, so they
 * can only be used in @NonCPS methods. However readFile and writeFile
 * cannotbe used in NonCPS methods, so reading and writing json
 * requires one function to handle the io, and another to do the.
 * conversion.
 *
 * JsonSluperClassic returns a serializable object, but JsonSlurper
 * does not. This makes Classic preferable for pipeline use.
 */
@NonCPS
def _parse_json_string(Map args){
  return (new JsonSlurperClassic()).parseText(args.json_text)
}

@NonCPS
def _write_json_string(Map args){
    return (new JsonOutput()).toJson(args.obj)
}

/* Read Json file and return object
 * Args:
 *  file: String path of file to read
 */
def parse_json(Map args){
    return this._parse_json_string(
      json_text: readFile(file: args.file)
    )
}

/* Write object to file as JSON
 * Args:
 *  file: String path of file to write
 *  obj: Object to translate into JSON
 */
def write_json(Map args){
  writeFile(
    file: args.file,
    text: this._write_json_string(obj: args.obj)
  )
}

/* Run a bash script
 * Args:
 *  script: Script or path to script from the current directory to run
 *  environment_vars: Environment variables to set
 */
def run_script(Map args) {
  withEnv(args.environment_vars) {
    sh """
        #!/bin/bash
        sudo -E ./${args.script}
        """
  }
}

/* Run a stage if the stage name is contained in an env var
 * Args:
 *   - stage_name: String name of this stage
 *   - stage: Closure to execute
 * Environment:
 *    - STAGES: String list of stages that should be run
 */
def conditionalStage(Map args){
  stage(args.stage_name){
    if (env.STAGES.contains(args.stage_name)){
        print "Stage Start: ${args.stage_name}"
        args.stage()
        print "Stage Complete: ${args.stage_name}"
    } else {
      print "Skipped: ${args.stage_name}"
    }
  }
}

/* Run a step if the step name is contained in an env var
 * Args:
 *   - step_name: String name of this stage
 *   - step: Closure to execute
 * Environment:
 *    - STAGES: String list of steps that should be run
 *
 * The difference between this and conditionalStage is that
 * this doesn't wrap the step in a stage block. This is useful
 * for cleanup jobs which confuse the jenkins visualisation when
 * run in a stage.
 */
def conditionalStep(Map args){
  if (env.STAGES.contains(args.step_name)){
      print "Step Start: ${args.step_name}"
      args.step()
      print "Step Complete: ${args.step_name}"
  } else {
    print "Skipped: ${args.step_name}"
  }
}

/* Create acronym
 * quick brown fox --> qbf
 * Arguments:
 *  string: the string to process
 */
def acronym(Map args){
  acronym=""
  words=args.string.split("[-_ ]")
  for (def i=0; i<words.size(); i++){
    acronym += words[i][0]
  }
  return acronym
}

def gen_instance_name(){
  if (env.INSTANCE_NAME == "AUTO"){
    job_name_acronym = common.acronym(string: env.JOB_NAME)
    //4 digit hex string to avoid name colisions
    rand_str = Integer.toString(Math.abs((new Random()).nextInt(0xFFFF)), 16)
    instance_name = "${job_name_acronym}-${env.BUILD_NUMBER}-${rand_str}"
  }
  else {
    instance_name = env.INSTANCE_NAME
  }
  //Hostname should match instance name for MaaS. Hostnames are converted
  //to lower case, so we'll do the same for instance name.
  instance_name = instance_name.toLowerCase()
  print "Instance_name: ${instance_name}"
  return instance_name
}

def archive_artifacts(){
  try{
    sh """#!/bin/bash
    d="artifacts_\${BUILD_TAG}"
    mkdir -p \$d

    # logs and config from the single use slave
    mkdir -p \$d/\$HOSTNAME/log
    cp -rp /openstack/log/\$HOSTNAME-* \$d/\$HOSTNAME/log ||:
    cp -rp /etc/ \$d/\$HOSTNAME/etc
    cp -rp /var/log/ \$d/\$HOSTNAME/var_log

    # logs and config from the containers
    while read c
    do
      mkdir -p \$d/\$c/log
      cp -rp /openstack/log/\$c/* \$d/\$c/log ||:
      cp -rp /var/lib/lxc/\$c/rootfs/etc \$d/\$c ||:
    done < <(lxc-ls)

    # compress to reduce storage space requirements
    tar cjf "\$d".tar.bz2 \$d
    """
  } catch (e){
    print(e)
    throw(e)
  } finally{
    // still worth trying to archiveArtifacts even if some part of
    // artifact collection failed.
      pubcloud.uploadToCloudFiles(
        container: "jenkins_logs",
        src: "${env.WORKSPACE}/artifacts_${env.BUILD_TAG}.tar.bz2",
        html_report_dest: "${env.WORKSPACE}/artifacts_report/index.html")
      publishHTML(
        allowMissing: true,
        alwaysLinkToLastBuild: true,
        keepAll: true,
        reportDir: 'artifacts_report',
        reportFiles: 'index.html',
        reportName: 'Build Artifact Links'
      )
    sh """
    rm -rf artifacts_\${BUILD_TAG}
    rm -f artifacts_${env.BUILD_TAG}.tar.bz2
    """
  }
}

def get_cloud_creds(){
  return [
    string(
      credentialsId: "dev_pubcloud_username",
      variable: "PUBCLOUD_USERNAME"
    ),
    string(
      credentialsId: "dev_pubcloud_api_key",
      variable: "PUBCLOUD_API_KEY"
    ),
    string(
      credentialsId: "dev_pubcloud_tenant_id",
      variable: "PUBCLOUD_TENANT_ID"
    ),
    file(
      credentialsId: 'id_rsa_cloud10_jenkins_file',
      variable: 'JENKINS_SSH_PRIVKEY'
    )
  ]
}

def writePyraxCfg(Map args){
  cfg = """[rackspace_cloud]
username = ${args.username}
api_key = ${args.api_key}
"""

  tmp_dir = pwd(tmp:true)
  pyrax_cfg = "${tmp_dir}/.pyrax.cfg"
  sh """
    echo "${cfg}" > ${pyrax_cfg}
  """

  return pyrax_cfg
}

def prepareConfigs(Map args){
  withCredentials(get_cloud_creds()){
      dir("rpc-gating"){
        git branch: env.RPC_GATING_BRANCH, url: env.RPC_GATING_REPO
      } //dir
      dir("rpc-gating/playbooks"){
        common.install_ansible()
        withCredentials(common.get_cloud_creds()) {
          List maas_vars = maas.get_maas_token_and_url(env.PUBCLOUD_USERNAME, env.PUBCLOUD_API_KEY, env.REGION)
          withEnv(maas_vars) {
            common.venvPlaybook(
              playbooks: ["aio_config.yml"],
              args: [
                "-i inventory",
                "--extra-vars \"@vars/${args.deployment_type}.yml\""
              ]
            )
          }
        }
      } //dir
  } //withCredentials
}

def prepareRpcGit(branch = "auto", dest = "/opt"){
  dir("${dest}/rpc-openstack"){

    if (branch == "auto"){
      /* if job is triggered by PR, then we need to set RPC_REPO and
         RPC_BRANCH using the env vars supplied by ghprb.
      */
      if ( env.ghprbPullId != null ){
        env.RPC_REPO = "https://github.com/${env.ghprbGhRepository}.git"
        branch = "origin/pr/${env.ghprbPullId}/merge"
        print("Triggered by PR: ${env.ghprbPullLink}")
      } else {
        branch = env.RPC_BRANCH
      }
    }

    print("Repo: ${env.RPC_REPO} Branch: ${branch}")

    // checkout used instead of git as a custom refspec is required
    // to checkout pull requests
    checkout([$class: 'GitSCM',
      branches: [[name: branch]],
      doGenerateSubmoduleConfigurations: false,
      extensions: [[$class: 'CleanCheckout']],
      submoduleCfg: [],
      userRemoteConfigs: [
        [
          url: env.RPC_REPO,
          refspec: '+refs/pull/*:refs/remotes/origin/pr/* +refs/heads/*:refs/remotes/origin/*'
        ]
      ]
    ]) // checkout
    sh "git submodule update --init"
  } // dir
}

/* Set mtime to a constant value as git doesn't track mtimes but
 * docker 1.7 does, this causes cache invalidation when files are
 * added.
 */
def docker_cache_workaround(){
   sh "touch -t 201704100000 *.txt"
}

def is_doc_update_pr(git_dir) {
  is_doc_update_pr = false
  if (env.ghprbPullId != null) {
    dir(git_dir) {
      def output = sh(script: """#!/bin/bash
      git show --stat=400,400 | awk '/\\|/{print \$1}' \
        | egrep -v -e '.*md\$' -e '.*rst\$' -e '^releasenotes/' \
        || echo "Skipping build as only documentation changes were detected"
      """, returnStdout: true)
      print output
      is_doc_update_pr = output.contains("Skipping build as only documentation changes were detected")
    }
  }
  if(!is_doc_update_pr){
    print "Not a documentation only change or not triggered by a pull request. Continuing..."
  }
  return is_doc_update_pr
}

return this
