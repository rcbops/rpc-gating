import datetime
import json
import logging
from operator import itemgetter
import os.path
import sys
import uuid

from bs4 import BeautifulSoup, NavigableString
import click
from jinja2 import Template
import requests

logging.basicConfig()
logger = logging.getLogger("confluenceutils")


class PageNotFound(Exception):
    pass


class Confluence(object):
    """https://developer.atlassian.com/cloud/confluence/rest/"""

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
            logger.error(
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
        logger.info(
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
        logger.info(
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
            "product": row[0],
            "version": row[1],
            "release_notes": row[2],
            "comments": row[3],
        }
        for row in output
    ]


def extract_date(raw_page):
    page = BeautifulSoup(raw_page, "html.parser")
    date = extract_string(page.p).split(":", 1)[1].strip()

    return date


def is_async_release(date, scheduled_window_days=7):
    month_start = date.replace(day=1)
    one_day = datetime.timedelta(days=1)

    saturday = 6
    sunday = 7
    if month_start.isoweekday() == saturday:
        month_start = month_start + one_day

    if month_start.isoweekday() == sunday:
        month_start = month_start + one_day

    scheduled_window_end = month_start + scheduled_window_days * one_day
    if date < scheduled_window_end:
        is_async = False
    else:
        is_async = True

    logger.info(
        "This release has been classified as {release_type}.".format(
            release_type=("asynchronous" if is_async else "scheduled"),
        )
    )
    return is_async


def get_annual_release_page(c, space_key, year, product_release_page_id):
    """ The wiki is organised in a hierarchy:
        Product Releases
            {year} Monthly Releases
                {version}
            Patch and Async Releases
                {version}
        This function gets or creates the "{year} Monthly Releases" page.
    """
    annual_page_title = "{year} Monthly Releases".format(year=year)
    annual_page_body = render_template("confluence_annual_page.j2",
                                       uuid=uuid.uuid4())
    return c.get_or_create_page(
        annual_page_title,
        annual_page_body,
        space_key,
        product_release_page_id)


def _publish_release_to_wiki(
    username, password, base_url, product_release_page, async_release_page,
    component, version, release_notes_url, comment,
):
    current_date = datetime.date.today()
    meta_release = "{year}.{month:02d}".format(
        year=current_date.year, month=current_date.month
    )
    space_key = "RE"

    c = Confluence(username, password, base_url)

    product_releases_page_id = c.get_page(
        product_release_page, space_key,
    )["id"]

    if is_async_release(current_date):
        async_releases_page_id = c.get_page(
            async_release_page,
            space_key,
            parent=product_releases_page_id,
        )["id"]

        parent_page_id = async_releases_page_id
        page_title = "{name} {version}".format(
            name=component,
            version=version,
        )
    else:
        parent_page_id = get_annual_release_page(
            c, space_key, current_date.year, product_releases_page_id)
        page_title = meta_release

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
        date = current_date.isoformat()
        rows = []
    else:
        page_id = resp["id"]
        page_version_id = resp["version"]["number"] + 1
        existing_page_content = resp["body"]["storage"]["value"]
        date = extract_date(existing_page_content)
        rows = [
            row for row in extract_table(existing_page_content)
            if not (
                row["product"] == component
                and row["version"] == version
            )
        ]

    new_row = {
        "product": component,
        "version": version,
        "release_notes": release_notes_url,
        "comments": comment,
    }
    rows.append(new_row)
    rows.sort(key=itemgetter("version"))
    rows.sort(key=itemgetter("product"))

    page_body = render_template("confluence_release_page.j2",
                                meta_version=meta_release,
                                date=date,
                                rows=rows)
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
    default="Product Releases",
)
@click.option(
    "--async-release-page",
    default="Patch and Async Releases",
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
    username, password, base_url, product_release_page, async_release_page,
    component, version, release_notes_url, comment,
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
    publish_release_to_wiki()
