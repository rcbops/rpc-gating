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
         " May be omitted if create_release is used as that"
         " generates a subject.")
@click.option(
    "--body",
    help="Contents of release announcement message"
         " May be omitted if generate_release_notes is used.")
def mail(to, subject, body):
    """Send mail via local MTA"""
    ctx_obj = click.get_current_context().obj
    subject = try_context(ctx_obj, subject, "subject", "release_subject")
    body = try_context(ctx_obj, body, "body", "release_notes")
    msg = MIMEText(body)
    msg["From"] = "RPC-Jenkins@rackspace.com"
    msg["To"] = to
    msg["Subject"] = subject
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
         " May be omitted if create_release is used as that"
         " generates a subject.")
@click.option(
    "--body",
    help="Contents of release announcement message"
         " May be omitted if generate_release_notes is used.")
@click.option("--mailgun-api-key", required=True, envvar="MAILGUN_API_KEY")
@click.option("--mailgun-endpoint", required=True, envvar="MAILGUN_ENDPOINT")
def mailgun(to, subject, body, mailgun_api_key, mailgun_endpoint):
    """Send mail via mailgun api."""
    ctx_obj = click.get_current_context().obj
    subject = try_context(ctx_obj, subject, "subject", "release_subject")
    body = try_context(ctx_obj, body, "body", "release_notes")
    return requests.post(
        "{endpoint}/messages".format(endpoint=mailgun_endpoint),
        auth=("api", mailgun_api_key),
        data={"from": "RPC-Jenkins@rackspace.com",
              "to": [to],
              "subject": subject,
              "text": body
              }
    )


if __name__ == "__main__":
    cli()
