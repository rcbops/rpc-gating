import groovy.json.JsonSlurperClassic
import groovy.json.JsonOutput
import org.jenkinsci.plugins.workflow.job.WorkflowJob

void download_venv(){
  sh """#!/bin/bash -xeu
    REPO_BASE="https://rpc-repo.rackspace.com/rpcgating/venvs"
    cd ${env.WORKSPACE}
    pushd rpc-gating
      SHA=\$(git rev-parse HEAD)
    popd
    curl --fail -s "\${REPO_BASE}/rpcgatingvenv_\${SHA}.tbz" > venv.tbz
  """
  print("Venv Downloaded")
}
void install_ansible(){
  print("install_ansible")
  try{
    download_venv()
  }catch (e){
    print("Venv not found, kicking off Build-Gating-Venv to build it. Error: ${e}")
    build(
      job: "Build-Gating-Venv",
      wait: true,
      parameters: [
        [
          $class: 'StringParameterValue',
          name: 'RPC_GATING_BRANCH',
          value: env.RPC_GATING_BRANCH
        ]
      ]
    )
    sleep(time: 60, unit: "SECONDS")
    retry(3){
      try{
        download_venv()
      } catch (f) {
        print ("Post venv build download failed, pausing before retry")
        sleep(time: 60, unit: "SECONDS")
        throw f
      }
    }
  }
  sh """#!/bin/bash -eu
    cd ${env.WORKSPACE}
    echo "Unpacking Venv..."
    tar xjfp venv.tbz
    op=\$(cat .venv/original_venv_path) # Original Path
    np=\${PWD}/.venv                    # New Path
    grep -ri --files-with-match \$op \
      |while read f; do sed -i.bak "s|\$op|\$np|" \$f; done
    [[ -e .venv/lib64 ]] || {
      pushd .venv
        ln -s lib lib64
      popd
    }
    if which scl; then
      echo "CentOS node detected, copying in external python interpreter and setting PYTHONPATH in activate script"
      # CentOS 6 can take a hike, its glibc isn't new enough for python 2.7.12
      cp /opt/rh/python27/root/usr/bin/python .venv/bin/python
      # hack the selinux module into the venv
      cp -r /usr/lib64/python2.6/site-packages/selinux .venv/lib/python2.7/site-packages/ ||:
      # I'm not sure why this is needed, but I assume its due to a change in python's
      # default module search paths between 2.7.8 and 2.7.12
      echo "export PYTHONPATH=${env.WORKSPACE}/.venv/lib/python2.7/site-packages" >> .venv/bin/activate
    fi
    echo "Venv Unpack Complete"
  """
}

/* Run ansible-galaxy within the rpc-gating venv
 * Args:
 *  args: list of string args to pass to ansible-galaxy
 */
def venvGalaxy(String[] args){
  sh """#!/bin/bash -x
    which scl && source /opt/rh/python27/enable
    set +x; . ${env.WORKSPACE}/.venv/bin/activate; set -x
    ansible-galaxy ${args.join(' ')}
  """
} //venvGalaxy

/* Run ansible-playbooks within the rpc-gating venv
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
    "ANSIBLE_FORKS=${forks}",
    'ANSIBLE_SSH_RETRIES=3',
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

  dir(args.path) {
    withEnv(full_env){
      sh """#!/bin/bash
      openstack-ansible ${args.playbook} ${args.args}
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

String container_name(){
  return "jenkinsjob_"+env.JOB_NAME+"_"+env.BUILD_NUMBER
}

def archive_artifacts(Map args = [:]){
  stage('Compress and Publish Artifacts'){
    try{
      artifacts_dir = args.get("artifacts_dir", "${env.WORKSPACE}/artifacts")
      results_dir = args.get("results_dir", "${env.WORKSPACE}/results")

      dir(results_dir) {
        Integer testXmlLintRc = sh(
          returnStatus: true,
          script: """#!/bin/bash -xe
            test -f /usr/bin/xmllint
          """
        )

        // The junit step doesn't like parsing non-XML files (resulting in builds
        // being // marked as UNSTABLE). If xmllint is installed we verify that
        // all XML input is valid, and if not we move the affected files aside so
        // the junit pipeline step won't parse them.
        if (testXmlLintRc == 0) {
          List xmlFiles = findFiles(glob: '*.xml')

          for (file in xmlFiles) {
            Integer xmlStatus = sh(
              returnStatus: true,
              script: """#!/bin/bash -xe
                /usr/bin/xmllint ${file} > /dev/null || mv ${file} ${file}-broken
              """
            )
          }
        }

        junit allowEmptyResults: true, testResults: "*.xml"
      }

      pubcloud.uploadToSwift(
        container: container_name(),
        path: artifacts_dir
      )
      if(fileExists(file: "artifact_public_url")){
        artifact_public_url = readFile(file: "artifact_public_url")
        currentBuild.description = "<h2><a href='"+artifact_public_url+"'>Build Artifacts</a></h2>"
      }
    } catch (e){
      // This function is called from stdJob, so we must catch exceptions to
      // prevent failures in RE code eg (artifact archival) from causing
      // the build result to be set to failure.
      // try-catch placed here because this function is used in multiple places.
      try {
        print "Error while archiving artifacts, swallowing this exception to prevent "\
              +"archive errors from failing the build: ${e}"
        create_jira_issue("RE",
                          "Artifact Archival Failure: ${env.BUILD_TAG}",
                          "[${env.BUILD_TAG}|${env.BUILD_URL}]",
                          ["jenkins", "artifact_archive_fail"])
      } catch (f){
        print "Failed to create Jira Issue :( ${f}"
      }
    }// try
  } // stage
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

def writeCloudsCfg(){
  withRequestedCredentials("cloud_creds") {
    String cfg = """
client:
  force_ipv4: true
clouds:
  public_cloud:
    profile: rackspace
    auth_type: rackspace_apikey
    auth:
      username: ${env.PUBCLOUD_USERNAME}
      api_key: ${env.PUBCLOUD_API_KEY}
    # The default regions include LON which is not
    # in the same catalog, causing errors when
    # using the ansible dynamic inventory due to
    # missing endpoints. We therefore specify all
    # the regions in the US catalog.
    regions:
      - IAD
      - DFW
      - ORD
      - HKG
      - SYD

  phobos_nodepool:
    identity_api_version: 3
    insecure: true
    auth:
      auth_url: "${env.PHOBOS_NODEPOOL_AUTH_URL}"
      project_name: "${env.PHOBOS_NODEPOOL_PROJECT_NAME}"
      project_id: "${env.PHOBOS_NODEPOOL_PROJECT_ID}"
      username: "${env.PHOBOS_NODEPOOL_USERNAME}"
      password: "${env.PHOBOS_NODEPOOL_PASSWORD}"
      user_domain_name: "${env.PHOBOS_NODEPOOL_USER_DOMAIN_NAME}"

# This configuration is used by ansible
# when using the openstack dynamic inventory.
ansible:
  use_hostnames: True
  expand_hostvars: False
  fail_on_errors: False
"""

    String tmp_dir = pwd(tmp: true)
    String clouds_cfg = "${tmp_dir}/clouds.yaml"
    sh """
    echo "${cfg}" > ${clouds_cfg}
    """
    return clouds_cfg
  }
}

def writeRaxmonCfg(Map args){
  String cfg = """[credentials]
username=${args.username}
api_key=${args.api_key}

[api]
url=https://monitoring.api.rackspacecloud.com/v1.0

[auth_api]
url=https://identity.api.rackspacecloud.com/v2.0/tokens

[ssl]
verify=true
"""

  String tmp_dir = pwd(tmp:true)
  String raxrc_cfg = "${tmp_dir}/.raxrc.cfg"
  sh """
    echo "${cfg}" > ${raxrc_cfg}
  """

  return raxrc_cfg
}

def prepareRpcGit(String branch = "auto", String dest = "/opt"){
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

  clone_with_pr_refs("${dest}/rpc-openstack", env.RPC_REPO, branch)
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
  String directory='./',
  String repo="git@github.com:${env.ghprbGhRepository}",
  String ref="origin/pr/${env.ghprbPullId}/merge",
  String refspec='+refs/pull/\\*:refs/remotes/origin/pr/\\*'\
                +' +refs/heads/\\*:refs/remotes/origin/\\*'
){
  if(repo == "git@github.com:null"){
    throw new Exception(
      "repo not supplied to common.clone_with_pr_refs or env.ghprbGhRepository"\
      + " not set."
    )
  }
  if(ref == "origin/pr/null/merge"){
    throw new Exception(
      "ref not supplied to common.clone_with_pr_refs or env.ghprbPullID not "\
      + "set, attempting to checkout PR for a periodic build?")
  }
  if (is_internal_repo_id(repo)) {
    clone_internal_repo(directory, repo, ref, refspec)
  } else {
    clone_external_repo(directory, repo, ref, refspec)
  }
}


void clone_repo(String directory, String ssh_key, String repo, String ref, String refspec) {
  print "Cloning Repo: ${repo}@${ref}"
  sshagent (credentials:[ssh_key]){
    sh """#!/bin/bash -xe
      mkdir -p ${directory}
      cd ${directory}
      # use init + fetch to avoid the "dir not empty git fail"
      git init .
      # If the git repo previously existed, we remove the origin
      git remote rm origin || true
      git remote add origin "${repo}"
      # Don't quote refspec as it should be separate args to git.
      # only log errors
      git fetch --quiet --tags origin ${refspec}
      git checkout ${ref}
      git submodule update --init
    """
  }
}


Boolean is_internal_repo_id(String repo_url) {
    return repo_url.startsWith("internal:")
}


void clone_internal_repo(String directory, String internal_repo, String ref, String refspec) {
  repo_secret_id = internal_repo.split(":", 2)[1]
  repo_creds = [
    string(
      credentialsId: repo_secret_id,
      variable: "INTERNAL_REPO_URL"
    ),
  ]

  internal_slave() {
    withCredentials(repo_creds) {
      clone_repo(directory, "rpc-jenkins-svc-github-key", env.INTERNAL_REPO_URL, ref, refspec)
    }

    if (directory.endsWith("/")) {
      directory_pattern = directory.minus(env.WORKSPACE + "/") + "**"
    } else {
      directory_pattern = directory.minus(env.WORKSPACE + "/") + "/**"
    }
    print "Internal repo stash include pattern: \"${directory_pattern}\"."
    stash includes: directory_pattern, name: "repo-clone"
  }
  unstash "repo-clone"
}


void clone_external_repo(String directory, String repo, String ref, String refspec) {
    clone_repo(directory, "rpc-jenkins-svc-github-ssh-key", repo, ref, refspec)
}


/**
 * Merge list of pull requests.
 *
 * Iterate through the list of pull request IDs and merge each change
 * on top of the current HEAD. If a merge fails an exception is raised.
 *
 * @param directory location of the Git repository
 * @param pullRequestIDs ordered list of pull request numbers to merge
 * @return null
 */
void merge_pr_chain(String directory='./', List pullRequestIDs=null){
  println("Attempting to merge the following pull requests onto the base branch: ${pullRequestIDs}.")
  dir(directory){
    for (pullRequestID in pullRequestIDs) {
      println("Merging pull request ${pullRequestID}.")
      sh """#!/bin/bash -xe
        git merge origin/pr/${pullRequestID}/head
      """
    }
  }
  println("Finished merging the pull requests onto the base branch.")
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

/* Used to check whether a pull request only includes changes
 * to files that match the skip regex. If the regex is an
 * empty string, that is treated as matching nothing.
 */
Boolean skip_build_check(String regex) {
  print "Skipping pattern:\n'$regex'"
  if (regex != "") {
    withEnv(["SKIP_REGEX=$regex"]) {
      def rc = sh(
        script: """#!/bin/bash
          set -xeu
          cd ${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}
          git status
          git show --stat=400,400 | awk '/\\|/{print \$1}' \
              |python -c 'import os, re, sys; all(re.search(os.environ["SKIP_REGEX"], line, re.VERBOSE) for line in sys.stdin) and sys.exit(0) or sys.exit(1)'
          """,
          returnStatus: true
      )
      if (rc==0) {
        print "All change files match skip pattern. Skipping..."
        return true
      } else if(rc==1) {
        print "One or more change files not matched by skip pattern. Continuing..."
        return false
      } else if(rc==128) {
        throw new Exception("Directory is not a git repo, cannot compile changes.")
      }
    }
  } else {
    return false
  } // if
}

/* DEPRECATED FUNCTION
 * This function should be removed once it is no longer in use. It remains
 * while still required by old-style jobs and has been replaced in standard
 * jobs by `skip_build_check`.
 */
def is_doc_update_pr(String git_dir) {
  if (env.ghprbPullId != null) {
    dir(git_dir) {
      def rc = sh(
        script: """#!/bin/bash
          set -xeu
          git status
          git show --stat=400,400 | awk '/\\|/{print \$1}' \
            | egrep -v -e '.*md\$' \
                       -e '.*rst\$' \
                       -e '^releasenotes/' \
                       -e '^gating/generate_release_notes/' \
                       -e '^gating/post_merge' \
                       -e '^gating/update_dependencies/'
        """,
        returnStatus: true
      )
      if (rc==0){
        print "Detected a deployment-related change or periodic job execution. Continuing..."
        return false
      }else if(rc==1){
        print "No deployment-related changes were detected. Skipping..."
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
  commits = sh(
    returnStdout: true,
    script: """#!/bin/bash -e
      cd ${repo_path}
      git log --pretty=%B origin/${ghprbTargetBranch}..origin/pr/${ghprbPullId}/merge""")
  print("Looking for Jira issue keys in the following commits: ${commits}")
  try{
    String key = (commits =~ key_regex)[0]
    print ("First Found Jira Issue Key: ${key}")
    return key
  } catch (e){
    throw new Exception("""
No JIRA Issue key were found in commits ${repo_path}:${ghprbSourceBranch}""")
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

// This function creates or updates issues related to build failures.
// Should be used to report build failures by calling:
//    common.build_failure_issue(project, labels)
String build_failure_issue(String project,
                           List labels = []){
  withCredentials([
    usernamePassword(
      credentialsId: "jira_user_pass",
      usernameVariable: "JIRA_USER",
      passwordVariable: "JIRA_PASS"
    )
  ]){
    return sh(script: """#!/bin/bash -xe
      cd ${env.WORKSPACE}
      set +x; . .venv/bin/activate; set -x
      python rpc-gating/scripts/jirautils.py \
        --user '$JIRA_USER' \
        --password '$JIRA_PASS' \
        build_failure_issue \
          --project "${project}" ${generate_label_options(labels)} \
          --job-name "${env.JOB_NAME}" \
          --job-url "${env.JOB_URL}" \
          --build-tag "${env.BUILD_TAG}" \
          --build-url "${env.BUILD_URL}"
    """, returnStdout: true).trim()
  }
}


// Create String of jirautils.py/create_jira_issue options for labels
// eg: --label "jenkins" --label "maas"
@NonCPS
String generate_label_options(List labels){
  List terms = []
  for (label in labels) {
      terms += "--label \"${label}\""
  }
  return terms.join(" ")
}


// This method creates a jira issue. Doesn't check for duplicates or
// do anthing fancy. Use build_failure_issue for build failures.
String create_jira_issue(String project,
                         String summary,
                         String description,
                         List labels = []){
  withCredentials([
    usernamePassword(
      credentialsId: "jira_user_pass",
      usernameVariable: "JIRA_USER",
      passwordVariable: "JIRA_PASS"
    )
  ]){
    return sh (script:"""#!/bin/bash -xe
      cd ${env.WORKSPACE}
      set +x; . .venv/bin/activate; set -x
      python rpc-gating/scripts/jirautils.py \
        --user '$JIRA_USER' \
        --password '$JIRA_PASS' \
        create_issue \
          --summary "${summary}" \
          --description "${description}" \
          --project "${project}" ${generate_label_options(labels)}
    """, returnStdout: true).trim()
  }
}


String get_or_create_jira_issue(String project,
                                String status = "BACKLOG",
                                String summary,
                                String description,
                                List labels = []){
  withCredentials([
    usernamePassword(
      credentialsId: "jira_user_pass",
      usernameVariable: "JIRA_USER",
      passwordVariable: "JIRA_PASS"
    )
  ]){
    return sh (script: """#!/bin/bash -xe
      cd ${env.WORKSPACE}
      set +x; . .venv/bin/activate; set -x
      python rpc-gating/scripts/jirautils.py \
        --user '$JIRA_USER' \
        --password '$JIRA_PASS' \
        get_or_create_issue \
          --status "${status}" \
          --summary "${summary}" \
          --description "${description}" \
          --project "${project}" ${generate_label_options(labels)}
    """, returnStdout: true).trim()
  }
}


List jira_query(String query){
  withCredentials([
    usernamePassword(
      credentialsId: "jira_user_pass",
      usernameVariable: "JIRA_USER",
      passwordVariable: "JIRA_PASS"
    )
  ]){
    return sh (script: """#!/bin/bash -xe
      cd ${env.WORKSPACE}
      set +x; . .venv/bin/activate; set -x
      python rpc-gating/scripts/jirautils.py \
        --user '$JIRA_USER' \
        --password '$JIRA_PASS' \
        query \
          --query "${query}"
    """,
    returnStdout: true).tokenize()
  }
}

List jira_comments(String key){
  withCredentials([
    usernamePassword(
      credentialsId: "jira_user_pass",
      usernameVariable: "JIRA_USER",
      passwordVariable: "JIRA_PASS"
    )
  ]){
    return sh (script: """#!/bin/bash -xe
      cd ${env.WORKSPACE}
      set +x; . .venv/bin/activate; set -x
      python rpc-gating/scripts/jirautils.py \
        --user '$JIRA_USER' \
        --password '$JIRA_PASS' \
        comments \
          --issue "${key}"
    """,
    returnStdout: true).tokenize('\n')
  }
}


void jira_close(String key){
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
      python rpc-gating/scripts/jirautils.py \
        --user '$JIRA_USER' \
        --password '$JIRA_PASS' \
        close \
          --issue "${key}"
    """
  }
}

void jira_close_all(String query, Integer max_issues=30,
                    Boolean allow_all_projects=false){
  allow_all_projects_str = ""
  if (allow_all_projects){
    allow_all_projects_str = "--allow-all-projects"
  }
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
      python rpc-gating/scripts/jirautils.py \
        --user '$JIRA_USER' \
        --password '$JIRA_PASS' \
        close_all \
          --query "${query}" \
          --max-issues ${max_issues} ${allow_all_projects_str}
    """
  }
}

void jira_set_labels(String key, List labels){
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
      python rpc-gating/scripts/jirautils.py \
        --user '$JIRA_USER' \
        --password '$JIRA_PASS' \
        set_labels \
          --issue "${key}" ${generate_label_options(labels)}
    """
  }
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
      if (! env.RPC_GATING_BRANCH){
        env.RPC_GATING_BRANCH="master"
      }
      configure_git()
      clone_with_pr_refs('rpc-gating',
                         'git@github.com:rcbops/rpc-gating',
                         env.RPC_GATING_BRANCH)
      install_ansible()
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

void shared_slave(Closure body){
  use_node("pubcloud_multiuse", body)
}

void internal_slave(Closure body){
  use_node("CentOS", body)
}

void standard_job_slave(String slave_type, Closure body){
  timeout(time: 8, unit: 'HOURS'){
    if (slave_type == "instance"){
      pubcloud.runonpubcloud(){
        body()
      }
    } else if (slave_type.startsWith("nodepool-")){
      use_node(slave_type){
        body();
      }
    } else if (slave_type == "container"){
      String image_name = env.BUILD_TAG.toLowerCase()
      String dockerfile_repo_dir = "${env.WORKSPACE}/"

      if (env.SLAVE_CONTAINER_DOCKERFILE_REPO == "PROJECT") {
        if ( env.ghprbPullId != null ) {
          clone_with_pr_refs(
            "${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}",
          )
        } else {
          clone_with_pr_refs(
            "${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}",
            env.REPO_URL,
            env.BRANCH,
          )
        }

        dockerfile_repo_dir += env.RE_JOB_REPO_NAME
      } else if (env.SLAVE_CONTAINER_DOCKERFILE_REPO == "RE") {
        dockerfile_repo_dir += "rpc-gating"
      } else {
        throw new Exception(
          "SLAVE_CONTAINER_DOCKERFILE_REPO '${env.SLAVE_CONTAINER_DOCKERFILE_REPO}' is not supported."
        )
      }

      dir(dockerfile_repo_dir){
        container = docker.build(image_name, genDockerBuildArgs())
      }
      container.inside {
        configure_git()
        body()
      }
    } else {
      throw new Exception("slave_type '$slave_type' is not supported.")
    }
  }
}


void connect_phobos_vpn(String gateway=null){
  withRequestedCredentials("phobos_vpn_auth_creds"){
    dir('rpc-gating/playbooks'){
      if (gateway == null){
        gateway = env.gw_from_creds
      }
      venvPlaybook(
        playbooks: [
          "phobos_vpn.yml"
        ],
        args: [
          "-u root"
        ],
        vars: [
          ipsec_id: env.ipsec_id,
          ipsec_secret: env.ipsec_secret,
          xauth_user: env.xauth_user,
          xauth_pass: env.xauth_pass,
          gateway: gateway
        ]
      ) //venvPlaybook
    } // dir
  } // withRequestedCredentials
}

// Build an array suitable for passing to withCredentials
// from a space or comma separated list of credential IDs.
@NonCPS
List build_creds_array(String list_of_cred_ids){
    print("Building credentials array from the following list of IDs: ${list_of_cred_ids}")
    Map creds_bundles = [
      "cloud_creds": [
        'dev_pubcloud_username',
        'dev_pubcloud_api_key',
        'dev_pubcloud_tenant_id',
        'phobos_nodepool_auth_url',
        'phobos_nodepool_project_name',
        'phobos_nodepool_project_id',
        'phobos_nodepool_username',
        'phobos_nodepool_password',
        'phobos_nodepool_user_domain_name'
      ],
      "rpc_asc_creds": [
        'RPC_ASC_QTEST_API_TOKEN'
      ],
      "rpc_ri_creds": [
        'RPC_RI_APPFORMIX_CF_ACCOUNT'
      ],
      "rpc_repo": [
        'RPC_REPO_IP',
        'RPC_REPO_SSH_USERNAME_TEXT',
        'RPC_REPO_SSH_USER_PRIVATE_KEY_FILE',
        'RPC_REPO_SSH_HOST_PUBLIC_KEY_FILE',
        'RPC_REPO_GPG_SECRET_KEY_FILE',
        'RPC_REPO_GPG_PUBLIC_KEY_FILE'
      ],
      "phobos_embedded": [
        'phobos_clouds_rpc_jenkins_user',
        'id_rsa_cloud10_jenkins_file',
        'rackspace_ca_crt'
      ],
      "phobos_vpn_auth_creds": [
        "phobos_vpn_ipsec",
        "phobos_vpn_xauth",
        "phobos_vpn_gateway"
      ],
      "jenkins_ssh_privkey": [
        'id_rsa_cloud10_jenkins_file',
      ],
      "jenkins_api_creds": [
        'service_account_jenkins_api_creds'
      ]
    ]
    // only needs to contain creds that should be exposed.
    // every cred added should also be documented in RE for Projects
    Map available_creds = [
      "dev_pubcloud_username": string(
        credentialsId: "dev_pubcloud_username",
        variable: "PUBCLOUD_USERNAME"
      ),
      "dev_pubcloud_api_key": string(
        credentialsId: "dev_pubcloud_api_key",
        variable: "PUBCLOUD_API_KEY"
      ),
      "dev_pubcloud_tenant_id": string(
        credentialsId: "dev_pubcloud_tenant_id",
        variable: "PUBCLOUD_TENANT_ID"
      ),
      "RE_GRAFANA_ADMIN_PASSWORD": string(
        credentialsId: "RE_GRAFANA_ADMIN_PASSWORD",
        variable: "RE_GRAFANA_ADMIN_PASSWORD"
      ),
      "RE_GRAFANA_GRAFYAML_API_KEY": string(
        credentialsId: "RE_GRAFANA_GRAFYAML_API_KEY",
        variable: "RE_GRAFANA_GRAFYAML_API_KEY"
      ),
      "RE_GRAPHITE_ADMIN_PASSWORD": string(
        credentialsId: "RE_GRAPHITE_ADMIN_PASSWORD",
        variable: "RE_GRAPHITE_ADMIN_PASSWORD"
      ),
      "RE_GRAPHITE_SECRET_KEY": string(
        credentialsId: "RE_GRAPHITE_SECRET_KEY",
        variable: "RE_GRAPHITE_SECRET_KEY"
      ),
      "RPC_ASC_QTEST_API_TOKEN": string(
        credentialsId: "RPC_ASC_QTEST_API_TOKEN",
        variable: "RPC_ASC_QTEST_API_TOKEN"
      ),
      "RPC_RI_APPFORMIX_CF_ACCOUNT": usernamePassword(
        credentialsId: "RPC_RI_APPFORMIX_CF_ACCOUNT",
        usernameVariable: "RPC_RI_APPFORMIX_CF_USERNAME",
        passwordVariable: "RPC_RI_APPFORMIX_CF_PASSWORD"
      ),
      "id_rsa_cloud10_jenkins_file": file(
        credentialsId: 'id_rsa_cloud10_jenkins_file',
        variable: 'JENKINS_SSH_PRIVKEY'
      ),
      "RPC_REPO_IP": string(
        credentialsId: "RPC_REPO_IP",
        variable: "REPO_HOST"
      ),
      "RPC_REPO_SSH_USERNAME_TEXT": string(
        credentialsId: "RPC_REPO_SSH_USERNAME_TEXT",
        variable: "REPO_USER"
      ),
      "RPC_REPO_SSH_USER_PRIVATE_KEY_FILE": file(
        credentialsId: "RPC_REPO_SSH_USER_PRIVATE_KEY_FILE",
        variable: "REPO_USER_KEY"
      ),
      "RPC_REPO_SSH_HOST_PUBLIC_KEY_FILE": file(
        credentialsId: "RPC_REPO_SSH_HOST_PUBLIC_KEY_FILE",
        variable: "REPO_HOST_PUBKEY"
      ),
      "RPC_REPO_GPG_SECRET_KEY_FILE": file(
        credentialsId: "RPC_REPO_GPG_SECRET_KEY_FILE",
        variable: "GPG_PRIVATE"
      ),
      "RPC_REPO_GPG_PUBLIC_KEY_FILE": file(
        credentialsId: "RPC_REPO_GPG_PUBLIC_KEY_FILE",
        variable: "GPG_PUBLIC"
      ),
      "phobos_clouds_rpc_jenkins_user": file(
        credentialsId: "phobos_clouds_rpc_jenkins_user",
        variable: "phobos_clouds_rpc_jenkins_user"
      ),
      "rackspace_ca_crt": file(
        credentialsId: "rackspace_ca_crt",
        variable: "rackspace_ca_crt"
      ),
      "phobos_vpn_ipsec": usernamePassword(
        credentialsId: "phobos_vpn_ipsec",
        usernameVariable: "ipsec_id",
        passwordVariable: "ipsec_secret"
      ),
      "phobos_vpn_xauth": usernamePassword(
        credentialsId: "phobos_vpn_xauth",
        usernameVariable: "xauth_user",
        passwordVariable: "xauth_pass"
      ),
      "phobos_vpn_gateway": string(
        credentialsId: 'phobos_vpn_gateway',
        variable: 'gw_from_creds'
      ),
      "phobos_nodepool_auth_url": string(
        credentialsId: "phobos_nodepool_auth_url",
        variable: "PHOBOS_NODEPOOL_AUTH_URL"
      ),
      "phobos_nodepool_project_name": string(
        credentialsId: "phobos_nodepool_project_name",
        variable: "PHOBOS_NODEPOOL_PROJECT_NAME"
      ),
      "phobos_nodepool_project_id": string(
        credentialsId: "phobos_nodepool_project_id",
        variable: "PHOBOS_NODEPOOL_PROJECT_ID"
      ),
      "phobos_nodepool_username": string(
        credentialsId: "phobos_nodepool_username",
        variable: "PHOBOS_NODEPOOL_USERNAME"
      ),
      "phobos_nodepool_password": string(
        credentialsId: "phobos_nodepool_password",
        variable: "PHOBOS_NODEPOOL_PASSWORD"
      ),
      "phobos_nodepool_user_domain_name": string(
        credentialsId: "phobos_nodepool_user_domain_name",
        variable: "PHOBOS_NODEPOOL_USER_DOMAIN_NAME"
      ),
      "service_account_jenkins_api_creds": usernamePassword(
        credentialsId: "service_account_jenkins_api_creds",
        usernameVariable: "JENKINS_USERNAME",
        passwordVariable: "JENKINS_API_KEY"
      )
    ]

    // split string into list, reject empty items.
    List requested_creds = list_of_cred_ids.split(/[, ]+/).findAll({
      it.size() > 0
    })

    // check for invalid values
    List invalid = requested_creds - (creds_bundles.keySet()
                                      + available_creds.keySet())
    if (invalid != []){
      throw new Exception("Attempt to use unknown credential(s): ${invalid}")
    }
    // expand bundles into sublists, then flatten the list
    List requested_bundle_expanded = requested_creds.collect({
      creds_bundles[it] ?: it
    }).flatten()
    print ("Expanded Credentials: ${requested_bundle_expanded}")
    // convert list of ids to list of objects
    List creds_array = requested_bundle_expanded.collect({
      available_creds[it]
    })
    print ("Final Credentials Array: ${creds_array}")
    return creds_array
}

// Supply credentials to a closure. Similar to withCredentials except
// that this function takes a string containing a list of credential IDs
// instead of an array of credentials objects. This is so that a string can
// be used in a JJB Project to request credentials.
void withRequestedCredentials(String list_of_cred_ids, Closure body){
  List creds = build_creds_array(list_of_cred_ids)
  withCredentials(creds){
    body()
  }
}

Cause getRootCause(Cause cause){
    if (cause.class.toString().contains("UpstreamCause")) {
         for (upCause in cause.upstreamCauses) {
             return getRootCause(upCause)
         }
     } else {
        return cause
     }
}

void setTriggerVars(){
  List causes = currentBuild.rawBuild.getCauses()
  Cause root_cause = getRootCause(causes[0])
  env.RE_JOB_TRIGGER_DETAIL="No detail available"
  if (root_cause instanceof Cause.UpstreamCause){
      env.RE_JOB_TRIGGER="UPSTREAM"
      env.RE_JOB_TRIGGER_DETAIL = "${root_cause.getUpstreamProject()}/${root_cause.getUpstreamBuild()}"
  } else if (root_cause instanceof Cause.UserIdCause){
      env.RE_JOB_TRIGGER = "USER"
      env.RE_JOB_TRIGGER_DETAIL = root_cause.getUserName()
  } else if (root_cause instanceof hudson.triggers.TimerTrigger.TimerTriggerCause) {
      env.RE_JOB_TRIGGER = "TIMER"
  } else if (root_cause instanceof com.cloudbees.jenkins.GitHubPushCause){
      env.RE_JOB_TRIGGER = "PUSH"
      env.RE_JOB_TRIGGER_DETAIL = root_cause.getShortDescription()
  } else if (root_cause instanceof org.jenkinsci.plugins.ghprb.GhprbCause){
      env.RE_JOB_TRIGGER="PULL"
      env.RE_JOB_TRIGGER_DETAIL = "${root_cause.title}/${root_cause.pullID}"
  } else {
      env.RE_JOB_TRIGGER="OTHER"
  }
  print ("Trigger: ${env.RE_JOB_TRIGGER} (${env.RE_JOB_TRIGGER_DETAIL})")
}

// convert a comma separated list of wrapper names
// into a list of functions for use with wrapList
List stdJobWrappers(String wrappers){
  Map availableWrappers = [
    "phobos_vpn": {body -> connect_phobos_vpn(); body()}
  ]
  // Convert csv list of strings to list of wrapper functions
  // via the above map
  List wrapperFuncs = []
  for (String wrapperName in wrappers.tokenize(", ")){
    Closure c = availableWrappers[wrapperName]
    if (c == null){
      throw new Exception("Invalid Standard Job Wrapper: ${wrapperName}")
    } else {
      wrapperFuncs << c
    }
  }
  return wrapperFuncs
}

// nest a load of functions from a list
// wraplist([a,b,c]){ print "body" } is equivalent to:
// a {
//   b {
//     c {
//       print "body"
//     }
//   }
// }
void wrapList(List wrappers, Closure body){
  // if the list of wrappers is empty, just
  // execute the passed in closure (nothing to wrap)
  if (wrappers.empty){
    body()
  } else {
    // wrappers is not empty, lets get wrapping

    // count down from through the wrappers list
    for (i in wrappers.size()..1){
        // define an alias for the loop counter
        // within the scope of the loop, so this
        // value will be captured by closures
        // created within the loop
        def _i = i

        // b is the closure that will be
        // passed to the next closure to be created
        Closure b

        // On the first iteration use, the actual
        // body closure that was passed into this function.
        // On subsequent iterations use the previously
        // created closure.
        if (_i == wrappers.size()){
            b = body
        } else {
            b = c
        }
        // create a closure
        // this closure calls one of the functions from the
        // passed in function list, either with the passed
        // in body closure as the argument, or with the
        // previously created closure as the argument.
        c = {wrappers[_i-1](b)}
    }
    // execute the nest of closures
    // this will look something like:
    // {wrappers[0]({wrappers[1]({wrappers[2]({print "body"})})})}
    c()
  }
}

// add wrappers that should be used for all jobs.
// max log size is in MB
void globalWraps(Closure body){
  // global timeout is long, so individual jobs can set shorter timeouts and
  // still have to cleanup, archive atefacts etc.
  timestamps{
    timeout(time: 10, unit: 'HOURS'){
      shared_slave(){
        wrap([$class: 'LogfilesizecheckerWrapper', 'maxLogSize': 200, 'failBuild': true, 'setOwn': true]) {
          print("common.globalWraps pre body")
          body()
          print("common.globalWraps post body")
        } // log size
      } // shared slave
    } // timeout
  } // timestamps
}

Boolean isUserAbortedBuild() {
  if (currentBuild.rawBuild.getAction(InterruptedBuildAction.class)) {
    userAborted = true
  } else {
    userAborted = false
  }
  return userAborted
}

String genDockerBuildArgs() {
  return sh(script: """#!/usr/bin/env python3
from os import environ
from shlex import quote

# Throw a KeyError if neither are set.
build_args = environ['SLAVE_CONTAINER_DOCKERFILE_BUILD_ARGS']
dockerfile = environ['SLAVE_CONTAINER_DOCKERFILE_PATH']
formatted_args = ""

# We require SLAVE_CONTAINER_DOCKERFILE_PATH to always be set, while
# SLAVE_CONTAINER_DOCKERFILE_BUILD_ARGS can contain an empty string.
if not dockerfile:
    raise Exception("No Dockefile path specified in "
                    "SLAVE_CONTAINER_DOCKERFILE_PATH")

if build_args:
    for arg in build_args.split():
        formatted_args += "--build-arg {} ".format(quote(arg))

formatted_args += '-f {} .'.format(quote(dockerfile))

print(formatted_args)
  """, returnStdout: true).trim()
}

return this

void stdJob(String hook_dir, String credentials, String jira_project_key, String wrappers) {
  globalWraps(){
    // set env.RE_JOB_TRIGGER & env.RE_JOB_TRIGGER_DETAIL
    setTriggerVars()

    standard_job_slave(env.SLAVE_TYPE) {
      wrapList(stdJobWrappers(wrappers)){
        env.RE_HOOK_ARTIFACT_DIR="${env.WORKSPACE}/artifacts"
        env.RE_HOOK_RESULT_DIR="${env.WORKSPACE}/results"

        currentBuild.result="SUCCESS"

        try {
          withRequestedCredentials(credentials) {

            stage('Checkout') {
              if (env.ghprbPullId == null) {
                clone_with_pr_refs(
                  "${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}",
                  env.REPO_URL,
                  env.BRANCH,
                )
                if (env.pullRequestChain) {
                  pullRequestIDs = loadCSV(env.pullRequestChain)
                  merge_pr_chain("${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}", pullRequestIDs)
                }
              } else {
                print("Triggered by PR: ${env.ghprbPullLink}")
                clone_with_pr_refs(
                  "${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}",
                )
              }
            }

            found_hook_dir = findHookDir(hook_dir)

            if (!found_hook_dir) {
              throw new Exception(
                "No valid hook directory found in project's `gating` directory")
            }

            stage('Execute Pre Script') {
              // The 'pre' stage is used to prepare the environment for
              // testing but is not the test itself. Retry on failure
              // to reduce the likelihood of non-test errors.
              retry(3) {
                sh """#!/bin/bash -xeu
                  cd ${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}
                  if [[ -e gating/${found_hook_dir}/pre ]]; then
                    gating/${found_hook_dir}/pre
                  fi
                """
              }
            }

            try{
              stage('Execute Run Script') {
                sh """#!/bin/bash -xeu
                  cd ${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}
                  gating/${found_hook_dir}/run
                """
              }
            } finally {
              stage('Execute Post Script') {
                // We do not want the 'post' execution to fail the test,
                // but we do want to know if it fails so we make it only
                // return status.
                post_result = sh(
                  returnStatus: true,
                  script: """#!/bin/bash -xeu
                             cd ${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}
                             if [[ -e gating/${found_hook_dir}/post ]]; then
                               gating/${found_hook_dir}/post
                             fi"""
                )
                if (post_result != 0) {
                  print("Build failed with return code ${post_result}")
                }
              }
            }
          }
        } catch (e) {
          print(e)
          currentBuild.result="FAILURE"
          if (env.ghprbPullId == null && ! isUserAbortedBuild() && jira_project_key != '') {
            print("Creating build failure issue.")
            def labels = ['post-merge-test-failure', 'jenkins', env.JOB_NAME]
            build_failure_issue(jira_project_key, labels)
          } else {
            print("Skipping build failure issue creation.")
          }
          throw e
        } finally {
            // try-catch within archive_artifacts() prevents
            // this call from failing standard job builds
            archive_artifacts(
              artifacts_dir: "${env.RE_HOOK_ARTIFACT_DIR}",
              results_dir: "${env.RE_HOOK_RESULT_DIR}"
            )
        } // try
      } // stdJobWrappers
    } // standard_job_slave
  } // globalwraps
} //stdJob func

/** Check if the build is a pull request that only modifies files that
 *  do not require testing by matching against skip pattern.
 */
Boolean isSkippable(String skip_pattern, String credentials) {
  Boolean skipIt
  globalWraps(){
    if (env.ghprbPullId == null) {
      skipIt = false
    } else {
      withRequestedCredentials(credentials) {
        stage('Check PR against skip-list') {
          print("Triggered by PR: ${env.ghprbPullLink}")
          clone_with_pr_refs(
            "${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}",
          )
          skipIt = skip_build_check(skip_pattern)
        }
      }
    }
  }
  return skipIt
}

def findHookDir(String hook_dirs) {
  String found_hook_dir = null
  List split_hook_dirs = hook_dirs.split(/,\s?/)

  for (Integer i = 0; i < split_hook_dirs.size(); i++) {
    if (fileExists("${env.WORKSPACE}/${env.RE_JOB_REPO_NAME}/gating/${split_hook_dirs[i]}")) {
      found_hook_dir = split_hook_dirs[i]
      if (i > 0) {
        print(
          "DEPRECATION WARNING: This job is using a hook directory of `gating/${found_hook_dir}`.\n" \
          + "DEPRECATION WARNING: This directory is being removed on 2018-08-24 in favour of `gating/${split_hook_dirs[0]}`.\n" \
          + "DEPRECATION WARNING: Please update your job accordingly!"
        )
      }
      break
    }
  }

  return found_hook_dir
}

void runReleasesPullRequestWorkflow(String baseBranch, String prBranch, String jiraProjectKey){
  def (prType, componentText) = getComponentChange(baseBranch, prBranch)
  def componentYaml = readYaml text: componentText

  if (prType == "release"){
    String REPO_NAME=componentYaml["name"]
    String REPO_URL=componentYaml["repo_url"]
    String SHA=componentYaml["release"]["sha"]
    String MAINLINE=componentYaml["release"]["series"]
    String RC_BRANCH="${MAINLINE}-rc"

    dir(REPO_NAME){
      clone_with_pr_refs(
        "./",
        REPO_URL,
        "master",
      )
      println "=== Checking that the specified SHA exists ==="
      Boolean sha_exists = ! sh(
        script: """#!/bin/bash -xe
          git rev-parse --verify ${SHA}^{commit}
        """,
        returnStatus: true,
      )
      if (! sha_exists) {
        throw new Exception("The supplied SHA ${SHA} was not found.")
      }
      println "=== Checking for the existence of an RC branch ==="
      Boolean has_rc_branch = ! sh(
        script: """#!/bin/bash -xe
          git rev-parse --verify remotes/origin/${RC_BRANCH}
        """,
        returnStatus: true,
      )
      if (has_rc_branch) {
        println "=== Checking that the specified SHA is the tip of RC branch ${RC_BRANCH} ==="
        String latest_sha = sh(
          script: """#!/bin/bash -xe
            git rev-parse --verify remotes/origin/${RC_BRANCH}
          """,
          returnStdout: true,
        ).trim()
        // (NOTE(mattt): We should be releasing the tip of ${RC_BRANCH}, so
        //               if the SHA specified in the release does not match
        //               the tip of ${RC_BRANCH} we throw an exception to
        //               abort the release.
        if (latest_sha != SHA) {
          throw new Exception("The supplied SHA ${SHA} is not the tip on RC branch ${RC_BRANCH}.")
        }
      }

      testRelease(componentText)
      createRelease(componentText, has_rc_branch)
    }
  }else if (type == "registration"){
    registerComponent(componentText, jiraProjectKey)
  }else{
    throw new Exception("The pull request type ${prType} is unsupported.")
  }

  build(
    job: "Merge-Pull-Request",
    wait: false,
    parameters: [
      [
        $class: "StringParameterValue",
        name: "RPC_GATING_BRANCH",
        value: RPC_GATING_BRANCH,
      ],
      [
        $class: "StringParameterValue",
        name: "pr_repo",
        value: ghprbGhRepository,
      ],
      [
        $class: "StringParameterValue",
        name: "pr_number",
        value: ghprbPullId,
      ],
      [
        $class: "StringParameterValue",
        name: "commit",
        value: ghprbActualCommit,
      ],
    ]
  )
}

List getComponentChange(String baseBranch, String prBranch){
  venv = "${WORKSPACE}/.componentvenv"
  sh """#!/bin/bash -xe
      virtualenv --python python3 ${venv}
      set +x; . ${venv}/bin/activate; set -x
      pip install -c '${env.WORKSPACE}/rpc-gating/constraints_rpc_component.txt' rpc_component
  """
  types = ["release", "registration"]
  for (i=0; i < types.size(); i++){
    type = types[i]
    try{
      component_text = sh(
        script: """#!/bin/bash -xe
          set +x; . ${venv}/bin/activate; set -x
          component --releases-dir . compare --from ${baseBranch} --to ${prBranch} --verify ${type}
        """,
        returnStdout: true
      )
      break
    }catch (e){
      println "Pull request is not a ${type}."
      component_text = null
    }
  }
  if (component_text == null) {
    throw new Exception("The pull request does not correspond to any of the known types ${types}.")
  }

  println "Pull request is a ${type}."
  println "=== component CLI standard out ==="
  println component_text

  return [type, component_text]
}

void registerComponent(String component_text, String jiraProjectKey){
  def component = readYaml text: component_text
  createComponentGateTrigger(component["name"], component["repo_url"], jiraProjectKey)
  createComponentSkeleton(component["name"], component["repo_url"], jiraProjectKey)
}

void createComponentSkeleton(String name, String repoUrl, String jiraProjectKey){
  dir("${WORKSPACE}/${name}"){
    clone_with_pr_refs(".", repoUrl.replace("https://github.com/", "git@github.com:"), "origin/master")
    withEnv(
      [
        "ISSUE_SUMMARY=Add component skeleton to ${name}",
        "ISSUE_DESCRIPTION=This issue was generated automatically as part of registering a new component.",
        "LABELS=component-skeleton",
        "JIRA_PROJECT_KEY=${jiraProjectKey}",
        "TARGET_BRANCH=master",
        "COMMIT_TITLE=Add new component gating skeleton",
        "COMMIT_MESSAGE=This project is being added to the RE platform. This change adds the\nbasic structure required to make use of the platform. Please modify\nthis pull request before merging to enable the required support.",
      ]
    ){
      withCredentials(
        [
          string(
            credentialsId: 'rpc-jenkins-svc-github-pat',
            variable: 'PAT'
          ),
          usernamePassword(
            credentialsId: "jira_user_pass",
            usernameVariable: "JIRA_USER",
            passwordVariable: "JIRA_PASS"
          ),
        ]
      ){
        sshagent (credentials:['rpc-jenkins-svc-github-ssh-key']){
          sh """#!/bin/bash -xe
            set +x; . ${WORKSPACE}/.venv/bin/activate; set -x
            ${WORKSPACE}/rpc-gating/scripts/add_component_skeleton.py .
            ${WORKSPACE}/rpc-gating/scripts/commit_and_pull_request.sh
          """
        }
      }
    }
  }
}

void createComponentGateTrigger(String name, String repoUrl, String jiraProjectKey){
  repo_dir = "${WORKSPACE}/rpc-gating"
  filename = "${name}.yml".replace("-", "_")
  projectsFile = "${repo_dir}/rpc_jobs/${filename}"
  withEnv(
    [
      "PROJECTS_FILE=${projectsFile}",
      "REPO_DIR=${repo_dir}",
      "COMPONENT_NAME=${name}",
      "COMPONENT_REPO_URL=${repoUrl}",
      "JIRA_PROJECT_KEY=${jiraProjectKey}",
    ]
  ){
    withCredentials(
      [
        string(
          credentialsId: 'rpc-jenkins-svc-github-pat',
          variable: 'PAT'
        ),
        usernamePassword(
          credentialsId: "jira_user_pass",
          usernameVariable: "JIRA_USER",
          passwordVariable: "JIRA_PASS"
        ),
      ]
    ){
      sshagent (credentials:['rpc-jenkins-svc-github-ssh-key']){
        sh """#!/bin/bash -xe
          set +x; . ${WORKSPACE}/.venv/bin/activate; set -x
          ${WORKSPACE}/rpc-gating/scripts/add_component_gate_trigger_job.sh
        """
      }
    }
  }
}

void testRelease(component_text){
  def component = readYaml text: component_text
  String name = component['name']
  String series = component['release']['series']
  String sha = component['release']['sha']

  allWorkflowJobs = Hudson.instance.getAllItems(WorkflowJob)
  jobs = (allWorkflowJobs.findAll {it.displayName =~ /RELEASE_${name}-${series}/})

  def parallelBuilds = [:]

  // Cannot do for (job in jobNames), see:
  // https://jenkins.io/doc/pipeline/examples/#parallel-multiple-nodes
  for (j in jobs) {
    def job = j
    existingSuccessfulBuild = job.getBuilds().find { build ->
      buildCause = build.getCauses()[0]
      generatedFromSamePullRequest = false
      try{
        upstreamBuild = buildCause.getUpstreamRun()
        if (gate.getPullRequestID(upstreamBuild) == ghprbPullId){
          generatedFromSamePullRequest = true
        }
      } catch (e){
      }
      (
        generatedFromSamePullRequest
        && upstreamBuild.getParent() == currentBuild.rawBuild.getParent()
        && build.isBuilding() == false
        && build.getAction(ParametersAction).getParameter("BRANCH").getValue() == sha
        && build.getResult() == Result.fromString("SUCCESS")
      )
    }
    if (! existingSuccessfulBuild) {
      parallelBuilds[job.displayName] = {
        build(
          job: job.displayName,
          wait: true,
          parameters: [
            [
              $class: 'StringParameterValue',
              name: 'BRANCH',
              value: sha
            ]
          ]
        )
      }
    } else {
      println("Found existing successful build ${existingSuccessfulBuild}, skipping new test.")
    }
  }

  parallel parallelBuilds
}

void createRelease(String component_text, Boolean from_rc_branch){
  build(
    job: "Component-Release",
    wait: true,
    parameters: [
      [
        $class: "StringParameterValue",
        name: "RPC_GATING_BRANCH",
        value: RPC_GATING_BRANCH,
      ],
      [
        $class: "StringParameterValue",
        name: "component_text",
        value: component_text,
      ],
      [
        $class: "StringParameterValue",
        name: "pr_repo",
        value: ghprbGhRepository,
      ],
      [
        $class: "StringParameterValue",
        name: "pr_number",
        value: ghprbPullId,
      ],
      [
        $class: "BooleanParameterValue",
        name: "from_rc_branch",
        value: from_rc_branch,
      ],
    ]
  )
}


List loadCSV(String str){
  str.split(",").collect {it.trim()}
}


String dumpCSV(List l){
  l.join(",")
}
