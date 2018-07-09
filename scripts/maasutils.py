#!/usr/bin/env python
import click
from rackspace_monitoring.providers import get_driver
from rackspace_monitoring.types import Provider
import requests


@click.option("--username", required=True)
@click.option("--api-key", required=True)
@click.group()
@click.pass_context
def cli(ctxt, api_key, username):
    Cls = get_driver(Provider.RACKSPACE)
    ctxt.obj = Cls(username, api_key)


@click.command()
@click.pass_obj
def get_token_url(raxmon):
    token = raxmon.connection.auth_token
    url = raxmon.connection.get_endpoint()

    click.echo("MAAS_AUTH_TOKEN={token} MAAS_API_URL={url}".format(
        token=token, url=url))
    return token, url


@click.command()
@click.option("--token", 'webhook_token', required=True)
@click.pass_context
def set_webhook_token(ctx, webhook_token):
    """Set the token that is included in MaaS webhook notifications.

    This is one method of verifying that receieved requests are
    from MaaS. This is per account.
    """
    auth_token, url = ctx.invoke(get_token_url)
    try:
        response = requests.put(
            "{url}/account".format(url=url),
            headers={'X-Auth-Token': auth_token},
            json={'webhook_token': webhook_token})
        response.raise_for_status()
        click.echo("Webhook token set to {}".format(webhook_token))
    except requests.exceptions.HTTPError as e:
        click.echo(response.text)
        raise e


@click.command()
@click.pass_obj
@click.option("--label", help="label of entity to get ID for", required=True)
def get_entity_id(raxmon, label):
    entities = raxmon.list_entities()
    for e in entities:
        if label == e.label:
            click.echo(e.id)


cli.add_command(get_token_url)
cli.add_command(set_webhook_token)
cli.add_command(get_entity_id)


if __name__ == "__main__":
    cli()
