#!/usr/bin/env python
import click
import pyrax
import requests


@click.option("--username", required=True)
@click.option("--api-key", required=True)
@click.group()
def cli(api_key, username):
    pyrax.set_setting("identity_type", "rackspace")
    pyrax.set_credentials(
        username,
        api_key
    )


@click.command()
def get_token_url():
    token = pyrax.identity.token

    monitoring_service = next(
        b for b in pyrax.identity.service_catalog if b["type"] == "rax:monitor"
    )
    url = monitoring_service["endpoints"][0]["publicURL"]
    click.echo("MAAS_AUTH_TOKEN={token} MAAS_API_URL={url}".format(
        token=token, url=url))
    return token, url


@click.command()
@click.option("--token", 'webhook_token', required=True)
@click.pass_context
def set_webhook_token(ctx, webhook_token):
    """Sets the token that is included in MaaS webhook notifications

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


cli.add_command(get_token_url)
cli.add_command(set_webhook_token)


if __name__ == "__main__":
    cli()
