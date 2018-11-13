#!/usr/bin/env python

from os import environ
import unittest


class TestREJobEnv(unittest.TestCase):
    def setUp(self):
        self.expected_env_vars = {
            "RE_JOB_ACTION": "test",
            "RE_JOB_FLAVOR": "performance1-1",
            "RE_JOB_IMAGE": "xenial",
            "RE_JOB_NAME": "gating-pre-merge",
            "RE_JOB_PROJECT_NAME": "gating-pre-merge",
            "RE_JOB_REPO_NAME": "rpc-gating",
            "RE_JOB_SCENARIO": "standard_job_pre_merge",
            "RE_JOB_TRIGGER": "PULL",
            "RE_JOB_TRIGGER_DETAIL": "{title}/{pull_id}".format(
                title=environ.get("ghprbPullTitle"),
                pull_id=environ.get("ghprbPullId"),
            ),
        }
        self.system_env_vars = {k: v for k, v in environ.items()}

    def test_all_env_vars_expected(self):
        self.assertSetEqual(
            set(self.expected_env_vars),
            set(v for v in self.system_env_vars if v.startswith("RE_JOB_"))
        )

    def test_env_vars_values(self):
        for name, expected_value in self.expected_env_vars.items():
            self.assertEqual(expected_value, self.system_env_vars[name])


if __name__ == "__main__":
    unittest.main()
