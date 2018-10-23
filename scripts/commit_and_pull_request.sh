#!/bin/bash -xue

labels=""
for l in ${LABELS}
  do labels="${labels} --label ${l}"
done

issue=$(python ${WORKSPACE}/rpc-gating/scripts/jirautils.py \
      --user "${JIRA_USER}" \
      --password "${JIRA_PASS}" \
      get_or_create_issue \
        --project "${JIRA_PROJECT_KEY}" \
        --summary "${ISSUE_SUMMARY}" \
        --description "${ISSUE_DESCRIPTION}" \
        ${labels}
)
echo "Issue: ${issue}"

orig_branch="$(git rev-parse --abbrev-ref HEAD)"
pr_branch="${issue}/${TARGET_BRANCH}/0"
git checkout -b "${pr_branch}" "origin/${TARGET_BRANCH}"

message="${COMMIT_MESSAGE}"$'\n'"JIRA: ${issue}"
git add -A
git commit -m "${COMMIT_TITLE}" -m "${message}"

echo "Pushing changes to repo branch"
ssh_url="$(git remote get-url --push origin | sed 's|https://github.com/|git@github.com:|')"
git push -f "$ssh_url" "${pr_branch}"

git checkout "${orig_branch}"

echo "Creating PR"
owner="$(echo ${ssh_url} | cut -d: -f2 | cut -d/ -f1)"
repo="$(echo ${ssh_url} | cut -d: -f2 | cut -d/ -f2 | cut -d. -f1)"
python ${WORKSPACE}/rpc-gating/scripts/ghutils.py \
  --org "${owner}" \
  --repo "${repo}" \
  --debug \
  create_pr \
    --source-branch "${pr_branch}" \
    --target-branch "${TARGET_BRANCH}" \
    --title "${COMMIT_TITLE}" \
    --body "${message}"
