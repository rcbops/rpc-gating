from functools import wraps
import os

from flask import abort
from flask import Flask
from flask import request
from flup.server.fcgi import WSGIServer
from jira import JIRA


app = Flask(__name__)


def rackspace_webhook_token_required(f):
    @wraps(f)
    def decorated_function(*args, **kwargs):
        token_header = "x-rackspace-webhook-token"
        token = os.environ.get('RACKSPACE_WEBHOOK_TOKEN', '')
        if request.headers.get(token_header, 'invalidtoken') != token:
            return abort(403, "x-rackspace-webhook-token validation failed")
        return f(*args, **kwargs)
    return decorated_function


def json_required(f):
    @wraps(f)
    def decorated_function(*args, **kwargs):
        if not request.is_json:
            abort(400, "JSON Required")
        return f(*args, **kwargs)
    return decorated_function


@app.route("/maas", methods=['GET', 'POST'])
@rackspace_webhook_token_required
@json_required
def maas():
    maas_payload = request.get_json()
    state = maas_payload['details']['state']
    entity = maas_payload['entity']['id']
    check = maas_payload['check']['id']
    alarm = maas_payload['alarm']['id']

    create_jira_issue(
        summary="MaaS Alert: {alarm}/{entity}:{state}".format(
            alarm=alarm,
            entity=entity,
            state=state
        ),
        # This string contains python string formats: {}
        # and jira markup using escaped braces: {{}}
        description="""
Alarm {alarm} for entity {entity} is in {state} state.
The check is {check}.

{{code:title=Full Payload from MaaS}}
{maas_payload}
{{code}}
        """.format(
            alarm=alarm,
            entity=entity,
            state=state,
            check=check,
            maas_payload=maas_payload
        ),
        labels=['maas', 'alert', 'automated', 'jenkins']
    )
    return "SUCCESS"


def create_jira_issue(summary, description, labels):
    juser = os.environ['JIRA_USER']
    jpass = os.environ['JIRA_PASSWORD']
    jproject = os.environ['JIRA_PROJECT']
    jinstance = os.environ.get(
        'JIRA_INSTANCE',
        'https://rpc-openstack.atlassian.net')
    authed_jira = JIRA(jinstance, basic_auth=(juser, jpass))
    authed_jira.create_issue(
        project=jproject,
        summary=summary,
        description=description,
        issuetype={'name': 'Task'},
        labels=labels
    )


def wsgi():
    """Run WSGI server for use with an external FCGI server"""
    WSGIServer(app).run()


def main():
    """Run standalone single concurrent request server"""
    app.run()


if __name__ == "__main__":
    main()
