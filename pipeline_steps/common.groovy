import groovy.json.JsonSlurperClassic
import groovy.json.JsonOutput

def void install_ansible(){
  print "install_ansible"
  sh """#!/bin/bash -e
    REPO_BASE="https://rpc-repo.rackspace.com/rpcgating/venvs"
    cd ${env.WORKSPACE}

    create_venv(){
      if [[ ! -d ".venv" ]]; then
        requirements="virtualenv==15.1.0"
        pip install -U "\${requirements}" \
          || pip install --isolated -U "\${requirements}"
        if which scl
        then
          # redhat/centos
          source /opt/rh/python27/enable
          virtualenv --no-pip --no-setuptools --no-wheel --python=/opt/rh/python27/root/usr/bin/python .venv
          # hack the selinux module into the venv
          cp -r /usr/lib64/python2.6/site-packages/selinux .venv/lib64/python2.7/site-packages/ ||:
        else
          virtualenv --no-pip --no-setuptools --no-wheel .venv
        fi
      fi

      # Install Pip
      source .venv/bin/activate

      # UG-613 change TMPDIR to directory with more space
      export TMPDIR="/var/lib/jenkins/tmp"

      # If the pip version we're using is not the same as the constraint then replace it
      PIP_TARGET="\$(awk -F= '/^pip==/ {print \$3}' rpc-gating/constraints.txt)"
      VENV_PYTHON=".venv/bin/python"
      VENV_PIP=".venv/bin/pip"
      if [[ "\$(\${VENV_PIP} --version)" != "pip \${PIP_TARGET}"* ]]; then
        # Install a known version of pip, setuptools, and wheel in the venv
        CURL_CMD="curl --silent --show-error --retry 5"
        OUTPUT_FILE="get-pip.py"
        \${CURL_CMD} https://bootstrap.pypa.io/get-pip.py > \${OUTPUT_FILE} \
          || \${CURL_CMD} https://raw.githubusercontent.com/pypa/get-pip/master/get-pip.py > \${OUTPUT_FILE}
        GETPIP_OPTIONS="pip setuptools wheel --constraint rpc-gating/constraints.txt"
        \${VENV_PYTHON} \${OUTPUT_FILE} \${GETPIP_OPTIONS} \
          || \${VENV_PYTHON} \${OUTPUT_FILE} --isolated \${GETPIP_OPTIONS}
      fi

      # Install rpc-gating requirements
      PIP_OPTIONS="-c rpc-gating/constraints.txt -r rpc-gating/requirements.txt"
      \${VENV_PIP} install \${PIP_OPTIONS} \
        || \${VENV_PIP} install --isolated \${PIP_OPTIONS}

      # Install ansible roles
      mkdir -p rpc-gating/playbooks/roles
      ansible-galaxy install -r rpc-gating/role_requirements.yml -p rpc-gating/playbooks/roles
    }

    unpack_venv(){
      op=\$(cat .venv/original_venv_path) # Original Path
      np=\${PWD}/.venv                    # New Path
      grep -ri --files-with-match \$op \
        |while read f; do sed -i.bak "s|\$op|\$np|" \$f; done
      if which scl; then
        echo "CentOS node detected, copying in external python interpreter and setting PYTHONPATH in activate script"
        # CentOS 6 can take a hike, its glibc isn't new enough for python 2.7.12
        cp /opt/rh/python27/root/usr/bin/python .venv/bin/python
        # hack the selinux module into the venv
        cp -r /usr/lib64/python2.6/site-packages/selinux .venv/lib64/python2.7/site-packages/ ||:
        # I'm not sure why this is needed, but I assume its due to a change in python's
        # default module search paths between 2.7.8 and 2.7.12
        echo "export PYTHONPATH=${env.WORKSPACE}/.venv/lib/python2.7/site-packages" >> .venv/bin/activate
      fi
    }

    pushd rpc-gating
      SHA=\$(git rev-parse HEAD)
    popd

    if (curl -s "\${REPO_BASE}/rpcgatingvenv_\${SHA}.tbz" > venv.tbz \
        && tar xjfp venv.tbz); then
      echo "Downloaded rpc-gating venv tar from rpc-repo for \$SHA, unpacking."
      unpack_venv
      echo "Venv download and modification complete. SHA:\${SHA}"
    else
      echo "Failed to download rpc-gating venv tar from rpc-repo for \$SHA, creating venv locally."
      create_venv
      echo "workspace/.venv creation complete"
    fi
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
  withEnv(get_deploy_script_env()){
    ansiColor('xterm'){
      if (!('vars' in args)){
        args.vars=[:]
      }
      if (!('args' in args)){
        args.args=[]
      }
      for (int i=0; i<args.playbooks.size(); i++){
        String playbook = args.playbooks[i]
        // randomised vars file path for parallel safety
        String vars_file="vars.${playbook.split('/')[-1]}.${rand_int_str()}"
        write_json(file: vars_file, obj: args.vars)
        sh """#!/bin/bash -x
          which scl && source /opt/rh/python27/enable
          set +x; . ${env.WORKSPACE}/.venv/bin/activate; set -x
          export ANSIBLE_HOST_KEY_CHECKING=False
          ansible-playbook ${args.args.join(' ')} -e@${vars_file} ${playbook}
        """
      } //for
    } //color
  } //withenv
} //venvplaybook

def calc_ansible_forks(){
  String forks = sh (script: """#!/bin/bash
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
List get_deploy_script_env(){
  String forks = calc_ansible_forks()
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
  def full_env = args.environment_vars + get_deploy_script_env()

  ansiColor('xterm'){
    dir(args.path) {
      withEnv(full_env){
        sh """#!/bin/bash
        openstack-ansible ${args.playbook} ${args.args}
        """
      }
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
  if (env.STAGES == null){
  throw new Exception(
    "ConditionalStage used without STAGES env var existing."\
    + " Ensure the top level job has a string param called STAGES.")
  }
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
  if (env.STAGES == null){
    throw new Exception(
      "ConditionalStep used without STAGES env var existing."\
      + " Ensure the top level job has a string param called STAGES.")
  }
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
def String acronym(Map args){
  String acronym=""
  List words=args.string.split("[-_ ]")
  for (def i=0; i<words.size(); i++){
    acronym += words[i][0]
  }
  return acronym
}

def String rand_int_str(int max=0xFFFF, int base=16){
  return Integer.toString(Math.abs((new Random()).nextInt(max)), base)
}

def String gen_instance_name(String prefix="AUTO"){
  String instance_name = ""
  if (env.INSTANCE_NAME == "AUTO"){
    if (prefix == "AUTO"){
      prefix = acronym(string: env.JOB_NAME)
    }
    //4 digit hex string to avoid name colisions
    instance_name = "${prefix}-${env.BUILD_NUMBER}-${rand_int_str()}"
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

def archive_artifacts(String build_type = "AIO"){
  try{
    if ( build_type == "MNAIO" ){
      args = [
        "-i /opt/ansible-static-inventory.ini",
      ]
      vars = [
        target_hosts: "all",
      ]
    } else {
      args = []
      vars = [
        target_hosts: "localhost",
      ]
    }
    dir("rpc-gating/playbooks"){
      venvPlaybook(
        playbooks: ['archive_artifacts.yml'],
        args: args,
        vars: vars,
      )
    }
  } catch (e){
    print(e)
    throw(e)
  } finally{
    // still worth trying to archiveArtifacts even if some part of
    // artifact collection failed.
      print "Uploading artifacts to Cloud Files..."
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

List get_cloud_creds(){
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
  String cfg = """[rackspace_cloud]
username = ${args.username}
api_key = ${args.api_key}
"""

  String tmp_dir = pwd(tmp:true)
  String pyrax_cfg = "${tmp_dir}/.pyrax.cfg"
  sh """
    echo "${cfg}" > ${pyrax_cfg}
  """

  return pyrax_cfg
}

def prepareConfigs(Map args){
  dir("rpc-gating/playbooks"){
    withCredentials(get_cloud_creds()) {
      venvPlaybook(
        playbooks: ["aio_config.yml"],
        args: [
          "-i inventory",
          "--extra-vars \"@vars/${args.deployment_type}.yml\""
        ]
      )
    }
  }
}

def prepareRpcGit(String branch = "auto", String dest = "/opt"){
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

    clone_with_pr_refs(env.RPC_REPO, branch)
  } // dir
}

// Clone repo with Refspecs required for PRs.
// Shouldn't need to supply any params to checkout a PR merged with the base.
// Uses shell+git to avoid hostname verification failures with
// the built in git scm step.
// Use init + fetch instead of clone so that the repo can
// be cloned into a non-empty directory. Thats added for
// compatibility with the jenkins git scm step.
// Note: Creds are not supplied for https connections
// If you need autheniticated access, use ssh:// or git@
void clone_with_pr_refs(
  String repo="git@github.com:${env.ghprbGhRepository}",
  String ref="origin/pr/${env.ghprbPullId}/merge",
  String refspec='+refs/pull/\\*:refs/remotes/origin/pr/\\*'\
                +' +refs/heads/\\*:refs/remotes/origin/\\*'
){
  if(repo == "git@github.com:"){
    throw new Exception(
      "repo not supplied to common.clone_with_pr_refs or env.ghprbGhRepository"\
      + " not set."
    )
  }
  if(ref == "origin/pr//merge"){
    throw new Exception(
      "ref not supplied to common.clone_with_pr_refs or env.ghprbPullID not "\
      + "set, attempting to checkout PR for a periodic build?")
  }
  print "Cloning Repo: ${repo}@${ref}"
  sshagent (credentials:['rpc-jenkins-svc-github-ssh-key']){
    sh """#!/bin/bash -xe
      # use init + fetch to avoid the "dir not empty git fail"
      git init .
      # If the git repo previously existed, we remove the origin
      git remote remove origin || true
      git remote add origin "${repo}"
      # Don't quote refspec as it should be separate args to git.
      git fetch --tags origin ${refspec}
      git checkout ${ref}
      git submodule update --init
    """
  }
}

void configure_git(){
  print "Configuring Git"
  // credentials store created to ensure that non public repos
  // can be cloned when specified as https:// urls.
  // Ssh auth is handled in clone_with_pr_refs
  sh """#!/bin/bash -xe
    mkdir -p ~/.ssh
    ssh-keyscan github.com >> ~/.ssh/known_hosts
    git config --global user.email "rpc-jenkins-svc@github.com"
    git config --global user.name "rpc.jenkins.cit.rackspace.net"
  """
  print "Git Configuration Complete"
}

/* Set mtime to a constant value as git doesn't track mtimes but
 * docker 1.7 does, this causes cache invalidation when files are
 * added.
 */
def docker_cache_workaround(){
   sh "touch -t 201704100000 *.txt"
}

def is_doc_update_pr(String git_dir) {
  if (env.ghprbPullId != null) {
    dir(git_dir) {
      def rc = sh(
        script: """#!/bin/bash
          set -xeu
          git status
          git show --stat=400,400 | awk '/\\|/{print \$1}' \
            | egrep -v -e '.*md\$' -e '.*rst\$' -e '^releasenotes/'
        """,
        returnStatus: true
      )
      if (rc==0){
        print "Not a documentation only change or not triggered by a pull request. Continuing..."
        return false
      }else if(rc==1){
        print "Skipping build as only documentation changes were detected"
        return true
      }else if(rc==128){
        throw new Exception("Directory is not a git repo, cannot check if changes are doc only")
      }
    }
  }
}

/* Look for JIRA issue key in commit messages for commits in the source branch
 * that aren't in the target branch.
 * This function uses environment variables injected by github pull request
 * builder and so can only be used for PR triggered jobs
 */
def get_jira_issue_key(String repo_path="rpc-openstack"){
  def key_regex = "[a-zA-Z][a-zA-Z0-9_]+-[1-9][0-9]*"
  dir(repo_path){
    commits = sh(
      returnStdout: true,
      script: """
        git log --pretty=%B upstream/${ghprbTargetBranch}..${ghprbSourceBranch}""")
    print("looking for Jira issue keys in the following commits: ${commits}")
    try{
      String key = (commits =~ key_regex)[0]
      print ("First Found Jira Issue Key: ${key}")
      return key
    } catch (e){
      throw new Exception("""
  No JIRA Issue key were found in commits ${repo_path}:${ghprbSourceBranch}""")
    }
  }
}

/* Attempt to add a jira comment, but don't fail if ghprb env vars are missing
 * or no Jira issue key is present in commit titles
 */
def safe_jira_comment(body, String repo_path="rpc-openstack"){
  if (env.ghprbTargetBranch == null){
    print ("Not a PR job, so not attempting to add a Jira comment")
    return
  }
  try{
    String key = get_jira_issue_key(repo_path)
    jiraComment(issueKey: key,
                body: body)
    print "Jira Comment Added: [${key}] ${body}"
  } catch (e){
    print ("Error while attempting to add a build result comment to a JIRA issue: ${e}")
  }
}

def delete_workspace() {
  dir(env.WORKSPACE) {
    print "Deleting workspace..."
    deleteDir()
  }
}

def create_jira_issue(String project="RE",
                      String tag=env.BUILD_TAG,
                      String link=env.BUILD_URL,
                      String type="Issue-releng-platform-alert"){
  withCredentials([
    usernamePassword(
      credentialsId: "jira_user_pass",
      usernameVariable: "JIRA_USER",
      passwordVariable: "JIRA_PASS"
    )
  ]){
    sh """#!/bin/bash -xe
      cd ${env.WORKSPACE}
      set +x; . .venv/bin/activate; set -x
      python rpc-gating/scripts/jirautils.py create_issue\
        --tag '$tag'\
        --link '$link'\
        --project '$project'\
        --user '$JIRA_USER' \
        --password '$JIRA_PASS' \
        --type '$type'
    """
  }
}

String get_current_git_sha(String repo_path) {
  String sha = ""
  dir(repo_path) {
    sha = sh(
      returnStdout: true,
      script: "git rev-parse --verify HEAD",
    ).trim()
  }
  print("Current SHA for '${repo_path}' is '${sha}'.")
  return sha
}

// Create inventory file. Useful for running part of a job against
// an existing node, where the job expects an inventory file to
// have been created by the resource allocation step.
void drop_inventory_file(String content,
                         String path='rpc-gating/playbooks/inventory/hosts'){
    dir(env.WORKSPACE){
      writeFile file: path, text: content
    }
}

// Conditional step to drop manually created inventory file
void override_inventory(){
  conditionalStep(
    step_name: "Override Inventory",
    step:{
        String inventory_path
        if (env.OVERRIDE_INVENTORY_PATH == null){
          inventory_path = 'rpc-gating/playbooks/inventory/hosts'
        } else{
          inventory_path = env.OVERRIDE_INVENTORY_PATH
        }
        drop_inventory_file(env.INVENTORY, inventory_path)
    }
  )
}

// initialisation steps for nodes
void use_node(String label=null, body){
  node(label){
    try {
      print "Preparing ${env.NODE_NAME} for use"
      deleteDir()
      dir("rpc-gating"){
        if (! env.RPC_GATING_BRANCH){
          env.RPC_GATING_BRANCH="master"
        }
        git branch: env.RPC_GATING_BRANCH, url: "https://github.com/rcbops/rpc-gating"
      }
      install_ansible()
      configure_git()
      print "${env.NODE_NAME} preparation complete, now ready for use."
      body()
    } catch (e){
      print "Caught exception on ${env.NODE_NAME}: ${e}"
      throw e
    } finally {
      deleteDir()
    }
  }
}

//shortcut functions for a shared slave or internal shared slave

void shared_slave(body){
  use_node("pubcloud_multiuse", body)
}

void internal_slave(body){
  use_node("CentOS", body)
}

return this
