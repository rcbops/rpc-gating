import groovy.json.JsonSlurperClassic
import groovy.json.JsonOutput

// Install ansible on a jenkins slave
def install_ansible(){
  sh """
    #!/bin/bash
    which scl && source /opt/rh/python27/enable
    if [[ ! -d ".venv" ]]; then
        virtualenv --python=/opt/rh/python27/root/usr/bin/python .venv
    fi
    # hack the selinux module into the venv
    cp -r /usr/lib64/python2.6/site-packages/selinux .venv/lib64/python2.7/site-packages/
    source .venv/bin/activate

    # These pip commands cannot be combined into one.
    pip install -U six packaging appdirs
    pip install -U setuptools pip
    pip install -U ansible pyrax
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
 *  venv: path to venv to activate before runing ansible-playbook.
 */
def venvPlaybook(Map args){
  withEnv(['ANSIBLE_FORCE_COLOR=true',
           'ANSIBLE_HOST_KEY_CHECKING=False']){
    ansiColor('xterm'){
      if (!('vars' in args)){
        args.vars=[:]
      }
      if (!('args' in args)){
        args.args=[]
      }
      if (!('venv' in args)){
        args.venv = ".venv"
      }
      for (i=0; i<args.playbooks.size(); i++){
        playbook = args.playbooks[i]
        vars_file="vars.${playbook}"
        write_json(file: vars_file, obj: args.vars)
        sh """
          which scl && source /opt/rh/python27/enable
          . ${args.venv}/bin/activate
          ansible-playbook -vvv --ssh-extra-args="-o  UserKnownHostsFile=/dev/null" ${args.args.join(' ')} -e@${vars_file} ${playbook}
        """
      } //for
    } //color
  } //withenv
} //venvplaybook

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
  for (i=0; i<words.size(); i++){
    acronym += words[i][0]
  }
  return acronym
}

def gen_instance_name(){
  if (env.INSTANCE_NAME == "AUTO"){
    job_name_acronym = common.acronym(string: env.JOB_NAME)
    instance_name = "${job_name_acronym}-${env.BUILD_NUMBER}"
  }
  else {
    instance_name = env.INSTANCE_NAME
  }
  print "Instance_name: ${instance_name}"
  return instance_name
}

return this
