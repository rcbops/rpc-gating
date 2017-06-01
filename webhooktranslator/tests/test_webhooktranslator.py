from mock import patch
import os
import unittest

from webhooktranslator import webhooktranslator


class WebhooktranslatorTestCase(unittest.TestCase):

    def setUp(self):
        webhooktranslator.app.config['TESTING'] = True
        self.app = webhooktranslator.app.test_client()
        self.token = "faketoken"
        os.environ['RACKSPACE_WEBHOOK_TOKEN'] = self.token

    def test_maas_auth_required(self):
        """Test that auth fails without the required headers

        Should fail on authentication as the required header is not supplied
        """
        resp = self.app.get('/maas')
        self.assertEqual(resp.status_code, 403)

    def test_maas_auth_pass(self):
        """Test that auth succeeds.

        Should pass authentication but fail on content type as json is required
        """
        resp = self.app.get(path='/maas',
                            headers={'x-rackspace-webhook-token': self.token})
        self.assertEqual(resp.status_code, 400)

    @patch('webhooktranslator.webhooktranslator.create_jira_issue')
    def test_json_parsing(self, mock_create_jira_issue):
        """Check that create_jira_issue is called correctly

        When a well formed authenticated request is received, create_jira_issue
        should be called with values extracted from the request.
        This test ensures that json is parsed correctly and that a sample
        of values are passed through to create_jira_issue.
        """

        data = open('tests/MaaS_payload_example.json', 'r').read()
        resp = self.app.get(path='/maas',
                            headers={'x-rackspace-webhook-token': self.token},
                            content_type="application/json",
                            data=data)
        self.assertEqual(resp.status_code, 200)
        _, kwargs = mock_create_jira_issue.call_args
        assert "MaaS Alert:" in kwargs['summary']
        assert "WARNING" in kwargs['summary']
        assert "Full Payload" in kwargs['description']
        assert "alert" in kwargs['labels']


if __name__ == '__main__':
    unittest.main()
