#!/bin/bash

rc=0
venv=.lintvenv
# exclude venv and git dir from linting
fargs=(. -not -path \*${venv}\* -not -path \*.git\*)
current_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"
JJB_PATHS_OVERRIDE=${JJB_PATHS_OVERRIDE:-}

trap cleanup EXIT
cleanup(){
  type -t deactivate >/dev/null && deactivate
  rm -f lint_jjb.ini
}
install(){
  python=${1:-python3}
  which virtualenv >/dev/null \
    || { echo "virtualenv not available, please install via pip"; return; }
  if [ ! -d ${venv}_${python} ]; then
    $python -m virtualenv ${venv}_${python}
  fi
  . ${venv}_${python}/bin/activate

  # find homebrew libs on macOS
  [[ "$OSTYPE" =~ "darwin" ]] && export CFLAGS="-I/usr/local/include -L/usr/local/lib"

  ${venv}_${python}/bin/${python} -m pip install -c constraints.txt -r test-requirements.txt  >/dev/null \
    || {
      echo "Failed to create venv for $python"
      exit 1
    }
}

create_jjb_ini(){
  # work around for ip6 issues in docker image used for gating UG-652
  echo -e "[jenkins]\nurl=http://127.0.0.1:8080" > lint_jjb.ini
  if [[ ${JJB_PATHS_OVERRIDE} == "" ]]; then
    job_sources_from_defaults=$(python -c 'import yaml; defaults = yaml.load(open("rpc_jobs/defaults.yml")); print(" ".join("--job-source {r};{c}".format(r=s["repo"], c=s["commitish"]) for s in yaml.load(defaults[0]["defaults"]["JOB_SOURCES"])))')
    jjb_paths=$(scripts/jjb-path-setup.py ${job_sources_from_defaults} --job-source=${current_dir})
  else
    jjb_paths=${JJB_PATHS_OVERRIDE}
  fi
}

# Check JJB for syntax
check_jjb(){
  which jenkins-jobs >/dev/null \
    || { echo "jenkins-jobs unavailble, please install jenkins-job-builder from pip"
         return
       }
  jenkins-jobs --conf lint_jjb.ini test -r "${jjb_paths}" >/dev/null \
    && echo "JJB Syntax ok" \
    || { echo "JJB Syntax fail"; rc=1; }
}

# This pulls job.dsl from jjb templates so they can be checked for groovy syntax
extract_groovy_from_jjb(){
  mkdir -p tmp_groovy
  for jjbfile in $(find rpc_jobs -iname \*.yml)
  do
    scripts/extract_dsl.py --jjbfile "${jjbfile}" --outdir tmp_groovy
  done
}

check_groovy(){
  which groovy > /dev/null \
    || { echo "groovy unavailble, please install groovy (apt:groovy2 brew:groovy)"
         return
       }
  extract_groovy_from_jjb
  groovy -classpath src \
    scripts/lint_support_groovy/syntax.groovy \
    scripts/lint_support_groovy/*.groovy \
    pipeline_steps/*.groovy \
    tmp_groovy/*.groovy \
    job_dsl/*.groovy

  if [[ $? == 0 ]]
  then
    echo "Groovy syntax ok"
  else
    echo "Groovy syntax fail"
    rc=1
  fi
  rm -rf tmp_groovy
}

check_ansible(){
  which ansible-playbook >/dev/null \
    || { echo "ansible-playbook unavailable, please install ansible from pip"
         return
       }
  mkdir -p playbooks/roles
  find . -name "role_requirements.yml" -exec ansible-galaxy install -p playbooks/roles -r "{}" \;
  ansible-playbook --syntax-check playbooks/*.yml \
    && echo "Playbook Syntax OK" \
    || { echo "Playbook syntax fail"; rc=1; }
}

check_bash(){
  while read script
  do
    bash -n $script \
      && echo "Bash syntax ok: $script" \
      || { echo "Bash syntax fail $script"; rc=1; }
  done < <(find ${fargs[@]} -iname \*.sh)
}

check_jjb_lint() {
  # Check JJB for internal standards:
  #   * naming conventions
  #   * retention policy
  # Limits lint to only gating unique files
  # Excludes webhooktranslator as it has additional packaging
  # and its own tox.
  # Excludes NonCPS.groovy as this filename is required, but
  # not matching rpc-gating conventions
  dirs_to_lint="pipeline_steps,rpc_jobs"
  exclude_files="NonCPS.groovy,venv,.venv,.lintvenv_python3,.lintvenv_python2.7"
  python scripts/lint_jjb.py \
    --dirs ${dirs_to_lint} --exclude-files ${exclude_files} \
    && echo "JJB Lint: OK" \
    || { echo "JJB Lint: FAIL"; rc=1; }
}

check_python(){
  python="${1:-python3}"
  . ${venv}_${python}/bin/activate
  #D = doc
  #W503=line break before binary operator
  ignore="D100,D101,D102,D103,D105,W503"

  echo "$python lint"
  ${venv}_${python}/bin/${python} \
    -m flake8 \
    --ignore="${ignore}"\
    --exclude=.venv,venv,.lintvenv_*,webhooktranslator,playbooks/roles,ansible_v2_3_2_0_1_contrib_inventory_openstack.py . \
    && echo "$python syntax ok" \
    || { echo "$python syntax fail"; rc=1; }
}

check_webhooktranslator(){
    pushd webhooktranslator
    # A temporary workdir is created to workaround an issue where the absolute path to the
    # Python virtual environment, created by tox, is too long and so causing pip to fail.
    # https://rpc-openstack.atlassian.net/browse/RE-53
    tmp_webhook_tox_dir=$(mktemp -d)
    tox --workdir $tmp_webhook_tox_dir && echo "Webhook Translator Unit tests pass" \
      || { echo "Webhook Translator Unit tests fail"; rc=1; }
    rm -r $tmp_webhook_tox_dir
    popd
}

check_jenkins_name_lengths(){
  # The interpreter line in virtualenv scripts will look like this:
  #
  # #!/var/lib/jenkins/workspace/OnMetal-Multi-Node-AIO_newton141-trusty-leapfrogupgrade-small-periodic@3/.venv/bin/python
  #
  # We can break that down as follows:
  #
  # #!/var/lib/jenkins/workspace/ => 30 chars
  # @3/.venv/bin/python           => 20 chars
  #
  # Since the interpreter line cannot exceed 127 chars, that means job names cannot be more than 77 chars.

  max_chars=77
  too_long=0

  which jenkins-jobs >/dev/null \
    || { echo "jenkins-jobs unavailble, please install jenkins-job-builder from pip"
         return
       }
  jobs=$(jenkins-jobs --conf lint_jjb.ini test -r "${jjb_paths}" 2>&1 | grep "INFO:jenkins_jobs.builder:Job name:" | awk '{print $NF}')

  echo -e "\n\n** Scanning for job names for those that are longer than ${max_chars} characters ... **"

  if [[ -z $jobs ]]; then
    echo -e "** We expected at least one job to exist, but found none. Please investigate! **\n\n"
    rc=1
  else
    for job in $jobs; do
      length=${#job}
      if [[ $length -gt $max_chars ]]; then
        echo -e "${length}\t${job}"
        rc=1
        too_long=$((${too_long}+1))
      fi
    done
    echo -e "** ${too_long} problematic job name(s) found! **\n\n"
  fi
}

#create the venvs`
install python3
install python2.7

# create test only jjb config
create_jjb_ini

# run the checks
if [[ "${CHECK_JENKINS_ONLY:-false}" == "true" ]]; then
  check_jjb
  check_jenkins_name_lengths
  check_groovy
  check_jjb_lint
else
  check_jjb
  check_jenkins_name_lengths
  check_groovy
  check_ansible
  check_bash
  check_jjb_lint
  check_webhooktranslator
  check_python python3
  check_python python2.7
fi

if [[ $rc == 0 ]]
then
  echo -e "\n**********************\nAll syntax checks passed :)\n**********************\n"
else
  echo -e "\n----------------------\nSyntax check fail :(\n----------------------\n"
fi

exit $rc
