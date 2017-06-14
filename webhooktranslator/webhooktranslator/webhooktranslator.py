from functools import wraps
import json
import os

from flask import abort
from flask import Flask
from flask import request
from flup.server.fcgi import WSGIServer
from jira import JIRA


app = Flask(__name__)


def read_env():
    """ Read all required configuration from the environment """
    try:
        app.config.update(
            rackspace_webhook_token=os.environ['RACKSPACE_WEBHOOK_TOKEN'],
            juser=os.environ['JIRA_USER'],
            jpass=os.environ['JIRA_PASSWORD'],
            jproject=os.environ['JIRA_PROJECT'],
            jinstance=os.environ.get(
                'JIRA_INSTANCE',
                'https://rpc-openstack.atlassian.net')
        )
    except KeyError as e:
        raise Exception(
            "Required environment variable not found: {}".format(e.message)
        )


def rackspace_webhook_token_required(f):
    @wraps(f)
    def decorated_function(*args, **kwargs):
        token_header = "x-rackspace-webhook-token"
        # this will throw 5xx if not set
        config_token = app.config['rackspace_webhook_token']
        request_token = request.headers.get(token_header, -1)
        if request_token != config_token:
            abort(401, "{hdr} validation failed".format(hdr=token_header))
        return f(*args, **kwargs)
    return decorated_function


def json_required(f):
    @wraps(f)
    def decorated_function(*args, **kwargs):
        if not request.is_json:
            abort(400, "JSON Required")
        return f(*args, **kwargs)
    return decorated_function


@app.route("/maas", methods=['POST'])
@rackspace_webhook_token_required
@json_required
def maas():
    maas_payload = request.get_json()
    try:
        state = maas_payload['details']['state']
        entity = maas_payload['entity']['label']
        check = maas_payload['check']['label']
        alarm = maas_payload['alarm']['label']
        dash_link = maas_payload['dashboard_link']
    except KeyError as e:
        abort(400, "JSON content missing required key: {}".format(
            e.message
        ))

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

[Dashboard Link|{dash_link}]

{{code:title=Full Payload from MaaS}}
{maas_payload}
{{code}}
        """.format(
            alarm=alarm,
            entity=entity,
            state=state,
            check=check,
            dash_link=dash_link,
            maas_payload=json.dumps(maas_payload,
                                    sort_keys=True,
                                    indent=2,
                                    separators=(',', ': ')
                                    )
        ),
        labels=['maas', 'alert', 'automated', 'jenkins']
    )
    return "SUCCESS"


def create_jira_issue(summary, description, labels):
    authed_jira = JIRA(
        app.config['jinstance'],
        basic_auth=(
            app.config['juser'],
            app.config['jpass']
        )
    )
    authed_jira.create_issue(
        project=app.config['jproject'],
        summary=summary,
        description=description,
        issuetype={'name': 'Task'},
        labels=labels
    )


def wsgi():
    """Run WSGI server for use with an external FCGI server"""
    read_env()
    WSGIServer(app).run()


def main():
    """Run standalone single concurrent request server"""
    read_env()
    app.run()


if __name__ == "__main__":
    main()
