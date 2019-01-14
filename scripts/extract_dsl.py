#!/usr/bin/env python
import os
import yaml
import xml.etree.ElementTree as ET

import click


@click.group()
def cli():
    pass


@cli.command()
@click.option(
    "--jjbfile",
    type=click.File('r'),
    required=True,
    help="Extract dsl from this JJB job")
@click.option(
    "--outdir",
    type=click.Path(exists=True),
    required=True,
    help="Dir to write extracted groovy into"
)
def extract_dsl_from_jjb(jjbfile, outdir):
    jjb = yaml.safe_load(jjbfile)
    for item in jjb:
        key = item.keys()[0]
        if key in ("job", "job-template") and 'dsl' in item[key]:
            dsl = item[key]['dsl'].replace('{{', '{').replace('}}', '}')
            outfile = "{outdir}/{base}-{item}.groovy".format(
                outdir=outdir,
                base=os.path.basename(jjbfile.name).split('.')[0],
                item=item[key]['name'])
            with open(outfile, "w") as outf:
                outf.write(dsl)


@cli.command()
@click.option(
    "--xmljob",
    type=click.File('r'),
    required=True,
    help="Extract dsl from this Jenkins job defintion")
@click.option(
    "--outdir",
    type=click.Path(exists=True),
    required=True,
    help="Dir to write extracted groovy into"
)
def extract_dsl_from_xml(xmljob, outdir):
    root = ET.fromstring(xmljob.read())
    dsl = root.find('.//script')
    if dsl is not None:
        outfile = "{outdir}/{xmljob.name}.groovy".format(
            outdir=outdir,
            xmljob=xmljob)
        with open(outfile, "w") as outf:
            outf.write(dsl.text)


if __name__ == "__main__":
    cli()
