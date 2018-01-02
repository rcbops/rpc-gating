# Welcome!

## Repo Layout

 - rpc_jobs: JJB Job definitions
 - pipeline_steps: Groovy Functions for use in pipelines
 - playbooks: Ansible Playbooks
 - scripts: Bash and Python scripts


## Git Hooks
 Run githooks.sh to add the lint.sh script as a pre-commit hook. This will
 do a basic syntax check before each commit. Note that unavailable tools will
 be skipped so for this to be useful you need to have the following tools installed:
   - groovy (apt/groovy2)
   - jenkins-jobs (pip/jenkins-job-builder)
   - ansible-playbook (pip/ansible)


# Conventions
## Naming
### Files
- Use `_` as the word delimiter
- All lowercase
- Examples:
  - `jjb_setup.yml`
  - `pipeline_steps`

### Jobs
- Use `-` as the word delimiter
- Use `_` between a job name and the [job template variables](https://docs.openstack.org/infra/jenkins-job-builder/definition.html#job-template)
- Use standard capitalization rules, template variables can be an exception to this
- Examples:
  - `RPC-AIO_{series}-{image}-{action}-{scenario}-{ztrigger}`
  - `RPC-AIO_master-xenial-deploy-swift-periodic`
  - `Merge-Trigger-JJB`


## Required Properties
### Retention Policy
Every Job must have a retention policy either based on days or number of builds.

Example job with number of builds retention policy:
```
  - job:
      properties:
        - build-discarder:
            num-to-keep: 30
```
Example job with number of days retention policy:
```
  - job:
      properties:
        - build-discarder:
            days-to-keep: 30
```
