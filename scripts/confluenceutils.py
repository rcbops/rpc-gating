import datetime
import json
import logging
import os.path
import re
import sys

from bs4 import BeautifulSoup, NavigableString
import click
from jinja2 import Template
import requests
from tenacity import retry, stop_after_delay, wait_random_exponential


class PageNotFound(Exception):
    pass


class Confluence(object):
    """https://developer.atlassian.com/cloud/confluence/rest/ ."""

    def __init__(self, username, password, base_url):
        self.session = requests.Session()
        adapter = requests.adapters.HTTPAdapter(max_retries=3)
        self.session.mount('http://', adapter)
        self.session.mount('https://', adapter)
        self.session.auth = (username, password)
        self.base_url = base_url
        self.content_url = self.base_url + "/wiki/rest/api/content"
        self.search_url = self.content_url + "/search"

    def get_page(self, title, space_key, parent=None, additional_params=None):
        fields = {
            "title": '"{t}"'.format(t=title),
            "space": space_key,
            "type": "page",
        }
        if parent:
            fields["parent"] = parent

        params = {
            "cql": " and ".join("=".join(i) for i in fields.items())
        }
        if additional_params:
            params.update(additional_params)

        resp = self.session.get(self.search_url, params=params)
        resp.raise_for_status()
        resp_data = resp.json()
        if resp_data["size"] == 0:
            raise PageNotFound
        elif resp_data["size"] > 1:
            logging.error(
                json.dumps(
                    resp_data,
                    sort_keys=True,
                    indent=4,
                    separators=(',', ': '),
                )
            )
            raise Exception("More than one page found.")

        return resp_data["results"][0]

    def create_page(self, title, body, space_key, parent):
        content = {
            "ancestors": [
                {
                    "id": parent
                }
            ],
            "type": "page",
            "title": title,
            "space": {"key": space_key},
            "body": {"storage": {"value": body, "representation": "storage"}},
        }
        resp = self.session.post(self.content_url, json=content)
        resp.raise_for_status()
        resp_data = resp.json()
        logging.info(
            "The following page has been created: {url}".format(
                url=resp_data["_links"]["base"] + resp_data["_links"]["webui"],
            )
        )
        return resp_data["id"]

    def get_or_create_page(self, title, body, space_key, parent):
        try:
            return self.get_page(title, space_key, parent)["id"]
        except PageNotFound:
            return self.create_page(title, body, space_key, parent)

    def update_page(self, page_id, page_version_id, space_key, title, body):
        """Update and existing Confluence page.

           page_version_id: must be equal to the value of the current page
                            version plus one otherwise 409 error returned by
                            Confluence. This prevents unknowingly overwriting
                            changes.
        """
        content = {
            "type": "page",
            "title": title,
            "space": {"key": space_key},
            "body": {"storage": {"value": body, "representation": "storage"}},
            "version": {"number": page_version_id},
        }
        url = "{base}/{page_id}".format(base=self.content_url, page_id=page_id)
        resp = self.session.put(url, json=content)
        resp.raise_for_status()
        resp_data = resp.json()
        logging.info(
            "The following page has been updated: {url}".format(
                url=resp_data["_links"]["base"] + resp_data["_links"]["webui"],
            )
        )


def render_template(template_relative_path, **vars):
    script_dir = os.path.dirname(os.path.realpath(__file__))
    with open(os.path.join(script_dir, template_relative_path)) as tf:
        page_template = tf.read()

    template = Template(page_template)
    return template.render(**vars)


def extract_string(element):
    return " ".join(
        filter(lambda x: isinstance(x, NavigableString), element.descendants)
    )


def extract_table(raw_page):
    page = BeautifulSoup(raw_page, "html.parser")
    table = page.find("table", attrs={"class": "wrapped"})
    table_body = table.find("tbody")
    rows_with_header = table_body.find_all("tr")
    rows = rows_with_header[1:]
    output = []
    for row in rows:
        output.append([extract_string(c) for c in row])

    return [
        {
            "version": row[0],
            "date": row[1],
            "release_notes": row[2],
            "comments": row[3],
        }
        for row in output
    ]


def row_sort_key(row):
    prerelease_map = {
        "alpha": 0,
        "beta": 1,
        "rc": 2,
        None: 3,
    }

    def int_or_none(x):
        return x if x is None else int(x)

    version_regex = (
        r"^(?P<version>r?"
        r"(?P<major>[0-9]+)\."
        r"(?P<minor>[0-9]+)\."
        r"(?P<patch>[0-9]+)"
        r"(-(?P<prerelease>alpha|beta|rc)\."
        r"(?P<prerelease_version>[0-9]+))?)$"
    )

    v = re.match(version_regex, row["version"])
    major = int(v.group("major"))
    minor = int_or_none(v.group("minor"))
    patch = int_or_none(v.group("patch"))
    prerelease = prerelease_map[v.group("prerelease")]
    prerelease_version = int(v.group("prerelease_version") or 0)

    return (major, minor, patch, prerelease, prerelease_version)


@retry(
    wait=wait_random_exponential(multiplier=1, max=60),
    stop=stop_after_delay(300),
)
def _publish_release_to_wiki(
    username, password, base_url, product_release_page, component, version,
    release_notes_url, comment,
):
    space_key = "RE"

    c = Confluence(username, password, base_url)

    parent_page_id = c.get_page(product_release_page, space_key)["id"]

    page_title = "{name} Releases".format(name=component)

    try:
        resp = c.get_page(
            page_title,
            space_key,
            parent=parent_page_id,
            additional_params={"expand": "body.storage,version"},
        )
    except PageNotFound:
        page_id = None
        page_version_id = None
        rows = []
    else:
        page_id = resp["id"]
        page_version_id = resp["version"]["number"] + 1
        existing_page_content = resp["body"]["storage"]["value"]
        rows = [
            row for row in extract_table(existing_page_content)
            if not row["version"] == version
        ]

    new_row = {
        "version": version,
        "release_notes": release_notes_url,
        "comments": comment,
        "date": datetime.date.today().isoformat(),
    }
    rows.append(new_row)
    rows.sort(key=row_sort_key)

    page_body = render_template("confluence_release_page.j2", rows=rows)
    if page_id:
        c.update_page(
            page_id, page_version_id, space_key, page_title, page_body
        )
    else:
        c.create_page(page_title, page_body, space_key, parent_page_id)


@click.command()
@click.option(
    "--username",
    required=True,
    envvar="JIRA_USER",
    help="Confluence username.",
)
@click.option(
    "--password",
    required=True,
    envvar="JIRA_PASS",
    help="Confluence password.",
)
@click.option(
    "--base-url",
    required=False,
    default="https://rpc-openstack.atlassian.net",
    help="Confluence instance.",
)
@click.option(
    "--product-release-page",
    default="Managed Releases",
)
@click.option(
    "--component",
    required=True,
    help="Component name, e.g. rpc-foo.",
)
@click.option(
    "--version",
    required=True,
    help="Component release version, e.g. 1.0.4.",
)
@click.option(
    "--release-notes-url",
    help=(
        "Component release notes URL, e.g. "
        "https://github.com/rcbops/rpc-foo/release/tag/1.0.0."
    ),
)
@click.option(
    "--comment",
    default="",
    help="Additional information to add against release in wiki.",
)
def publish_release_to_wiki(
    username, password, base_url, product_release_page, component, version,
    release_notes_url, comment,
):
    if not release_notes_url:
        ctx_obj = click.get_current_context().obj
        try:
            release_notes_url = ctx_obj.release_url
            del ctx_obj
        except AttributeError:
            sys.exit(
                "'--release-notes-url' cannot be determined because a release "
                "is not being created, this option is therefore required."
            )

    _publish_release_to_wiki(**vars())


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    publish_release_to_wiki()
