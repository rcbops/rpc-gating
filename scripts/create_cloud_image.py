#!/usr/bin/env python2
#
# Copyright 2016, Rackspace US, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Basic tool to save an image of a server an OpenStack Cloud."""

import argparse
from time import sleep

import openstack.connection


def main():
    """Run the main application."""

    # Setup argument parsing
    parser = argparse.ArgumentParser(
        description='Basic cloud CLI utilities',
        epilog='Licensed "Apache 2.0"')

    parser.add_argument(
        '-r',
        '--cloudregion',
        help='<Required> The region to execute commands against',
        required=True,
        dest="region_name"
    )

    parser.add_argument(
        '-s',
        '--serveruuid',
        help='<Required> The server uuid to execute commands against',
        required=True
    )

    parser.add_argument(
        '-i',
        '--imagename',
        help='<Required> The name to use for the saved image',
        required=True
    )

    parser.add_argument(
        '-c',
        '--cloudname',
        help='<Required> The name of the cloud configuration to use',
        required=True
    )

    args = parser.parse_args()

    conn = openstack.connection.from_config(
        cloud_name=args.cloudname, options=args,
    )

    current_images = [
        image for image in conn.image.images()
        if image.name == args.imagename
    ]
    current_ids = [image.id for image in current_images]

    conn.compute.create_server_image(
        server=args.serveruuid, name=args.imagename
    )

    for image in conn.image.images():
        if image.name == args.imagename and image.id not in current_ids:
            saved_image = image
            break

    timeout = 900
    while timeout > 0 and saved_image.status not in ("active", "error"):
        timeout -= 60
        sleep(60)
        saved_image = conn.image.get_image(saved_image)

    if saved_image.status == "active":
        for image in current_images:
            conn.image.delete_image(image)


if __name__ == "__main__":
    main()
