#!/usr/bin/env python
import argparse
import pyrax

parser = argparse.ArgumentParser()
parser.add_argument("--username", required=True)
parser.add_argument("--api-key", required=True)
parser.add_argument("--region", required=True)

args = parser.parse_args()

pyrax.set_setting("identity_type", "rackspace")
pyrax.set_credentials(
    args.username,
    args.api_key,
    region=args.region,
)

token = pyrax.identity.token

monitoring_service = next(
    b for b in pyrax.identity.service_catalog if b["type"] == "rax:monitor"
)
url = monitoring_service["endpoints"][0]["publicURL"]

print "MAAS_AUTH_TOKEN={} MAAS_API_URL={}".format(token, url)
