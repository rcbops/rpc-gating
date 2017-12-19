#!/bin/bash -xe
# Run pre and run hooks
# Detect whether the repo has been modified
# Create pr if required
# Run post hook
run_hook(){
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
}
cd ${WORKSPACE}/repo
run_hook "gating/update_dependencies/pre"
run_hook "gating/update_dependencies/run"

if [[ "${READ_ONLY_TEST:-true}" == "true" ]]; then
  echo "Read only test complete."
  exit 0
fi

if [[ -z "$(git status -s)" ]]
then
  echo "Repo is clean, prep script made no changes to be committed."
else
  echo "Repo is dirty, preparing to propose changes."
  # split URL into array separated by '/'s
  IFS='/ ' read -r -a url_split <<< "$URL"
  # owner/org is whatever is between the second and third / in the url
  owner="${url_split[3]}"
  # repo is anything after owner/
  repo=${URL#https://github.com/${owner}/}
  ssh_url="git@github.com:${owner}/${repo}"
  message="This change is the result of running gating/update_dependencies/*
  See update_dependencies in https://rpc-openstack.atlassian.net/wiki/spaces/RE/pages/19005457/RE+for+Projects for more details."

  # Get / Create Jira issue
  echo "Looking for Jira issue, will create if not found."
  jira_summary="Update ${repo}:${BRANCH} dependencies"
  issue=$(python ${WORKSPACE}/rpc-gating/scripts/jirautils.py \
        --user "${JIRA_USER}" \
        --password "${JIRA_PASS}" \
        get_or_create_issue \
          --project "${JIRA_PROJECT_KEY}" \
          --summary "${jira_summary}" \
          --description "${message}" \
          --label RE_DEP_UPDATE \
          --label jenkins \
          --label "${repo}_${branch}"
  )
  echo "Issue: ${issue}"

  echo "Committing changes"
  pr_branch="${issue}_${BRANCH}_dep_update"
  title="${issue} Update ${BRANCH} dependencies"
  git commit -a -m "${title}" -m "${message}"

  echo "Pushing changes to repo branch"
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
      --title "${title}" \
      --body "${message}"
fi

run_hook "gating/update_dependencies/post"
