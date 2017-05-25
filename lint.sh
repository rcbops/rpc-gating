#!/bin/bash

rc=0
venv=.lintvenv
# exclude venv and git dir from linting
fargs=(. -not -path \*${venv}\* -not -path \*.git\* -not -path \*geerlingguy\*)

trap cleanup EXIT
cleanup(){
  type -t deactivate >/dev/null && deactivate
}
install(){
  which virtualenv >/dev/null \
    || { echo "virtualenv not available, please install via pip"; return; }
  if [ ! -d $venv ]; then
    virtualenv $venv
  fi
  . $venv/bin/activate
  pip install -c constraints.txt -r test-requirements.txt >/dev/null
}

check_jjb(){
  which jenkins-jobs >/dev/null \
    || { echo "jenkins-jobs unavailble, please install jenkins-job-builder from pip"
         return
       }
  jenkins-jobs test -r rpc_jobs >/dev/null \
    && echo "JJB Syntax ok" \
    || { echo "JJB Syntax fail"; rc=1; }
}

# This pulls job.dsl from jjb templates so they can be checked for groovy syntax
extract_groovy_from_jjb(){
  mkdir -p tmp_groovy
  for jjbfile in rpc_jobs/*.yml
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
  groovy scripts/syntax.groovy pipeline_steps/*.groovy tmp_groovy/*.groovy

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
  ansible-galaxy install -r role_requirements.yml -p playbooks/roles
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

check_python(){
  flake8 --exclude=.lintvenv . \
    && echo "Python syntax ok" \
    || { echo "Python syntax fail"; rc=1; }
}

[[ ${RPC_GATING_LINT_USE_VENV:-yes} == yes ]] && install
check_jjb
check_groovy
check_ansible
check_bash
check_python

if [[ $rc == 0 ]]
then
  echo -e "\n**********************\nAll syntax checks passed :)\n**********************\n"
else
  echo -e "\n----------------------\nSyntax check fail :(\n----------------------\n"
fi

exit $rc
