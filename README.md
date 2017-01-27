# Welcome!

## Repo Layout

 - rpc-jobs: JJB Job definitions
 - pipeline-steps: Groovy Functions for use in pipelines
 - playbooks: Ansible Playbooks
 - scripts: Bash scripts


## Git Hooks
 Run githooks.sh to add the lint.sh script as a pre-commit hook. This will
 do a basic syntax check before each commit. Note that unavailable tools will
 be skipped so for this to be useful you need to have the following tools installed:
   - groovy (apt/groovy2)
   - jenkins-jobs (pip/jenkins-job-builder)
   - ansible-playbook (pip/ansible)
