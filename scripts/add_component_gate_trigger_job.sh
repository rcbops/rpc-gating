#!/bin/bash -xue

cd "${REPO_DIR}"
if [[ ! $(grep -s 'Component-Gate-Trigger_{repo_name}' "${PROJECTS_FILE}") ]] ; then
  cat << EOF >> "${PROJECTS_FILE}"

- project:
    name: "${COMPONENT_NAME}"
    repo_name: "${COMPONENT_NAME}"
    repo_url: "${COMPONENT_REPO_URL}"

    jobs:
      - 'Component-Gate-Trigger_{repo_name}'
EOF

  issue_message="This issue was generated automatically by the Jenkins job ${RE_JOB_NAME}."
  issue_summary="Add new component gate trigger job for ${COMPONENT_NAME}"
  issue=$(python ${WORKSPACE}/rpc-gating/scripts/jirautils.py \
        --user "${JIRA_USER}" \
        --password "${JIRA_PASS}" \
        get_or_create_issue \
          --project "${JIRA_PROJECT_KEY}" \
          --summary "${issue_summary}" \
          --description "${issue_message}" \
          --label COMPONENT_GATE_TRIGGER \
          --label "${COMPONENT_NAME}" \
  )
  echo "Issue: ${issue}"

  title="${issue} Add new component gate trigger job"
  message="A new component, ${COMPONENT_NAME}, has been registered. This change
  adds the component gate trigger job to ensure pull requests can be
  merged."
  git add "${PROJECTS_FILE}"
  git commit -m "${title}" -m "${message}"

  echo "Pushing changes to repo branch"
  pr_branch="${issue}/master/0"
  ssh_url="$(git remote get-url --push origin |sed 's|https://github.com/|git@github.com:|')"
  git push "$ssh_url" "HEAD:${pr_branch}"

  echo "Creating PR"
  owner="$(echo ${ssh_url} | cut -d: -f2 | cut -d/ -f1)"
  repo="$(echo ${ssh_url} | cut -d: -f2 | cut -d/ -f2 | cut -d. -f1)"
  python ${WORKSPACE}/rpc-gating/scripts/ghutils.py \
    --org "$owner" \
    --repo "$repo" \
    --debug \
    create_pr \
      --source-branch "${pr_branch}"\
      --target-branch "master" \
      --title "${title}" \
      --body "${message}"
fi
