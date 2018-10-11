#!/bin/bash -xe
# Run pre and run hooks
# Detect whether the repo has been modified
# Create pr if required
# Run post hook
run_hook(){
  if [[ ${THIRD_PARTY_DEPENDENCIES_UPDATE} == "true" ]]
  then
    if [[ -x "$1" ]]
    then
      $1 || {
        echo "Hook script failed: $1"
        exit 1
      }
    else
      if [[ "$1" =~ .*/run ]]
      then
        echo "Run hook $1 not found."
        exit 1
      fi
    fi
  fi
}
cd ${WORKSPACE}/repo

start_sha=$(git rev-parse --verify HEAD)

if [[ "${COMPONENT_DEPENDENCIES_UPDATE}" == 'true' ]]; then
  apt-get update
  apt-get install -y python3-pip
  pip3 install -c "${WORKSPACE}/rpc-gating/constraints_rpc_component.txt" rpc_component
  component constraints update-requirements
  if [[ ${start_sha} == $(git rev-parse --verify HEAD) ]]; then
    echo "No component constraints updates found."
  fi
fi

# this is probably wrong, but not sure where to put my tox run stuff
run_hook "gating/update_dependencies/pre"
run_hook "gating/update_dependencies/run"

if [[ "${READ_ONLY_TEST:-true}" == "true" ]]; then
  echo "Read only test complete."
  exit 0
fi

# split URL into array separated by '/'s
IFS='/ ' read -r -a url_split <<< "${REPO_URL}"
# owner/org is whatever is between the second and third / in the url
owner="${url_split[3]}"
# repo is anything after owner/
repo=${REPO_URL#https://github.com/${owner}/}
ssh_url="git@github.com:${owner}/${repo}"

if [[ -n "$(git status -s)" ]] || [[ ${start_sha} != $(git rev-parse --verify HEAD) ]]; then
  echo "Looking for Jira issue, will create if not found."
  issue_message="This issue was generated automatically by the Jenkins job ${RE_JOB_NAME}.
  See update_constraints in https://rpc-openstack.atlassian.net/wiki/spaces/RE/pages/19005457/RE+for+Projects for more details."
  jira_summary="Update ${repo}:${BRANCH} constraints"
  issue=$(python ${WORKSPACE}/rpc-gating/scripts/jirautils.py \
        --user "${JIRA_USER}" \
        --password "${JIRA_PASS}" \
        get_or_create_issue \
          --project "${JIRA_PROJECT_KEY}" \
          --summary "${jira_summary}" \
          --description "${issue_message}" \
          --label RE_DEP_UPDATE \
          --label jenkins \
          --label "${repo}_${BRANCH}"
  )
  echo "Issue: ${issue}"
fi

if [[ -z "$(git status -s)" ]]
then
  echo "Repo is clean, prep script made no changes to be committed."
else
  echo "Repo is dirty, committing changes."
  title="${issue} Update ${BRANCH} constraints"
  message="This change is the result of running gating/update_constraints/*
  See update_constraints in https://rpc-openstack.atlassian.net/wiki/spaces/RE/pages/19005457/RE+for+Projects for more details."
  git commit -a -m "${title}" -m "${message}"
fi

if [[ ${start_sha} != $(git rev-parse --verify HEAD) ]]; then
  echo "Pushing changes to repo branch"
  pr_branch="${issue}_${BRANCH}_constraints_update"
  git push -f "$ssh_url" "HEAD:${pr_branch}"

  # Create PR from pushed commit.
  # This will not create a new PR if one already exists.
  echo "Creating PR"
  python ${WORKSPACE}/rpc-gating/scripts/ghutils.py \
    --org "$owner" \
    --repo "$repo" \
    --debug \
    create_pr \
      --source-branch "${pr_branch}"\
      --target-branch "${BRANCH}" \
      --title "${issue} Update ${BRANCH} constraints" \
      --body "Automated constraint update pull request, see individual commits for details."
else
  echo "No pull request created, update scripts did not discover any constraint changes."
fi

run_hook "gating/update_constraints/post"
