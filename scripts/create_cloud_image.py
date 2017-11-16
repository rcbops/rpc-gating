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

from __future__ import print_function

import argparse
from time import sleep

import openstack.connection


def find_new_image(conn, current_ids, name):
    print("Looking for images with name: {}".format(name))
    for image in conn.image.images():
        if image.name == name:
            print("Found image matching {}".format(name))
            if image.id not in current_ids:
                print("Found new image matching {}".format(name))
                return image
            else:
                print ("Skipping {name}/{id} as it predates the "
                       "required image."
                       .format(name=name, id=image.id))
    raise Exception("Image {i} not found.".format(i=name))


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

    print("Creating image: {name} from Instance: {instance}"
          .format(name=args.imagename, instance=args.serveruuid))

    conn = openstack.connection.from_config(
        cloud_name=args.cloudname, options=args,
    )

    current_images = [
        image for image in conn.image.images()
        if image.name == args.imagename
    ]
    current_ids = [image.id for image in current_images]
    print("Existing image IDs: {}".format(current_ids))

    conn.compute.create_server_image(
        server=args.serveruuid, name=args.imagename
    )
    for _ in xrange(240):
        try:
            saved_image = find_new_image(conn, current_ids, args.imagename)
            break
        except Exception:
            sleep(60)
    else:
        raise Exception("Failed to get image after creating it: {i}"
                        .format(i=args.imagename))

    print("Waiting for image:{i} to achieve active status."
          .format(i=saved_image.name))
    for attempt in xrange(240):
        saved_image = conn.image.get_image(saved_image)
        if saved_image.status == "active":
            print("Image {i} is now active after {a} mins."
                  .format(i=saved_image.name, a=attempt))
            break
        elif saved_image.status == "error":
            raise Exception("Image failed to save: {i}"
                            .format(i=args.imagename))
        print("Current status for image:{i} is '{s}'"
              .format(i=saved_image.name, s=saved_image.status))
        sleep(60)
    else:
        raise Exception("Time out waiting for image {i} to become active."
                        " Waited {a} minutes."
                        .format(i=saved_image.name, a=attempt))

    if current_images:
        print("Deleting old images that use the same name:")
    for image in current_images:
        print("Deleting Image: {n}/{id}".format(n=image.name, id=image.id))
        conn.image.delete_image(image)

    print ("Image Creation Complete.")


if __name__ == "__main__":
    main()
