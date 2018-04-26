from email.mime.text import MIMEText
import logging
from subprocess import Popen, PIPE

import click
import requests

logger = logging.getLogger("notifications")


def try_context(ctx_obj, var, var_name, context_attr):
    """Try and get a value from context, otherwise use value supplied
       as an arg/option. If neither are supplied, bail.

       The idea is to prevent the user from having to supply the same
       option twice when executing multiple commands.
         var: the value supplied as an option
         var_name: Name of the option
         context_attr: attribute to attempt to read from the context object.
    """
    logger.debug("Try context: {var_name}:{var}, {c_attr}".format(
        var_name=var_name, var=var, c_attr=context_attr
    ))
    if var is None:
        try:
            var = getattr(ctx_obj, context_attr)
        except AttributeError as e:
            raise ValueError("No value found for {var_name} ({e})".format(
                var_name=var_name, e=e))

    logger.debug("Try context Result: {var_name} --> {result}".format(
        var_name=var_name, result=var
    ))
    return var


def generate_message_data(subject, body):
    """Return e-mail message data."""
    if not (subject or body):
        ctx_obj = click.get_current_context().obj

    if not subject:
        owner = ctx_obj.owner.login
        repo = ctx_obj.name
        try:
            version = ctx_obj.version
        except AttributeError:
            logger.error(
                "`version` is missing from the Click context object, if "
                "this notification is not being sent at the same time as the "
                "release is created, `--subject` must be supplied."
            )
            raise
        subject = "New release: {o}/{r} version {v}".format(
            v=version,
            o=owner,
            r=repo,
        )

    if not body:
        try:
            url = ctx_obj.release_url
        except AttributeError:
            logger.error(
                "`release_url` is missing from the Click context object, if "
                "this notification is not being sent at the same time as the "
                "release is created, `--body` must be supplied."
            )
            raise
        body = (
            "The release notes for this new release can be found on "
            "{link}\n\nRegards,\nRelease Engineering"
        ).format(link=url)
    logger.debug("E-mail subject: {subject}".format(subject=subject))
    logger.debug("E-mail body:\n{body}".format(body=body))
    return {
        "from": "RPC-Jenkins@mailgun.rpc.jenkins.cit.rackspace.net",
        "subject": subject,
        "body": body,
    }


@click.group()
@click.option("--debug/--no-debug")
def cli(debug):
    level = logging.WARNING
    if debug:
        level = logging.DEBUG
    logging.basicConfig(level=level)


@cli.command()
@click.option("--to", required=True)
@click.option(
    "--subject",
    help="Subject of release announcement message."
         " May be omitted if create_release is used.")
@click.option(
    "--body",
    help="Body of release announcement message."
         " May be omitted if create_release is used.")
def mail(to, subject, body):
    """Send mail via local MTA"""
    data = generate_message_data(subject=subject, body=body)
    msg = MIMEText(data["body"])
    msg["From"] = data["from"]
    msg["To"] = to
    msg["Subject"] = data["subject"]
    logger.debug("Sending notification mail To: {to} Subject:{s}".format(
        to=to, s=subject
    ))
    p = Popen(["/usr/sbin/sendmail", "-t", "-oi"], stdin=PIPE)
    p.communicate(msg.as_string())


@cli.command()
@click.option(
    "--to",
    required=True,
    help="Must be added to authorised recipients list if using a free mailgun"
         " account.")
@click.option(
    "--subject",
    help="Subject of release announcement message."
         " May be omitted if create_release is used.")
@click.option(
    "--body",
    help="Body of release announcement message."
         " May be omitted if create_release is used.")
@click.option("--mailgun-api-key", required=True, envvar="MAILGUN_API_KEY")
@click.option("--mailgun-endpoint", required=True, envvar="MAILGUN_ENDPOINT")
def mailgun(to, subject, body, mailgun_api_key, mailgun_endpoint):
    """Send mail via mailgun api."""
    msg = generate_message_data(subject=subject, body=body)
    post_data = {"from": msg["from"],
                 "to": [to],
                 "subject": msg["subject"],
                 "text": msg["body"],
                 }
    logger.debug("Mailgun post data: {}".format(post_data))
    response = requests.post(
        "{endpoint}/messages".format(endpoint=mailgun_endpoint),
        auth=("api", mailgun_api_key),
        data=post_data
    )
    logger.debug("Mailgun response: {}".format(response.text))
    response.raise_for_status()
    return response


if __name__ == "__main__":
    cli()
