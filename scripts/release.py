import logging
import os
import pipes
import subprocess
import traceback

import click
import git

import confluenceutils
import ghutils
from reexceptions import ReleaseFailureException
from notifications import mail, mailgun, try_context


# release notes
@click.command()
@click.option(
    "--script",
    'scripts',
    help="Execute these script(s) to generate release notes, may be specified"
         " multiple times. After all scripts are executed, release notes must"
         " have been written to $WORKSPACE/artifacts/release_notes."
         " Scripts can be marked as optional prefixing with 'optional:'"
         " eg optional:/opt/myscript",
    multiple=True
)
@click.option(
    "--file", "rnfile",
    help="Read release notes from this file."
)
@click.option(
    "--text",
    help="Text of the release notes."
)
@click.option(
    '--version',
    help="Symbolic name of Release (eg r14.1.99)"
         " Required when using --script."
)
@click.option(
    "--prev-version",
    help="Last released version. Release notes scripts should compare "
         "prev-version to version. "
         "Required when using --script.",
)
@click.option(
    "--clone-dir",
    help="Root of the repo dir. May be omitted if clone was used earlier"
         " in the chain."
)
@click.option(
    "--out-file", "dst_file",
    required=True,
    help="Release notes will be written to this file")
def generate_release_notes(scripts, rnfile, text, version, prev_version,
                           clone_dir, dst_file):
    ctx_obj = click.get_current_context().obj
    clone_dir = try_context(ctx_obj, clone_dir, "clone_dir", "clone_dir")
    os.system("mkdir -p {}".format(os.path.dirname(dst_file)))
    if scripts:
        version = try_context(ctx_obj, version, "version", "version")
        logging.debug(
            "Generating release notes from scripts: {}".format(scripts)
        )
        sub_env = os.environ.copy()
        sub_env["RE_HOOK_PREVIOUS_VERSION"] = prev_version.encode('ascii')
        sub_env["RE_HOOK_VERSION"] = version.encode('ascii')
        sub_env["RE_HOOK_RELEASE_NOTES"] = dst_file.encode('ascii')
        sub_env["RE_HOOK_REPO_HTTP_URL"] = ctx_obj.clone_url.encode('ascii')
        script_work_dir = "{cwd}/{clone_dir}".format(
            cwd=os.getcwd(),
            clone_dir=clone_dir
        )
        logging.debug("script work dir: {}".format(script_work_dir))
        logging.debug("script env: {}".format(sub_env))
        for script in scripts:
            try:
                optional = False
                if script.startswith("optional:"):
                    script = script.replace("optional:", "")
                    optional = True
                script_path = "{script_work_dir}/{script}".format(
                    script_work_dir=script_work_dir,
                    script=script
                )
                if os.path.exists(script_path):
                    logging.debug("Executing script: {}".format(script_path))
                    proc = subprocess.Popen(
                        script_path,
                        env=sub_env,
                        cwd=script_work_dir
                    )
                    proc.communicate()
                    logging.debug("Script Execution Complete: {}"
                                  .format(script_path))
                    if proc.returncode != 0:
                        logging.error(
                            "Script {s} failed. (Return Code: {rc})".format(
                                s=script_path, rc=proc.returncode
                            ))
                        click.get_current_context().exit(-1)
                else:
                    if not optional:
                        logging.error(
                            "Aborting as required hook script not found: {}"
                            .format(script_path))
                        click.get_current_context().exit(-1)
                    else:
                        logging.info(
                            "Optional hook script not found: {}"
                            .format(script_path))

            except Exception as e:
                logging.error(traceback.format_exc())
                logging.error("Failed to generate release notes: {}".format(e))
                click.get_current_context().exit(-1)
        if not os.path.exists(dst_file):
            logging.error("Scripts did not generate release notes or did not"
                          " place them in"
                          " $WORKSPACE/{df}".format(df=dst_file))
            click.get_current_context().exit(-1)
    elif rnfile:
        # Release notes are already in a file, just copy them to where
        # they need to be
        logging.debug("Reading release notes from file: {}".format(rnfile))
        return_code = subprocess.check_call(
            ["cp", pipes.quote(rnfile), dst_file])
        if return_code != 0:
            logging.error(
                "Failed to read release notes file: {}".format(rnfile))
            click.get_current_context().exit(-1)
    elif text:
        logging.debug("Release notes supplied inline: {}".format(text))
        with open(dst_file, "w") as out:
            out.write(text)
    else:
        logging.error("One of script, text or file must be specified. "
                      "No release notes generated")
        click.get_current_context().exit(-1)


@click.command()
@click.option(
    '--version',
    required=True,
    help="Symbolic name of Release (eg r14.1.99)"
)
@click.option(
    '--ref',
    help="Reference to create release from (branch, SHA etc)"
         " May be omitted if supplied to clone earlier in the chain."
)
@click.option(
    "--clone-dir",
    help="Root of the repo dir. May be omitted if clone was used earlier"
         " in the chain."
)
def publish_tag(version, ref, clone_dir):
    ctx_obj = click.get_current_context().obj
    ctx_obj.version = version
    ref = try_context(ctx_obj, ref, "ref", "rc_ref")
    clone_dir = try_context(ctx_obj, clone_dir, "clone_dir", "clone_dir")

    repo = git.Repo(clone_dir)
    refsha = repo.rev_parse(ref).hexsha
    tagsha = None
    try:
        tagsha = repo.tags[version].commit.hexsha
    except IndexError:
        pass
    if tagsha is not None:
        # tag exists
        if tagsha == refsha:
            # Existing tag SHA matches ref
            logging.info(
                "Tag '{tag}' already exists and the ref matches,"
                "nothing to do.".format(tag=version)
            )
        else:
            # Existing tag SHA doesn't not match ref
            if ctx_obj.re_release:
                # Overwrite flag is set so we remove the existing tag
                # and recreate it pointing to ref
                repo.delete_tag(version)
                repo.remote().push(":{tag}".format(tag=version))
                logging.info(
                    "Tag '{tag}@{tagsha}' removed."
                    .format(tag=version, tagsha=tagsha)
                )
                _publish_tag(repo, version, ref)
            else:
                # Tag exists, doesn't match and overwrite flag not set, bail.
                raise ReleaseFailureException(
                    "Trying to create tag {tag}@{refsha}"
                    " but {tag}@{tagsha} already exists."
                    "Failing because re release was not specified."
                    .format(tag=version, refsha=refsha, tagsha=tagsha)
                )
    else:
        # Tag doesn't exist, create it.
        _publish_tag(repo, version, ref)


def _publish_tag(repo, version, ref):
    repo.create_tag(version, ref, message=version)
    repo.remotes.origin.push(version)
    logging.info(
        "New tag '{tag}' successfully pushed to repository."
        .format(tag=version)
    )


@click.command()
def usage():
    commands = ghutils.cli.commands
    for name, command in commands.items():
        print("<br>{}:<br>".format(name))
        help_lines = command.get_help(click.get_current_context()).split('\n')
        for line in help_lines:
            if (not (line.startswith("Usage:") or (
                    line.startswith("Options:")) and line)):
                print("{}<br>".format(line))


ghutils.cli.add_command(generate_release_notes)
ghutils.cli.add_command(publish_tag)
ghutils.cli.add_command(mail)
ghutils.cli.add_command(mailgun)
ghutils.cli.add_command(usage)
ghutils.cli.add_command(confluenceutils.publish_release_to_wiki)


if __name__ == "__main__":
    ghutils.cli()
